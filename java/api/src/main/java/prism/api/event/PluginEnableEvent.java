package prism.api.event;

import prism.api.PluginInfo;

/** Posted after a plugin was enabled. */
public final class PluginEnableEvent {
    private final PluginInfo plugin;

    public PluginEnableEvent(PluginInfo plugin) { this.plugin = plugin; }

    public PluginInfo plugin() { return plugin; }
}
