package net.minecraft.client;

import java.util.ArrayDeque;
import java.util.Queue;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.settings.KeyBinding;

/** Stand-in with the same names Lunar uses (MCP), for testing the agent and core. */
public class Minecraft {
    private static Minecraft theMinecraft;
    public GuiIngame ingameGUI;
    public FontRenderer fontRendererObj = new FontRenderer();
    public EntityRenderer entityRenderer = new EntityRenderer();
    public static int ticks;
    private static int frame;
    private net.minecraft.client.network.NetHandlerPlayClient netHandler;
    private io.netty.channel.embedded.EmbeddedChannel channel, serverChannel;

    public net.minecraft.client.network.NetHandlerPlayClient getNetHandler() { return netHandler; }

    private static boolean flag(String name) {
        java.io.File f = new java.io.File(System.getProperty("fake.net", "/nonexistent"), name);
        return f.exists() && f.delete();
    }

    private static io.netty.channel.embedded.EmbeddedChannel connect(final net.minecraft.network.NetworkManager nm) {
        return new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInitializer<io.netty.channel.Channel>() {
            protected void initChannel(io.netty.channel.Channel c) { c.pipeline().addLast("packet_handler", nm); }
        });
    }

    /** Stand-in for the network: connect, traffic and disconnect, driven by flag files from the test. */
    private void network() {
        if (flag("connect")) {
            net.minecraft.network.NetworkManager nm = new net.minecraft.network.NetworkManager(net.minecraft.network.EnumPacketDirection.CLIENTBOUND);
            channel = connect(nm);
            netHandler = new net.minecraft.client.network.NetHandlerPlayClient(nm);
            // singleplayer-style server side in the same game: Prism must leave it alone
            serverChannel = connect(new net.minecraft.network.NetworkManager(net.minecraft.network.EnumPacketDirection.SERVERBOUND));
            System.out.println("GAME connected");
        }
        if (channel == null) return;
        if (flag("traffic")) {
            channel.writeInbound(new net.minecraft.network.play.server.S02PacketChat("hello"));
            channel.writeInbound(new net.minecraft.network.play.server.S02PacketChat("secret"));
            netHandler.getNetworkManager().sendPacket(new net.minecraft.network.play.client.C01PacketChatMessage("/hub"));
            netHandler.getNetworkManager().sendPacket(new net.minecraft.network.play.client.C01PacketChatMessage("/blocked"));
            serverChannel.writeInbound(new net.minecraft.network.play.server.S02PacketChat("server side"));
        }
        if (flag("close")) {
            channel.close();
            serverChannel.close();
        }
        channel.runPendingTasks();
        Object out;
        while ((out = channel.readOutbound()) != null) {
            String msg = out instanceof net.minecraft.network.play.client.C01PacketChatMessage
                    ? ((net.minecraft.network.play.client.C01PacketChatMessage) out).getMessage() : "";
            System.out.println("SERVER GOT " + out.getClass().getSimpleName() + " " + msg);
        }
        if (!channel.isActive()) channel = null;
    }
    private final Queue<Runnable> scheduledTasks = new ArrayDeque<>();

    public static Minecraft getMinecraft() { return theMinecraft; }

    public void runTick() { ticks++; }

    public Object addScheduledTask(Runnable r) {
        synchronized (scheduledTasks) { scheduledTasks.add(r); }
        return null;
    }

    public static void run() throws Exception {
        Thread.sleep(1500);           // "loading"
        theMinecraft = new Minecraft();
        theMinecraft.ingameGUI = new net.minecraftforge.client.GuiIngameForge();
        System.out.println("GAME: started");
        while (true) {                // game loop, 50 fps, a tick every other frame
            synchronized (theMinecraft.scheduledTasks) {
                Runnable r;
                while ((r = theMinecraft.scheduledTasks.poll()) != null) r.run();
            }
            if (frame++ % 2 == 0) theMinecraft.runTick();
            theMinecraft.network();
            theMinecraft.entityRenderer.renderWorldPass(2, 0.5f, System.nanoTime());
            theMinecraft.ingameGUI.renderGameOverlay(0.5f);
            if (new java.io.File(System.getProperty("fake.keys", "/nonexistent")).exists()) {
                new java.io.File(System.getProperty("fake.keys")).delete();
                KeyBinding.setKeyBindState(30, true);    // A
                KeyBinding.setKeyBindState(31, true);    // S
                KeyBinding.setKeyBindState(-100, true);  // left mouse
            }
            Thread.sleep(20);
        }
    }
}
