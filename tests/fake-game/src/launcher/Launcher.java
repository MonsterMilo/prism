package launcher;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/** Loads the "game" through its own class loader on a "Client thread", like Lunar and Forge do. */
public class Launcher {
    public static void main(String[] args) throws Exception {
        ClassLoader game = new URLClassLoader(new URL[] {new File(args[0]).toURI().toURL(), new File(args[1]).toURI().toURL()}, null);
        Thread t = new Thread(() -> {
            try {
                game.loadClass("net.minecraft.client.Minecraft").getMethod("run").invoke(null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Client thread");
        t.setContextClassLoader(game);
        t.start();
        t.join();
    }
}
