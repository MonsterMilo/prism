package prism.core;

/**
 * Calls into the native agent (prism_agent.dll). The agent registers these methods
 * before {@link Prism#start} runs. Without the agent (tests), they are no-ops.
 */
public final class Native {
    private Native() {}

    static native void log0(String line);

    static native void unload0();

    static native int retransform0(Class<?> target, java.util.function.Function<byte[], byte[]> transformer);

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

    /**
     * Retransforms a loaded class (JVMTI): its current bytes go through {@code transformer}, whose
     * result becomes the class. {@code null} restores the class as the game loaded it.
     * Returns 0, a JVMTI error code, or -1 when the agent can't do it.
     */
    public static int retransform(Class<?> target, java.util.function.Function<byte[], byte[]> transformer) {
        try {
            return retransform0(target, transformer);
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }
}
