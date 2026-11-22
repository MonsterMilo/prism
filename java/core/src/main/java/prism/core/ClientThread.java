package prism.core;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import prism.api.Logger;

/**
 * Runs code on Minecraft's client thread through Minecraft#addScheduledTask, found by its
 * shape (instance method taking one Runnable) so it works with every naming scheme.
 */
final class ClientThread {
    private static final Logger log = CoreLog.forSource("core");
    private static Class<?> mcClass;
    private static Method getInstance, addTask;
    private static boolean warned;

    static void init(Class<?> minecraft) {
        mcClass = minecraft;
        for (Method m : minecraft.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                    && m.getReturnType() == minecraft) {
                getInstance = m;
            }
            if (!Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Runnable.class) {
                // addScheduledTask returns a ListenableFuture; prefer that one if there are several
                if (addTask == null || m.getReturnType().getName().endsWith("ListenableFuture")) addTask = m;
            }
        }
        if (getInstance != null) getInstance.setAccessible(true);
        if (addTask != null) addTask.setAccessible(true);
        log.info("client thread: " + (getInstance != null && addTask != null ? "ready" : "not found"));
    }

    static Object minecraft() {
        if (getInstance == null) return null;
        try {
            return getInstance.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    static Class<?> minecraftClass() { return mcClass; }

    static void run(Runnable task) {
        Object mc = minecraft();
        if (mc != null && addTask != null) {
            try {
                addTask.invoke(mc, task);
                return;
            } catch (Exception e) {
                log.error("addScheduledTask failed", e);
            }
        }
        if (!warned) {
            warned = true;
            log.warn("client thread not available, running tasks directly");
        }
        task.run();
    }
}
