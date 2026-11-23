package prism.api;

/**
 * The connection to the server. Packets are the game's own packet objects
 * (e.g. {@code net.minecraft.network.play.client.C01PacketChatMessage}); build them with the
 * game's class loader ({@link Game#classLoader()}).
 */
public interface Network {
    boolean connected();

    /** "host:port" of the current server, or null. */
    String serverAddress();

    /** Sends a packet to the server. Plugins don't see it as a PacketSendEvent. */
    void send(Object packet);

    /** Hands a packet to the game as if the server sent it. Plugins don't see it as a PacketReceiveEvent. */
    void receive(Object packet);
}
