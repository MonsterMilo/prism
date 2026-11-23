package prism.api.event;

/** The game opened a connection to a server ("host:port"). Network thread. */
public final class ConnectEvent {
    private final String address;

    public ConnectEvent(String address) { this.address = address; }

    public String address() { return address; }
}
