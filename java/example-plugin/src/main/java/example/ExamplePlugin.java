package example;

import prism.api.Plugin;
import prism.api.PluginContext;
import prism.api.event.PluginEnableEvent;

/** Says hello in chat and logs when other plugins load. */
public class ExamplePlugin implements Plugin {
    @Override
    public void onEnable(PluginContext ctx) {
        ctx.log().info("hello from " + ctx.info());
        ctx.game().chat("§d[Example] §fHello!");
        ctx.events().on(PluginEnableEvent.class, e -> ctx.log().info("saw " + e.plugin() + " load"));
    }
}
