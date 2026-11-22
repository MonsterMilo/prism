package prism.api;

/** Runs code later or on the game's main (client) thread. Game state must only be touched there. */
public interface Scheduler {
    /** Runs on the client thread, as soon as the game gets to it. */
    void runOnClientThread(Runnable task);

    /** Runs once on the client thread after {@code delayMs}. */
    Task later(long delayMs, Runnable task);

    /** Runs on the client thread every {@code periodMs}, starting after one period. */
    Task every(long periodMs, Runnable task);

    interface Task {
        void cancel();
    }
}
