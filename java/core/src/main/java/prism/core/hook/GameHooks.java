package prism.core.hook;

import java.util.function.Function;

import prism.api.event.KeyEvent;
import prism.api.event.MouseButtonEvent;
import prism.api.event.RenderOverlayEvent;
import prism.api.event.RenderWorldEvent;
import prism.api.event.TickEvent;

/** The hooks behind Prism's events. Names: MCP (Lunar) and SRG (Forge). */
public final class GameHooks {
    private GameHooks() {}

    public interface Poster {
        <E> E post(E event);
    }

    public static void register(Hooks hooks, Poster bus, Function<Float, int[]> screenSize,
                                java.util.function.BiConsumer<Object, Object> channelActive) {
        // every new connection: NetworkManager.channelActive (a Netty method, same name everywhere)
        hooks.add(Hook.head("connect", "net/minecraft/network/NetworkManager", "channelActive", "channelActive",
                "(Lio/netty/channel/ChannelHandlerContext;)V", new int[] {0, 1}, false, args -> {
                    channelActive.accept(args[0], args[1]);
                    return false;
                }));

        hooks.add(Hook.head("tick", "net/minecraft/client/Minecraft", "runTick", "func_71407_l", "()V",
                new int[0], false, args -> {
                    bus.post(TickEvent.INSTANCE);
                    return false;
                }));

        java.util.function.Predicate<Object[]> overlay = args -> {
            float pt = (Float) args[0];
            int[] size = screenSize.apply(pt);
            bus.post(new RenderOverlayEvent(pt, size[0], size[1]));
            return false;
        };
        hooks.add(Hook.tail("overlay", "net/minecraft/client/gui/GuiIngame", "renderGameOverlay", "func_175180_a", "(F)V",
                new int[] {1}, overlay));
        // Forge (and Lunar with Forge mods) replaces the HUD with GuiIngameForge, which overrides
        // renderGameOverlay without calling the vanilla one
        hooks.add(Hook.tail("overlay-forge", "net/minecraftforge/client/GuiIngameForge", "renderGameOverlay", "func_175180_a", "(F)V",
                new int[] {1}, overlay).optional());

        hooks.add(Hook.beforeCall("world", "net/minecraft/client/renderer/EntityRenderer",
                "renderWorldPass", "func_175068_a", "(IFJ)V",
                "net/minecraft/client/renderer/EntityRenderer", "renderHand", "func_78476_b", "(FI)V",
                new int[] {2}, args -> {
                    bus.post(new RenderWorldEvent((Float) args[0]));
                    return false;
                }));

        // all keybind input while playing goes through here; mouse buttons come as button - 100
        hooks.add(Hook.head("input", "net/minecraft/client/settings/KeyBinding", "setKeyBindState", "func_74510_a", "(IZ)V",
                new int[] {0, 1}, true, args -> {
                    int code = (Integer) args[0];
                    boolean pressed = (Boolean) args[1];
                    if (code < 0) return bus.post(new MouseButtonEvent(code + 100, pressed)).isCancelled();
                    return bus.post(new KeyEvent(code, pressed)).isCancelled();
                }));
    }
}
