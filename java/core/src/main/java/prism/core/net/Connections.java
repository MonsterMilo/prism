package prism.core.net;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import prism.api.Logger;
import prism.api.Network;
import prism.api.event.ConnectEvent;

/**
 * Puts a {@link PacketHandler} into every client connection: the one already open when Prism
 * starts, and each new one (through a hook on NetworkManager.channelActive). Removes them on stop.
 */
public final class Connections implements Network {
    public interface Poster {
        <E> E post(E event);
    }

    static final String NAME = "prism_packets";

    private final Poster bus;
    private final Logger log;
    private final Map<Channel, PacketHandler> handlers = new ConcurrentHashMap<>();
    private volatile ChannelHandlerContext current;
    private volatile PacketHandler currentHandler;
    private volatile boolean stopped;

    public Connections(Poster bus, Logger log) {
        this.bus = bus;
        this.log = log;
    }

    <E> E post(E event) { return bus.post(event); }

    /** From the hook: NetworkManager.channelActive(ctx) is starting. */
    public void onChannelActive(Object networkManager, Object ctx) {
        if (stopped || !(ctx instanceof ChannelHandlerContext)) return;
        if (!isClientSide(networkManager)) return;   // singleplayer also has the server's side
        attach(((ChannelHandlerContext) ctx).channel());
    }

    /** The connection that's open right now (Prism injected while in a game). */
    public void attachCurrent(Object minecraft) {
        try {
            Object netHandler = call(minecraft, "NetHandlerPlayClient");
            Object manager = netHandler == null ? null : call(netHandler, "NetworkManager");
            if (manager == null || !isClientSide(manager)) return;
            for (Field f : allFields(manager.getClass())) {
                if (Channel.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Channel ch = (Channel) f.get(manager);
                    if (ch != null && ch.isActive()) attach(ch);
                    return;
                }
            }
        } catch (Throwable t) {
            log.error("can't attach to the open connection", t);
        }
    }

    private void attach(Channel ch) {
        if (handlers.containsKey(ch) || ch.pipeline().get("packet_handler") == null) return;
        String address = format(ch.remoteAddress());
        PacketHandler h = new PacketHandler(this, address);
        try {
            ch.pipeline().addBefore("packet_handler", NAME, h);
        } catch (Throwable t) {
            log.error("can't add the packet handler", t);
            return;
        }
        handlers.put(ch, h);
        log.info("connected to " + address);
        bus.post(new ConnectEvent(address));
    }

    void added(PacketHandler h, ChannelHandlerContext ctx) {
        current = ctx;
        currentHandler = h;
    }

    void closed(PacketHandler h) {
        handlers.values().remove(h);
        if (currentHandler == h) {
            current = null;
            currentHandler = null;
        }
        log.info("disconnected from " + h.address);
    }

    /** Takes every handler out of its pipeline (waits up to a second each). */
    public void stop() {
        stopped = true;
        for (Map.Entry<Channel, PacketHandler> e : handlers.entrySet()) {
            Channel ch = e.getKey();
            PacketHandler h = e.getValue();
            h.removed = true;
            try {
                ch.eventLoop().submit(() -> {
                    if (ch.pipeline().get(NAME) != null) ch.pipeline().remove(NAME);
                }).await(1, TimeUnit.SECONDS);
            } catch (Throwable t) {
                log.warn("couldn't remove the packet handler: " + t);
            }
        }
        handlers.clear();
        current = null;
        currentHandler = null;
    }

    // ------------------------------------------------------------------ Network

    @Override
    public boolean connected() {
        ChannelHandlerContext c = current;
        return c != null && c.channel().isActive();
    }

    @Override
    public String serverAddress() {
        PacketHandler h = currentHandler;
        return h == null ? null : h.address;
    }

    @Override
    public void send(Object packet) {
        ChannelHandlerContext c = current;
        if (c == null) throw new IllegalStateException("not connected");
        c.writeAndFlush(packet);   // starts below our handler: no PacketSendEvent
    }

    @Override
    public void receive(Object packet) {
        ChannelHandlerContext c = current;
        if (c == null) throw new IllegalStateException("not connected");
        c.executor().execute(() -> c.fireChannelRead(packet));   // goes straight to the game
    }

    // ------------------------------------------------------------------ helpers

    /** The client's NetworkManager has direction CLIENTBOUND (it receives clientbound packets). */
    private static boolean isClientSide(Object manager) {
        try {
            for (Field f : allFields(manager.getClass())) {
                if (f.getType().isEnum() && f.getType().getSimpleName().equals("EnumPacketDirection")) {
                    f.setAccessible(true);
                    Object v = f.get(manager);
                    return v != null && ((Enum<?>) v).name().equals("CLIENTBOUND");
                }
            }
        } catch (Throwable ignored) {
        }
        return true;   // no direction field: assume it's the client's
    }

    /** Calls the no-argument method of `target` whose return type has this simple name. */
    private static Object call(Object target, String returnSimpleName) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().equals(returnSimpleName)) {
                    m.setAccessible(true);
                    return m.invoke(target);
                }
            }
        }
        return null;
    }

    private static java.util.List<Field> allFields(Class<?> c) {
        java.util.List<Field> out = new java.util.ArrayList<>();
        for (; c != null; c = c.getSuperclass()) java.util.Collections.addAll(out, c.getDeclaredFields());
        return out;
    }

    private static String format(SocketAddress a) {
        if (a instanceof InetSocketAddress) {
            InetSocketAddress i = (InetSocketAddress) a;
            return i.getHostString() + ":" + i.getPort();
        }
        return String.valueOf(a);
    }
}
