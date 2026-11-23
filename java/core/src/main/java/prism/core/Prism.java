package prism.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import prism.api.Logger;
import prism.api.event.ShutdownEvent;
import prism.core.hook.GameHooks;
import prism.core.hook.Hooks;

/**
 * Started by the native agent once the game's classes are loaded.
 * Layout of the Prism folder (home): bin/ (agent + core), plugins/, data/, logs/, cache/.
 */
public final class Prism {
    public static final String VERSION = "0.2.0";

    private static final AtomicBoolean running = new AtomicBoolean();
    private static Bus bus;
    private static PluginManager plugins;
    private static Hooks hooks;
    private static Thread shutdownHook;

    private Prism() {}

    /**
     * @param home      the Prism folder
     * @param mappings  "mcp", "srg" or "notch"
     * @param minecraft the game's Minecraft class
     * @return null when started, else why it failed
     */
    public static String start(String home, String mappings, Class<?> minecraft) {
        if (!running.compareAndSet(false, true)) return "already running";
        try {
            Path dir = Paths.get(home);
            CoreLog.open(dir.resolve("logs"));
            Logger log = CoreLog.forSource("core");
            log.info("Prism " + VERSION + " starting (" + mappings + " names, Java "
                    + System.getProperty("java.version") + ")");
            ClientThread.init(minecraft);
            // injected at the launcher/loading screen: wait for the game object to exist
            for (int i = 0; i < 600 && ClientThread.minecraft() == null; i++) Thread.sleep(100);
            if (ClientThread.minecraft() == null) log.warn("Minecraft instance not found yet; continuing");
            Tasks.start();
            bus = new Bus();
            GameImpl game = new GameImpl(mappings, minecraft.getClassLoader());
            hooks = new Hooks(minecraft.getClassLoader(), mappings, CoreLog.forSource("hooks"));
            Bus b = bus;
            GameHooks.register(hooks, new GameHooks.Poster() {
                public <E> E post(E event) { return b.post(event); }
            }, pt -> game.drawImpl().screenSize());
            log.info("hooks placed: " + hooks.install());
            plugins = new PluginManager(dir, Prism.class.getClassLoader(), bus, game);
            plugins.start();
            shutdownHook = new Thread(Prism::stop, "Prism Shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            log.info("started with " + plugins.plugins().size() + " plugin(s)");
            return null;
        } catch (Throwable t) {
            CoreLog.write("ERROR", "core", "start failed", t);
            running.set(false);
            return t.toString();
        }
    }

    /** Disables every plugin and stops Prism's threads. Called by the agent on unload and at game exit. */
    public static void stop() {
        if (!running.compareAndSet(true, false)) return;
        Logger log = CoreLog.forSource("core");
        log.info("stopping");
        try {
            if (bus != null) bus.post(new ShutdownEvent());
            if (plugins != null) plugins.stop();
        } catch (Throwable t) {
            log.error("stop failed", t);
        }
        try {
            if (hooks != null) hooks.uninstall();
        } catch (Throwable t) {
            log.error("removing hooks failed", t);
        }
        hooks = null;
        Tasks.stop();
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {   // JVM already shutting down
            }
        }
        log.info("stopped");
        CoreLog.close();
        bus = null;
        plugins = null;
    }

    public static boolean isRunning() { return running.get(); }
}
