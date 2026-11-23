package net.minecraft.network.play.server;
public class S02PacketChat implements net.minecraft.network.Packet {
    private final String text;
    public S02PacketChat(String text) { this.text = text; }
    public String getText() { return text; }
}
