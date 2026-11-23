package prism.api;

/** The running Minecraft client. */
public interface Game {
    /**
     * Which class names this client uses: {@code "mcp"} (Lunar), {@code "srg"} (Forge)
     * or {@code "notch"} (vanilla and vanilla-based clients).
     */
    String mappings();

    /** The game's class loader, for plugins that work with Minecraft classes directly. */
    ClassLoader classLoader();

    /**
     * Shows a message in the player's own chat (only they see it, nothing is sent to the server).
     * Supports {@code §} colour codes. Safe to call from any thread.
     */
    void chat(String message);

    /** 2D drawing for RenderOverlayEvent handlers. */
    Draw draw();
}
