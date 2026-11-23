package net.minecraft.network.play.client;
public class C01PacketChatMessage implements net.minecraft.network.Packet {
    private final String message;
    public C01PacketChatMessage(String message) { this.message = message; }
    public String getMessage() { return message; }
}
