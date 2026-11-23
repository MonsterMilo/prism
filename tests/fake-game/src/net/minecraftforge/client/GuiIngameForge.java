package net.minecraftforge.client;
import net.minecraft.client.gui.GuiIngame;
/** Like Forge: overrides the HUD without calling the vanilla method. */
public class GuiIngameForge extends GuiIngame {
    @Override
    public void renderGameOverlay(float partialTicks) {
        if (partialTicks > 100) return;
    }
}
