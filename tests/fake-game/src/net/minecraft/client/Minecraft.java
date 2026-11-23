package net.minecraft.client;

import java.util.ArrayDeque;
import java.util.Queue;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.settings.KeyBinding;

/** Stand-in with the same names Lunar uses (MCP), for testing the agent and core. */
public class Minecraft {
    private static Minecraft theMinecraft;
    public GuiIngame ingameGUI;
    public FontRenderer fontRendererObj = new FontRenderer();
    public EntityRenderer entityRenderer = new EntityRenderer();
    public static int ticks;
    private static int frame;
    private final Queue<Runnable> scheduledTasks = new ArrayDeque<>();

    public static Minecraft getMinecraft() { return theMinecraft; }

    public void runTick() { ticks++; }

    public Object addScheduledTask(Runnable r) {
        synchronized (scheduledTasks) { scheduledTasks.add(r); }
        return null;
    }

    public static void run() throws Exception {
        Thread.sleep(1500);           // "loading"
        theMinecraft = new Minecraft();
        theMinecraft.ingameGUI = new net.minecraftforge.client.GuiIngameForge();
        System.out.println("GAME: started");
        while (true) {                // game loop, 50 fps, a tick every other frame
            synchronized (theMinecraft.scheduledTasks) {
                Runnable r;
                while ((r = theMinecraft.scheduledTasks.poll()) != null) r.run();
            }
            if (frame++ % 2 == 0) theMinecraft.runTick();
            theMinecraft.entityRenderer.renderWorldPass(2, 0.5f, System.nanoTime());
            theMinecraft.ingameGUI.renderGameOverlay(0.5f);
            if (new java.io.File(System.getProperty("fake.keys", "/nonexistent")).exists()) {
                new java.io.File(System.getProperty("fake.keys")).delete();
                KeyBinding.setKeyBindState(30, true);    // A
                KeyBinding.setKeyBindState(31, true);    // S
                KeyBinding.setKeyBindState(-100, true);  // left mouse
            }
            Thread.sleep(20);
        }
    }
}
