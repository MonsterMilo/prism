package net.minecraft.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/** Like Minecraft's: the last handler in the pipeline, named "packet_handler". */
public class NetworkManager extends SimpleChannelInboundHandler<Packet> {
    private final EnumPacketDirection direction;
    private Channel channel;

    public NetworkManager(EnumPacketDirection direction) { this.direction = direction; }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.channel = ctx.channel();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet p) {
        String text = p instanceof net.minecraft.network.play.server.S02PacketChat
                ? ((net.minecraft.network.play.server.S02PacketChat) p).getText() : "";
        System.out.println("GAME RECV " + direction + " " + p.getClass().getSimpleName() + " " + text);
    }

    public void sendPacket(Packet p) { channel.writeAndFlush(p); }

    public Channel channel() { return channel; }
}
