package prism.api.event;

import prism.api.PluginInfo;

/** Posted before a plugin is disabled. */
public final class PluginDisableEvent {
    private final PluginInfo plugin;

    public PluginDisableEvent(PluginInfo plugin) { this.plugin = plugin; }

    public PluginInfo plugin() { return plugin; }
}
