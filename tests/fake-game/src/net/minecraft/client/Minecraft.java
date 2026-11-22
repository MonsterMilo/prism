package net.minecraft.client;

import java.util.ArrayDeque;
import java.util.Queue;
import net.minecraft.client.gui.GuiIngame;

/** Stand-in with the same names Lunar uses (MCP), for testing the agent and core. */
public class Minecraft {
    private static Minecraft theMinecraft;
    public GuiIngame ingameGUI;
    private final Queue<Runnable> scheduledTasks = new ArrayDeque<>();

    public static Minecraft getMinecraft() { return theMinecraft; }

    public Object addScheduledTask(Runnable r) {
        synchronized (scheduledTasks) { scheduledTasks.add(r); }
        return null;
    }

    public static void run() throws Exception {
        Thread.sleep(1500);           // "loading"
        theMinecraft = new Minecraft();
        theMinecraft.ingameGUI = new GuiIngame();
        System.out.println("GAME: started");
        while (true) {                // game loop, 50 fps
            synchronized (theMinecraft.scheduledTasks) {
                Runnable r;
                while ((r = theMinecraft.scheduledTasks.poll()) != null) r.run();
            }
            Thread.sleep(20);
        }
    }
}
