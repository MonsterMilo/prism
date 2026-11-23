# Prism

An open-source plugin platform for Minecraft 1.8.9. Prism loads into a running game
(Lunar Client first; Forge and vanilla next) and runs Java plugins inside it.

> Status: early. Injection, the Java core, plugin loading with hot reload, the desktop app and
> game events (ticks, HUD/world rendering, keys and mouse) and packets work. Features come next.

## Using it

1. Download `Prism.exe` from the latest build.
2. Start Lunar Client 1.8.9 and run `Prism.exe`.
3. Prism injects on its own (or press **Inject**). You'll see `[Prism] Loaded` in chat.
4. Drop plugin `.jar` files into the plugins folder (**Open folder** in the app).
   Plugins reload by themselves when a jar changes.

Everything lives in `%APPDATA%\Prism`: `plugins\`, `data\`, `logs\prism.log`.

## Writing a plugin

Compile against `prism-api.jar` (Java 8). Add a `plugin.properties` to the jar:

```properties
id=example
name=Example
version=1.0.0
main=example.ExamplePlugin
```

```java
public class ExamplePlugin implements Plugin {
    @Override
    public void onEnable(PluginContext ctx) {
        ctx.game().chat("§dHello!");
        ctx.events().on(RenderOverlayEvent.class, e -> ctx.game().draw().text("§dHi", 4, 4, -1));
        ctx.events().on(KeyEvent.class, e -> { if (e.key() == 30) e.cancel(); });   // block A
    }
}
```

Events: `TickEvent`, `RenderOverlayEvent`, `RenderWorldEvent`, `KeyEvent`, `MouseButtonEvent`,
`PacketReceiveEvent`, `PacketSendEvent`, `ConnectEvent`, `DisconnectEvent`,
`PluginEnableEvent`, `PluginDisableEvent`, `ShutdownEvent`.

Packets: cancel or `replace()` them in the packet events, or use `ctx.network().send(packet)` /
`receive(packet)` with the game's own packet classes.

See [`java/example-plugin`](java/example-plugin) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Building

Needs Rust, a JDK 17+ and Gradle. On Linux, also `mingw-w64` and
`rustup target add x86_64-pc-windows-gnu` (it cross-compiles for Windows).

```sh
./build.sh        # dist/Prism.exe, dist/prism-api.jar, dist/example-plugin.jar
tests/run.sh      # end-to-end test against a stand-in game (Linux)
```

## Good to know

- Prism only runs inside your own game. It does not touch Lunar's files.
- Injecting into a client may be against that client's terms. Use it at your own risk.
- Some antivirus tools flag injectors. The full source is here so you can build it yourself.

MIT licensed.
