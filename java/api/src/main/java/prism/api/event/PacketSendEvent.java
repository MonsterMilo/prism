package prism.api.event;

/** A packet the game is sending to the server. Cancel to stop it. */
public final class PacketSendEvent extends PacketEvent {
    public PacketSendEvent(Object packet) { super(packet); }
}
