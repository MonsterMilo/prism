package prism.api.event;

/** The connection to the server closed. Network thread. */
public final class DisconnectEvent {
    private final String address;

    public DisconnectEvent(String address) { this.address = address; }

    public String address() { return address; }
}
