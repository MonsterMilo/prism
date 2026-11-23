package prism.api.event;

/**
 * After the game drew its HUD, every frame. Draw with {@code ctx.game().draw()}.
 * Width and height are in GUI-scaled pixels.
 */
public final class RenderOverlayEvent {
    private final float partialTicks;
    private final int width, height;

    public RenderOverlayEvent(float partialTicks, int width, int height) {
        this.partialTicks = partialTicks;
        this.width = width;
        this.height = height;
    }

    public float partialTicks() { return partialTicks; }
    public int width() { return width; }
    public int height() { return height; }
}
