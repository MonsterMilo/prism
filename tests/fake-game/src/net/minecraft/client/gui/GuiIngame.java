package net.minecraft.client.gui;
public class GuiIngame {
    private final GuiNewChat chat = new GuiNewChat();
    public GuiNewChat getChatGUI() { return chat; }
    public void renderGameOverlay(float partialTicks) {
        if (partialTicks < 0) return;   // a second return, to check TAIL hooks every exit
    }
}
