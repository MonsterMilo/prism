package prism.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import prism.api.EventBus;
import prism.api.Game;
import prism.api.Logger;
import prism.api.Plugin;
import prism.api.PluginContext;
import prism.api.PluginInfo;
import prism.api.Scheduler;
import prism.api.event.PluginDisableEvent;
import prism.api.event.PluginEnableEvent;

/**
 * Loads every jar in the plugins folder and hot-reloads it when the file changes.
 * Each jar is copied to a cache file first, so the original stays unlocked and can be replaced
 * while the game runs. A changed file is reloaded once it has stopped changing for one scan.
 */
final class PluginManager {
    private static final long SCAN_MS = 1000;

    private final Path pluginsDir, cacheDir, dataDir;
    private final ClassLoader parent;
    private final Bus bus;
    private final Game game;
    private final Logger log = CoreLog.forSource("plugins");

    /** file name -> loaded plugin */
    private final Map<String, Loaded> loaded = new LinkedHashMap<>();
    /** file name -> signature seen on the previous scan (to wait until a copy has finished) */
    private final Map<String, String> lastSeen = new HashMap<>();
    private Thread thread;
    private volatile boolean running;
    private int cacheCounter;
    private boolean firstScan = true;

    PluginManager(Path home, ClassLoader parent, Bus bus, Game game) {
        this.pluginsDir = home.resolve("plugins");
        this.cacheDir = home.resolve("cache").resolve("plugins");
        this.dataDir = home.resolve("data");
        this.parent = parent;
        this.bus = bus;
        this.game = game;
    }

    void start() {
        try {
            Files.createDirectories(pluginsDir);
            Files.createDirectories(cacheDir);
            clearCache();
        } catch (IOException e) {
            log.error("can't create plugin folders", e);
        }
        running = true;
        List<String> first = scan();
        if (!first.isEmpty()) game.chat("§d[Prism] §fLoaded " + String.join(", ", first));
        else game.chat("§d[Prism] §fLoaded");
        thread = new Thread(this::loop, "Prism Plugins");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            List<String> names = new ArrayList<>(loaded.keySet());
            for (int i = names.size() - 1; i >= 0; i--) unload(names.get(i));
        }
        clearCache();
    }

    synchronized List<PluginInfo> plugins() {
        List<PluginInfo> out = new ArrayList<>();
        for (Loaded l : loaded.values()) out.add(l.info);
        return out;
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(SCAN_MS);
            } catch (InterruptedException e) {
                return;
            }
            List<String> changed = scan();
            if (!changed.isEmpty()) game.chat("§d[Prism] §f" + String.join(", ", changed));
        }
    }

    /** One pass over the folder. Returns what changed, e.g. "Loaded Example" or "Reloaded Example". */
    private synchronized List<String> scan() {
        List<String> done = new ArrayList<>();
        Map<String, String> now = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path p : ds) {
                try {
                    now.put(p.getFileName().toString(), Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            log.error("can't read plugins folder", e);
            return done;
        }
        for (String name : new ArrayList<>(loaded.keySet())) {
            if (!now.containsKey(name)) {
                unload(name);
                log.info(name + " removed");
            }
        }
        for (Map.Entry<String, String> e : now.entrySet()) {
            String name = e.getKey(), sig = e.getValue();
            Loaded cur = loaded.get(name);
            if (cur != null && cur.signature.equals(sig)) continue;
            boolean stable = firstScan || sig.equals(lastSeen.get(name));
            if (!stable) continue;   // still being written; look again next scan
            boolean reload = cur != null;
            if (reload) unload(name);
            Loaded l = load(pluginsDir.resolve(name), sig);
            if (l != null) {
                loaded.put(name, l);
                done.add(firstScan ? l.info.name() : (reload ? "Reloaded " : "Loaded ") + l.info.name());
            }
        }
        lastSeen.clear();
        lastSeen.putAll(now);
        firstScan = false;
        return done;
    }

    private Loaded load(Path jar, String signature) {
        String file = jar.getFileName().toString();
        Path copy = cacheDir.resolve((cacheCounter++) + "-" + file);
        URLClassLoader cl = null;
        try {
            Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
            PluginInfo info = readInfo(copy, file);
            for (Loaded other : loaded.values()) {
                if (other.info.id().equals(info.id())) {
                    throw new IllegalStateException("id '" + info.id() + "' already used by " + other.file);
                }
            }
            cl = new URLClassLoader(new URL[] {copy.toUri().toURL()}, parent);
            Plugin plugin = (Plugin) Class.forName(info.main(), true, cl).getDeclaredConstructor().newInstance();
            Loaded l = new Loaded(file, signature, info, plugin, cl, copy);
            plugin.onEnable(l.ctx);
            log.info("enabled " + info);
            bus.post(new PluginEnableEvent(info));
            return l;
        } catch (Throwable t) {
            log.error("failed to load " + file, t);
            game.chat("§d[Prism] §c" + file + " failed to load (see log)");
            closeQuietly(cl);
            deleteQuietly(copy);
            return null;
        }
    }

    private void unload(String file) {
        Loaded l = loaded.remove(file);
        if (l == null) return;
        bus.post(new PluginDisableEvent(l.info));
        try {
            l.plugin.onDisable();
        } catch (Throwable t) {
            log.error(l.info.name() + " failed to disable", t);
        }
        l.ctx.close();
        closeQuietly(l.loader);
        deleteQuietly(l.copy);
        log.info("disabled " + l.info);
    }

    private static PluginInfo readInfo(Path jar, String file) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("plugin.properties");
            if (entry == null) throw new IOException(file + " has no plugin.properties");
            Properties p = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                p.load(in);
            }
            String id = p.getProperty("id"), main = p.getProperty("main");
            if (id == null || main == null) throw new IOException(file + ": plugin.properties needs id and main");
            if (!id.matches("[a-z0-9][a-z0-9_-]*")) throw new IOException(file + ": id must be lowercase letters, digits, - or _");
            return new PluginInfo(id, p.getProperty("name", id), p.getProperty("version", "0"), main);
        }
    }

    private void clearCache() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir)) {
            for (Path p : ds) deleteQuietly(p);
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(URLClassLoader cl) {
        if (cl == null) return;
        try {
            cl.close();
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // still locked on Windows; removed by clearCache() on the next start
        }
    }

    private final class Loaded {
        final String file, signature;
        final PluginInfo info;
        final Plugin plugin;
        final URLClassLoader loader;
        final Path copy;
        final Context ctx;

        Loaded(String file, String signature, PluginInfo info, Plugin plugin, URLClassLoader loader, Path copy) {
            this.file = file;
            this.signature = signature;
            this.info = info;
            this.plugin = plugin;
            this.loader = loader;
            this.copy = copy;
            this.ctx = new Context(info);
        }
    }

    private final class Context implements PluginContext {
        private final PluginInfo info;
        private final Logger log;
        private final Bus.Owned events;
        private final Tasks.Owned tasks;

        Context(PluginInfo info) {
            this.info = info;
            this.log = CoreLog.forSource(info.id());
            this.events = bus.owned(info.id());
            this.tasks = new Tasks.Owned(log);
        }

        @Override public PluginInfo info() { return info; }
        @Override public Logger log() { return log; }
        @Override public EventBus events() { return events; }
        @Override public Scheduler scheduler() { return tasks; }
        @Override public Game game() { return game; }

        @Override
        public Path dataFolder() {
            Path p = dataDir.resolve(info.id());
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                log.error("can't create " + p, e);
            }
            return p;
        }

        void close() {
            events.close();
            tasks.close();
        }
    }
}
