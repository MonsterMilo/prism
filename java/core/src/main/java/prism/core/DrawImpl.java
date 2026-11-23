package prism.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import prism.api.Draw;
import prism.api.Logger;

/** Draw through the game's FontRenderer and Gui.drawRect, found by MCP or SRG names. */
final class DrawImpl implements Draw {
    private final String mappings;
    private final ClassLoader loader;
    private final Logger log = CoreLog.forSource("draw");

    private Field fontField, fontHeight;
    private Method drawText, textWidth, drawRect, scaledW, scaledH;
    private Constructor<?> scaled;
    private boolean resolved, broken;

    DrawImpl(String mappings, ClassLoader loader) {
        this.mappings = mappings;
        this.loader = loader;
    }

    private boolean srg() { return "srg".equals(mappings); }

    private synchronized boolean resolve() {
        if (resolved || broken) return resolved;
        try {
            if ("notch".equals(mappings)) throw new UnsupportedOperationException("vanilla names not supported yet");
            Class<?> mc = ClientThread.minecraftClass();
            fontField = mc.getDeclaredField(srg() ? "field_71466_p" : "fontRendererObj");
            fontField.setAccessible(true);
            Class<?> fr = fontField.getType();
            drawText = fr.getMethod(srg() ? "func_175063_a" : "drawStringWithShadow", String.class, float.class, float.class, int.class);
            textWidth = fr.getMethod(srg() ? "func_78256_a" : "getStringWidth", String.class);
            fontHeight = fr.getField(srg() ? "field_78288_b" : "FONT_HEIGHT");
            Class<?> gui = Class.forName("net.minecraft.client.gui.Gui", false, loader);
            drawRect = gui.getMethod(srg() ? "func_73734_a" : "drawRect", int.class, int.class, int.class, int.class, int.class);
            Class<?> sr = Class.forName("net.minecraft.client.gui.ScaledResolution", false, loader);
            scaled = sr.getConstructor(mc);
            scaledW = sr.getMethod(srg() ? "func_78326_a" : "getScaledWidth");
            scaledH = sr.getMethod(srg() ? "func_78328_b" : "getScaledHeight");
            resolved = true;
        } catch (Throwable t) {
            broken = true;
            log.error("drawing not available", t);
        }
        return resolved;
    }

    private Object font() throws IllegalAccessException {
        return fontField.get(ClientThread.minecraft());
    }

    @Override
    public int text(String text, float x, float y, int color) {
        if (!resolve()) return 0;
        try {
            return (Integer) drawText.invoke(font(), text, x, y, color);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int textWidth(String text) {
        if (!resolve()) return 0;
        try {
            return (Integer) textWidth.invoke(font(), text);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int fontHeight() {
        if (!resolve()) return 9;
        try {
            return fontHeight.getInt(font());
        } catch (Exception e) {
            return 9;
        }
    }

    @Override
    public void rect(int x1, int y1, int x2, int y2, int color) {
        if (!resolve()) return;
        try {
            drawRect.invoke(null, x1, y1, x2, y2, color);
        } catch (Exception ignored) {
        }
    }

    /** GUI-scaled screen size {width, height}. */
    int[] screenSize() {
        if (!resolve()) return new int[] {0, 0};
        try {
            Object sr = scaled.newInstance(ClientThread.minecraft());
            return new int[] {(Integer) scaledW.invoke(sr), (Integer) scaledH.invoke(sr)};
        } catch (Exception e) {
            return new int[] {0, 0};
        }
    }
}
