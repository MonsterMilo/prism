package prism.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import prism.api.EventBus;
import prism.api.Logger;
import prism.api.Subscription;

/** The one event bus. Plugins get an {@link Owned} view so their handlers can be removed together. */
final class Bus {
    private static final class Handler {
        final Class<?> type;
        final Consumer<Object> fn;
        final Owned owner;

        @SuppressWarnings("unchecked")
        Handler(Class<?> type, Consumer<?> fn, Owned owner) {
            this.type = type;
            this.fn = (Consumer<Object>) fn;
            this.owner = owner;
        }
    }

    private final List<Handler> handlers = new CopyOnWriteArrayList<>();
    private final Logger log = CoreLog.forSource("events");

    <E> E post(E event) {
        for (Handler h : handlers) {
            if (!h.type.isInstance(event)) continue;
            try {
                h.fn.accept(event);
            } catch (Throwable t) {
                log.error((h.owner == null ? "core" : h.owner.name) + " failed handling "
                        + event.getClass().getSimpleName(), t);
            }
        }
        return event;
    }

    Owned owned(String name) { return new Owned(name); }

    /** One plugin's view of the bus. */
    final class Owned implements EventBus {
        final String name;
        private volatile boolean closed;

        Owned(String name) { this.name = name; }

        @Override
        public <E> Subscription on(Class<E> type, Consumer<? super E> handler) {
            if (closed) throw new IllegalStateException("plugin is disabled");
            Handler h = new Handler(type, handler, this);
            handlers.add(h);
            return () -> handlers.remove(h);
        }

        @Override
        public <E> E post(E event) { return Bus.this.post(event); }

        void close() {
            closed = true;
            handlers.removeIf(h -> h.owner == this);
        }
    }
}
