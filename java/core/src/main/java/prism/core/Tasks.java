package prism.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import prism.api.Logger;
import prism.api.Scheduler;

/** One timer thread for everyone; the work itself always runs on the client thread. */
final class Tasks {
    private static ScheduledExecutorService timer;

    static synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Prism Timer");
            t.setDaemon(true);
            return t;
        });
    }

    static synchronized void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    /** A plugin's scheduler: everything it scheduled is cancelled by {@link #close()}. */
    static final class Owned implements Scheduler {
        private final Logger log;
        private final Set<ScheduledFuture<?>> futures = ConcurrentHashMap.newKeySet();
        private volatile boolean closed;

        Owned(Logger log) { this.log = log; }

        private Runnable guarded(Runnable task) {
            return () -> {
                if (closed) return;
                try {
                    task.run();
                } catch (Throwable t) {
                    log.error("task failed", t);
                }
            };
        }

        @Override
        public void runOnClientThread(Runnable task) {
            if (!closed) ClientThread.run(guarded(task));
        }

        @Override
        public Task later(long delayMs, Runnable task) {
            return track(timer.schedule(() -> runOnClientThread(task), delayMs, TimeUnit.MILLISECONDS));
        }

        @Override
        public Task every(long periodMs, Runnable task) {
            return track(timer.scheduleAtFixedRate(() -> runOnClientThread(task), periodMs, periodMs,
                    TimeUnit.MILLISECONDS));
        }

        private Task track(ScheduledFuture<?> f) {
            futures.add(f);
            return () -> {
                f.cancel(false);
                futures.remove(f);
            };
        }

        void close() {
            closed = true;
            for (ScheduledFuture<?> f : futures) f.cancel(false);
            futures.clear();
        }
    }
}
