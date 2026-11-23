package prism.api.event;

/** Start of every client tick (20 per second), on the client thread. */
public final class TickEvent {
    public static final TickEvent INSTANCE = new TickEvent();

    private TickEvent() {}
}
