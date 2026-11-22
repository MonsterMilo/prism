package prism.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import prism.api.Game;
import prism.api.Logger;

final class GameImpl implements Game {
    private final String mappings;
    private final ClassLoader loader;
    private final Logger log = CoreLog.forSource("game");

    // resolved on first chat()
    private Field ingameGui;
    private Method getChat, printChat;
    private Constructor<?> textComponent;
    private boolean resolved, broken;

    GameImpl(String mappings, ClassLoader loader) {
        this.mappings = mappings;
        this.loader = loader;
    }

    @Override public String mappings() { return mappings; }

    @Override public ClassLoader classLoader() { return loader; }

    @Override
    public void chat(String message) {
        ClientThread.run(() -> printNow(message));
    }

    private void printNow(String message) {
        if (!resolve()) {
            log.info("(chat) " + message);
            return;
        }
        try {
            Object mc = ClientThread.minecraft();
            Object gui = mc == null ? null : ingameGui.get(mc);
            if (gui == null) {   // still loading: no HUD yet
                log.info("(chat, no HUD yet) " + message);
                return;
            }
            printChat.invoke(getChat.invoke(gui), textComponent.newInstance(message));
        } catch (Exception e) {
            log.error("chat failed", e);
        }
    }

    /** Minecraft.ingameGUI -> GuiIngame.getChatGUI() -> GuiNewChat.printChatMessage(new ChatComponentText(s)) */
    private synchronized boolean resolve() {
        if (resolved || broken) return resolved;
        try {
            Class<?> mc = ClientThread.minecraftClass();
            String[] n = names();
            Class<?> chatText = Class.forName(n[3], false, loader);
            Class<?> component = Class.forName(n[4], false, loader);
            ingameGui = field(mc, n[0]);
            getChat = method(ingameGui.getType(), n[1]);
            printChat = getChat.getReturnType().getMethod(n[2], component);
            textComponent = chatText.getConstructor(String.class);
            resolved = true;
        } catch (Throwable t) {
            broken = true;
            log.error("chat not available for " + mappings + " names", t);
        }
        return resolved;
    }

    private String[] names() {
        switch (mappings) {
            case "srg":
                return new String[] {"field_71456_v", "func_146158_b", "func_146227_a",
                        "net.minecraft.util.ChatComponentText", "net.minecraft.util.IChatComponent"};
            case "notch":   // vanilla 1.8.9
                return new String[] {"q", "d", "a", "fa", "eu"};
            default:        // mcp (Lunar)
                return new String[] {"ingameGUI", "getChatGUI", "printChatMessage",
                        "net.minecraft.util.ChatComponentText", "net.minecraft.util.IChatComponent"};
        }
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Method method(Class<?> c, String name) throws NoSuchMethodException {
        Method m = c.getDeclaredMethod(name);
        m.setAccessible(true);
        return m;
    }
}
