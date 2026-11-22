package prism.api;

/**
 * Entry point of a Prism plugin. The class named by {@code main} in the plugin's
 * {@code plugin.properties} must implement this and have a public no-argument constructor.
 */
public interface Plugin {
    /** Called once when the plugin is loaded (also after every hot reload). */
    void onEnable(PluginContext ctx) throws Exception;

    /** Called before the plugin is unloaded. Subscriptions and tasks are cleaned up automatically. */
    default void onDisable() throws Exception {}
}
