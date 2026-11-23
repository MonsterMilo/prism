package prism.core.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import prism.api.event.DisconnectEvent;
import prism.api.event.PacketReceiveEvent;
import prism.api.event.PacketSendEvent;

/**
 * Sits in the game's Netty pipeline just before Minecraft's "packet_handler", where packets are
 * already decoded objects in both directions.
 */
final class PacketHandler extends ChannelDuplexHandler {
    private final Connections owner;
    final String address;
    volatile boolean removed;

    PacketHandler(Connections owner, String address) {
        this.owner = owner;
        this.address = address;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        owner.added(this, ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (removed || msg instanceof ByteBuf) {
            ctx.fireChannelRead(msg);
            return;
        }
        PacketReceiveEvent e = owner.post(new PacketReceiveEvent(msg));
        if (!e.isCancelled()) ctx.fireChannelRead(e.packet());
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (removed || msg instanceof ByteBuf) {
            ctx.write(msg, promise);
            return;
        }
        PacketSendEvent e = owner.post(new PacketSendEvent(msg));
        if (e.isCancelled()) promise.setSuccess();
        else ctx.write(e.packet(), promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!removed) {
            owner.closed(this);
            owner.post(new DisconnectEvent(address));
        }
        ctx.fireChannelInactive();
    }
}
