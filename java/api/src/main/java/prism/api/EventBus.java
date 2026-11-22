package prism.api;

import java.util.function.Consumer;

/**
 * Typed events. A handler for a class also receives its subclasses.
 * Handlers run on the thread that posted the event; one failing handler never stops the others.
 */
public interface EventBus {
    <E> Subscription on(Class<E> type, Consumer<? super E> handler);

    /** Posts an event to every plugin. Returns the event so callers can read results back. */
    <E> E post(E event);
}
