package prism.core;

/**
 * Calls into the native agent (prism_agent.dll). The agent registers these methods
 * before {@link Prism#start} runs. Without the agent (tests), they are no-ops.
 */
public final class Native {
    private Native() {}

    static native void log0(String line);

    static native void unload0();

    static native boolean redefineClass0(Class<?> target, byte[] classFile);

    private static volatile boolean linked = true;

    static void log(String line) {
        if (!linked) return;
        try {
            log0(line);
        } catch (UnsatisfiedLinkError e) {
            linked = false;
        }
    }

    /** Asks the agent to unload Prism from the game. Prism.stop() is called by the agent. */
    public static void requestUnload() {
        try {
            unload0();
        } catch (UnsatisfiedLinkError e) {
            Prism.stop();
        }
    }

    /** Replaces a loaded class's bytecode (JVMTI RedefineClasses). For the hook system. */
    public static boolean redefineClass(Class<?> target, byte[] classFile) {
        try {
            return redefineClass0(target, classFile);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }
}
