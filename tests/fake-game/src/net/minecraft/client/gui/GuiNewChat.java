package net.minecraft.client.gui;
import net.minecraft.util.IChatComponent;
public class GuiNewChat {
    public void printChatMessage(IChatComponent c) {
        System.out.println("CHAT[" + Thread.currentThread().getName() + "]: " + c.getUnformattedText());
    }
}
