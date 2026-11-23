package prism.core.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import prism.api.Logger;
import prism.core.Native;

/**
 * Places hooks into game classes when Prism starts and takes them out again when it stops.
 * Hooks need JVMTI retransformation (the agent turns it on). Each class is patched in one go,
 * starting from the bytes the game is running, so other patches (Lunar's own) are kept.
 */
public final class Hooks {
    private static final Predicate<Object[]> NOOP = args -> false;

    private final ClassLoader gameLoader;
    private final String mappings;
    private final Logger log;
    private final List<Hook> hooks = new ArrayList<>();
    private final List<Class<?>> patched = new ArrayList<>();

    public Hooks(ClassLoader gameLoader, String mappings, Logger log) {
        this.gameLoader = gameLoader;
        this.mappings = mappings;
        this.log = log;
    }

    public void add(Hook h) { hooks.add(h); }

    /** Patches every class that has hooks. Returns how many hooks were placed. */
    public int install() {
        if ("notch".equals(mappings)) {
            log.warn("hooks: vanilla (notch) names aren't supported yet");
            return 0;
        }
        Map<String, List<Hook>> byClass = new LinkedHashMap<>();
        for (Hook h : hooks) {
            System.getProperties().put(h.key(), h.handler);   // before any patched code can run
            byClass.computeIfAbsent(h.owner, k -> new ArrayList<>()).add(h);
        }
        int placed = 0;
        for (Map.Entry<String, List<Hook>> e : byClass.entrySet()) {
            List<Hook> list = e.getValue();
            Class<?> cls;
            try {
                cls = Class.forName(e.getKey().replace('/', '.'), false, gameLoader);
            } catch (Throwable t) {
                for (Hook h : list) {
                    if (h.optional) log.info("hook " + h.id + ": skipped (" + e.getKey() + " not on this client)");
                    else log.warn("hook " + h.id + ": class " + e.getKey() + " not found");
                }
                continue;
            }
            Patcher.Result[] result = new Patcher.Result[1];
            Throwable[] failure = new Throwable[1];
            int code = Native.retransform(cls, bytes -> {
                try {
                    result[0] = Patcher.patch(bytes, list, mappings);
                    return result[0].bytes;
                } catch (Throwable t) {
                    failure[0] = t;
                    return null;
                }
            });
            if (failure[0] != null) log.error("hooks: patching " + e.getKey() + " failed", failure[0]);
            if (code != 0 || result[0] == null) {
                for (Hook h : list) log.warn("hook " + h.id + ": not placed (JVMTI " + code + ")");
                continue;
            }
            if (result[0].bytes != null) patched.add(cls);
            for (int i = 0; i < list.size(); i++) {
                String problem = result[0].problems[i];
                if (problem == null) {
                    placed++;
                    log.info("hook " + list.get(i).id + ": ok");
                } else {
                    log.warn("hook " + list.get(i).id + ": " + problem);
                }
            }
        }
        return placed;
    }

    /** Puts every patched class back the way the game had it. */
    public void uninstall() {
        boolean allRestored = true;
        for (Class<?> cls : patched) {
            int code = Native.retransform(cls, null);
            if (code != 0) {
                allRestored = false;
                log.warn("hooks: couldn't restore " + cls.getName() + " (JVMTI " + code + ")");
            }
        }
        patched.clear();
        for (Hook h : hooks) {
            // a class that couldn't be restored still calls in: leave a no-op there, never null
            if (allRestored) System.getProperties().remove(h.key());
            else System.getProperties().put(h.key(), NOOP);
        }
        log.info("hooks removed");
    }
}
