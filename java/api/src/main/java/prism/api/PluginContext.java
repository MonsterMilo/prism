package prism.api;

import java.nio.file.Path;

/** Everything a plugin gets from Prism. One context per plugin, valid until it is disabled. */
public interface PluginContext {
    PluginInfo info();

    Logger log();

    /** Subscriptions made here are removed when the plugin is disabled. */
    EventBus events();

    /** Tasks scheduled here are cancelled when the plugin is disabled. */
    Scheduler scheduler();

    /** The game Prism is injected into. */
    Game game();

    /** A folder for this plugin's own files (created on first call). */
    Path dataFolder();
}
