package example;

import prism.api.Draw;
import prism.api.Plugin;
import prism.api.PluginContext;
import prism.api.event.KeyEvent;
import prism.api.event.RenderOverlayEvent;
import prism.api.event.TickEvent;

/** Says hello, counts ticks, draws a small label on the HUD and logs key presses. */
public class ExamplePlugin implements Plugin {
    private int ticks;

    @Override
    public void onEnable(PluginContext ctx) {
        ctx.game().chat("§d[Example] §fHello!");
        ctx.events().on(TickEvent.class, e -> ticks++);
        ctx.events().on(RenderOverlayEvent.class, e -> {
            Draw d = ctx.game().draw();
            String label = "§dPrism §7" + (ticks / 20) + "s";
            d.text(label, e.width() - d.textWidth(label) - 4, e.height() - d.fontHeight() - 4, 0xFFFFFFFF);
        });
        ctx.events().on(KeyEvent.class, e -> {
            if (e.pressed()) ctx.log().info("key " + e.key());
        });
    }
}
