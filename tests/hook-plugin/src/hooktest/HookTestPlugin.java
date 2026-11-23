package hooktest;

import prism.api.Plugin;
import prism.api.PluginContext;
import prism.api.event.KeyEvent;
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
        ctx.scheduler().every(1000, () -> ctx.log().info("counts tick=" + ticks + " overlay=" + overlays + " world=" + worlds));
    }
}
