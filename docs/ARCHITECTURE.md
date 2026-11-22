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
  (with `can_redefine_classes` for the hook system later) and waits until Minecraft is loaded.
  It finds the game's class loader from the threads' context class loaders, falling back to
  JVMTI `GetLoadedClasses`, and detects the names the client uses:

  | names | detected by | clients |
  |---|---|---|
  | `mcp` | `Minecraft.getMinecraft()` | Lunar |
  | `srg` | `Minecraft.func_71410_x()` | Forge |
  | `notch` | `ave.A()` | vanilla, OptiFine, Badlion |

  The core jar is loaded by a `URLClassLoader` whose parent is the game's loader, so core and
  plugins can use Minecraft classes. The agent registers the natives of `prism.core.Native`
  (log, unload, redefine class).
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

## Next steps (not built yet)

- Hooks: patch game methods (JVMTI redefine + ASM) for ticks, rendering and input events.
- Packets: a Netty handler in the game's pipeline for packet events.
- In-game UI, updates, Forge and vanilla testing.
