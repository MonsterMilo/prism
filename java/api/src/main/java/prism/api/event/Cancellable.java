package prism.api.event;

/** An event a handler can cancel, stopping what the game was about to do. */
public abstract class Cancellable {
    private boolean cancelled;

    public void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }
}
