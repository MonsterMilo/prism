package prism.api;

/** Read from {@code plugin.properties} inside the plugin jar. */
public final class PluginInfo {
    private final String id, name, version, main;

    public PluginInfo(String id, String name, String version, String main) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.main = main;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String version() { return version; }
    public String main() { return main; }

    @Override
    public String toString() { return name + " " + version; }
}
