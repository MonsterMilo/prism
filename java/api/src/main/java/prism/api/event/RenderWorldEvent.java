package prism.api.event;

/** After the world was drawn, before the player's hand, every frame (camera-space GL state). */
public final class RenderWorldEvent {
    private final float partialTicks;

    public RenderWorldEvent(float partialTicks) { this.partialTicks = partialTicks; }

    public float partialTicks() { return partialTicks; }
}
