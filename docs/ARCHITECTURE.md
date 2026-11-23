# How Prism works

```
Prism.exe (Rust)                     the game (javaw.exe)
 ├─ finds games (window + jvm.dll)    ┌───────────────────────────────────────┐
 ├─ writes bin\prism_agent-<b>.dll ──▶│ prism_agent.dll (Rust)                 │
 │   and bin\prism-core-<b>.jar       │  ├─ JNI_GetCreatedJavaVMs → attach     │
 └─ LoadLibraryW via remote thread    │  ├─ finds Minecraft's class loader     │
                                      │  ├─ URLClassLoader(core jar, game CL)  │
                                      │  └─ prism.core.Prism.start(...)        │
                                      │ prism-core.jar (Java 8 bytecode)       │
                                      │  ├─ plugin manager (hot reload)        │
                                      │  ├─ event bus, scheduler, logging      │
                                      │  └─ plugins (each its own class loader)│
                                      └───────────────────────────────────────┘
```

## Native side (`native/`)

- **inject**: lists visible windows whose process has `jvm.dll` loaded, and loads the agent with
  `VirtualAllocEx` + `WriteProcessMemory` + `CreateRemoteThread(LoadLibraryW)`.
- **agent**: `DllMain` starts a thread that attaches to the JVM as a daemon thread, gets JVMTI
  (with `can_retransform_classes` for the hook system) and waits until Minecraft is loaded.
  It finds the game's class loader from the threads' context class loaders, falling back to
  JVMTI `GetLoadedClasses`, and detects the names the client uses:

  | names | detected by | clients |
  |---|---|---|
  | `mcp` | `Minecraft.getMinecraft()` | Lunar |
  | `srg` | `Minecraft.func_71410_x()` | Forge |
  | `notch` | `ave.A()` | vanilla, OptiFine, Badlion |

  The core jar is loaded by a `URLClassLoader` whose parent is the game's loader, so core and
  plugins can use Minecraft classes. The agent registers the natives of `prism.core.Native`
  (log, unload, retransform class).
- **Unload**: the app creates `bin\unload-<pid>`. The agent calls `Prism.stop()`, unregisters its
  natives, closes the core's class loader, detaches and frees the DLL. The game keeps running.
- Files are named per build (`-<build>`), so a new version never needs to overwrite a DLL or jar
  a running game still holds.

## Java side (`java/`)

- **api**: what plugins compile against (`Plugin`, `PluginContext`, `EventBus`, `Scheduler`,
  `Game`, `Logger`).
- **core**: `Prism` (start/stop), `PluginManager`, `Bus`, `Tasks`, `ClientThread`.
  - Plugins are copied to `cache\plugins\` before loading, so the original jar is never locked.
    The folder is scanned every second; a changed jar is reloaded once it stops changing.
  - Each plugin gets its own class loader and context. Its event handlers and tasks are removed
    when it is disabled, and one failing plugin never affects another.
  - Game state is only touched on the client thread. `Scheduler` runs work there through
    `Minecraft#addScheduledTask`, found by its shape, so it works with every naming scheme.

## Hooks (`prism.core.hook`)

Prism patches a few game methods to raise its events:

| event | method (MCP / SRG) | where |
|---|---|---|
| `TickEvent` | `Minecraft.runTick` / `func_71407_l` | start |
| `RenderOverlayEvent` | `GuiIngame.renderGameOverlay` / `func_175180_a` | every return |
| `RenderWorldEvent` | `EntityRenderer.renderWorldPass` / `func_175068_a` | before `renderHand` |
| (packets) | `NetworkManager.channelActive` | start: adds the packet handler |
| `KeyEvent`, `MouseButtonEvent` | `KeyBinding.setKeyBindState` / `func_74510_a` | start, cancelable |

- The agent retransforms a class (JVMTI `RetransformClasses`) with its ClassFileLoadHook switched on
  only for that call. The hook passes the class's current bytes to a Java transformer, so Prism
  patches what the game is really running (a client's own patches stay).
- The patch is ASM (bundled, moved to `prism.shadow.asm`). The added code only uses JDK classes:
  `((Predicate) System.getProperties().get("prism.hook.<id>")).test(args)`, so the game's class
  loader never needs to see Prism. A cancelable hook returns early when the handler says so.
- On unload every patched class is retransformed without the hook, which puts back the original.
  The property is set before patching and only removed once the class is restored.

## Packets (`prism.core.net`)

- A Netty handler named `prism_packets` goes into each client connection's pipeline, right before
  Minecraft's `packet_handler`, where packets are decoded objects both ways.
- New connections are caught by the `NetworkManager.channelActive` hook; the connection already
  open when Prism starts is found through `Minecraft → NetHandlerPlayClient → NetworkManager`.
  Singleplayer's server-side NetworkManager (direction SERVERBOUND) is skipped.
- `PacketReceiveEvent` / `PacketSendEvent` can be cancelled or replaced. `Network.send()` writes from
  the handler's own context (straight to the encoder) and `receive()` fires from it (straight to the
  game), so neither raises an event. Events run on the Netty thread.
- On unload the handler is removed from every pipeline before the core's class loader closes.

## Next steps (not built yet)

- In-game UI, updates, Forge and vanilla testing.
