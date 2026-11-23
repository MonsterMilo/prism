package net.minecraft.client.gui;
public class FontRenderer {
    public int FONT_HEIGHT = 9;
    public static int draws;
    public int drawStringWithShadow(String text, float x, float y, int color) {
        if (draws++ == 0) System.out.println("DRAW text '" + text + "' at " + (int) x + "," + (int) y);
        return text.length() * 6;
    }
    public int getStringWidth(String text) { return text.length() * 6; }
}
