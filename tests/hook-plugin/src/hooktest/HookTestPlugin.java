package hooktest;

import prism.api.Plugin;
import prism.api.PluginContext;
import prism.api.event.ConnectEvent;
import prism.api.event.DisconnectEvent;
import prism.api.event.KeyEvent;
import prism.api.event.PacketReceiveEvent;
import prism.api.event.PacketSendEvent;
import prism.api.event.MouseButtonEvent;
import prism.api.event.RenderOverlayEvent;
import prism.api.event.RenderWorldEvent;
import prism.api.event.TickEvent;

/** Test plugin: counts every hook event, blocks key A, reports once a second. */
public class HookTestPlugin implements Plugin {
    int ticks, overlays, worlds;

    @Override
    public void onEnable(PluginContext ctx) {
        ctx.events().on(TickEvent.class, e -> ticks++);
        ctx.events().on(RenderOverlayEvent.class, e -> {
            overlays++;
            ctx.game().draw().rect(0, 0, 10, 10, 0x80000000);
            if (overlays == 1) ctx.log().info("overlay size " + e.width() + "x" + e.height() + " pt " + e.partialTicks());
        });
        ctx.events().on(RenderWorldEvent.class, e -> worlds++);
        ctx.events().on(KeyEvent.class, e -> {
            ctx.log().info("key event " + e.key() + " " + e.pressed());
            if (e.key() == 30) e.cancel();
        });
        ctx.events().on(MouseButtonEvent.class, e -> ctx.log().info("mouse event " + e.button() + " " + e.pressed()));
        ClassLoader game = ctx.game().classLoader();
        ctx.log().info("enabled, connected=" + ctx.network().connected() + " server=" + ctx.network().serverAddress());
        ctx.events().on(ConnectEvent.class, e -> {
            ctx.log().info("connect event " + e.address() + " connected=" + ctx.network().connected());
            try {
                ctx.network().send(game.loadClass("net.minecraft.network.play.client.C01PacketChatMessage")
                        .getConstructor(String.class).newInstance("hello from plugin"));
                ctx.network().receive(game.loadClass("net.minecraft.network.play.server.S02PacketChat")
                        .getConstructor(String.class).newInstance("injected"));
            } catch (Exception ex) {
                ctx.log().error("send failed", ex);
            }
        });
        ctx.events().on(DisconnectEvent.class, e -> ctx.log().info("disconnect event " + e.address()));
        ctx.events().on(PacketReceiveEvent.class, e -> {
            String text = text(e.packet(), "getText");
            ctx.log().info("recv " + e.name() + " " + text);
            if (text.equals("secret")) e.cancel();
            if (text.equals("hello")) {
                try {
                    e.replace(e.packet().getClass().getConstructor(String.class).newInstance("replaced"));
                } catch (Exception ex) {
                    ctx.log().error("replace failed", ex);
                }
            }
        });
        ctx.events().on(PacketSendEvent.class, e -> {
            String msg = text(e.packet(), "getMessage");
            ctx.log().info("send " + e.name() + " " + msg);
            if (msg.equals("/blocked")) e.cancel();
        });
        ctx.scheduler().every(1000, () -> ctx.log().info("counts tick=" + ticks + " overlay=" + overlays + " world=" + worlds));
    }

    private static String text(Object packet, String getter) {
        try {
            return String.valueOf(packet.getClass().getMethod(getter).invoke(packet));
        } catch (Exception e) {
            return "";
        }
    }
}
