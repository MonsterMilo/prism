package prism.api.event;

/** A packet from the server, before the game handles it. Cancel to hide it from the game. */
public final class PacketReceiveEvent extends PacketEvent {
    public PacketReceiveEvent(Object packet) { super(packet); }
}
