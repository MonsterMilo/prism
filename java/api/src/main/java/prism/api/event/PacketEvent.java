package prism.api.event;

/** Base of the packet events. Runs on the network thread: use the scheduler for game state. */
public abstract class PacketEvent extends Cancellable {
    private Object packet;

    protected PacketEvent(Object packet) { this.packet = packet; }

    /** The game's packet object. */
    public Object packet() { return packet; }

    /** Class name without the package, e.g. "S02PacketChat" (Lunar and Forge use the same names). */
    public String name() { return packet.getClass().getSimpleName(); }

    /** Replaces the packet with another one (of any type). */
    public void replace(Object packet) {
        if (packet == null) throw new IllegalArgumentException("use cancel() to drop a packet");
        this.packet = packet;
    }
}
