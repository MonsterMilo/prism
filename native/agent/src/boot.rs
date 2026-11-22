//! Finds the game's class loader, loads prism-core.jar on top of it and starts the core.
//! Runs on the agent's own thread (attached to the JVM as a daemon thread).

use crate::jvm::{self, Env, Jvmti};
use crate::log::log;
use jni_sys::*;
use std::ffi::c_void;
use std::path::Path;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use std::time::{Duration, Instant};

/// Set by Native.unload0() (or the unload file); the agent thread then shuts down.
pub static UNLOAD: AtomicBool = AtomicBool::new(false);
static JVMTI: AtomicPtr<c_void> = AtomicPtr::new(null_mut());

/// Which class names the client uses.
fn mode_of(env: Env, loader: jobject) -> Option<(&'static str, jclass)> {
    if let Some(mc) = env.load_class(loader, "net.minecraft.client.Minecraft") {
        if env
            .static_method(mc, "getMinecraft", "()Lnet/minecraft/client/Minecraft;")
            .is_some()
        {
            return Some(("mcp", mc)); // Lunar
        }
        if env
            .static_method(mc, "func_71410_x", "()Lnet/minecraft/client/Minecraft;")
            .is_some()
        {
            return Some(("srg", mc)); // Forge
        }
        env.delete_local(mc);
    }
    if let Some(mc) = env.load_class(loader, "ave") {
        if env.static_method(mc, "A", "()Lave;").is_some() {
            return Some(("notch", mc)); // vanilla 1.8.9
        }
        env.delete_local(mc);
    }
    None
}

pub struct Game {
    pub loader: jobject, // global refs
    pub minecraft: jclass,
    pub mode: &'static str,
}

/// 1) context class loaders of all threads (Lunar's and Forge's loaders show up here),
/// 2) JVMTI: the loader of the loaded Minecraft class.
fn find_game(env: Env, ti: Option<Jvmti>) -> Option<Game> {
    let found = |loader: jobject| -> Option<Game> {
        let (mode, mc) = mode_of(env, loader)?;
        Some(Game {
            loader: env.global(loader),
            minecraft: env.global(mc),
            mode,
        })
    };
    // 1) threads
    if let Some(g) = (|| -> Option<Game> {
        let thread = env.find_class("java/lang/Thread").ok()?;
        let all = env.static_method(thread, "getAllStackTraces", "()Ljava/util/Map;")?;
        let map = env.call_static_object(thread, all, &[]).ok()?;
        let map_cls = env.find_class("java/util/Map").ok()?;
        let key_set = env.method(map_cls, "keySet", "()Ljava/util/Set;").ok()?;
        let set = env.call_object(map, key_set, &[]).ok()?;
        let coll = env.find_class("java/util/Collection").ok()?;
        let to_array = env.method(coll, "toArray", "()[Ljava/lang/Object;").ok()?;
        let arr = env.call_object(set, to_array, &[]).ok()?;
        let ctx = env
            .method(thread, "getContextClassLoader", "()Ljava/lang/ClassLoader;")
            .ok()?;
        for i in 0..env.array_len(arr) {
            let t = env.array_get(arr, i);
            if let Ok(loader) = env.call_object(t, ctx, &[]) {
                if !loader.is_null() {
                    if let Some(g) = found(loader) {
                        return Some(g);
                    }
                }
            }
        }
        None
    })() {
        return Some(g);
    }
    // 2) JVMTI
    let mut game = None;
    if let Some(ti) = ti {
        ti.for_class_loaders(
            env,
            &["Lnet/minecraft/client/Minecraft;", "Lave;"],
            |_, loader| {
                game = found(loader);
                game.is_some()
            },
        );
    }
    game
}

/// A URLClassLoader for the core jar with the game's loader as parent, so the core sees game classes.
fn core_loader(env: Env, jar: &Path, parent: jobject) -> Result<jobject, String> {
    let file_cls = env.find_class("java/io/File")?;
    let path = env.string(&jar.to_string_lossy());
    let file = env.new_object(file_cls, "(Ljava/lang/String;)V", &[jvalue { l: path }])?;
    let to_uri = env.method(file_cls, "toURI", "()Ljava/net/URI;")?;
    let uri = env.call_object(file, to_uri, &[])?;
    let uri_cls = env.find_class("java/net/URI")?;
    let to_url = env.method(uri_cls, "toURL", "()Ljava/net/URL;")?;
    let url = env.call_object(uri, to_url, &[])?;
    let url_cls = env.find_class("java/net/URL")?;
    let urls = env.new_object_array(url_cls, 1, url);
    let ucl = env.find_class("java/net/URLClassLoader")?;
    env.new_object(
        ucl,
        "([Ljava/net/URL;Ljava/lang/ClassLoader;)V",
        &[jvalue { l: urls }, jvalue { l: parent }],
    )
}

// ------------------------------------------------------------------ natives of prism.core.Native

extern "system" fn n_log(env: *mut JNIEnv, _: jclass, line: jstring) {
    log(&Env(env).rust_string(line));
}

extern "system" fn n_unload(_: *mut JNIEnv, _: jclass) {
    UNLOAD.store(true, Ordering::SeqCst);
}

extern "system" fn n_redefine(
    env: *mut JNIEnv,
    _: jclass,
    target: jclass,
    bytes: jbyteArray,
) -> jboolean {
    let p = JVMTI.load(Ordering::SeqCst);
    if p.is_null() || target.is_null() || bytes.is_null() {
        return JNI_FALSE;
    }
    let ti: Jvmti = unsafe { std::mem::transmute(p) };
    let data = Env(env).byte_array(bytes);
    let r = ti.redefine(target, &data);
    if r != 0 {
        log(&format!("RedefineClasses failed: JVMTI error {r}"));
    }
    (r == 0) as jboolean
}

// ------------------------------------------------------------------ the agent thread

pub struct Paths {
    pub home: std::path::PathBuf,
    pub core_jar: std::path::PathBuf,
}

/// Everything the agent does, start to unload. `extra_unload` is polled for an outside unload request.
pub fn run(vm: *mut JavaVM, paths: Paths, extra_unload: impl Fn() -> bool) {
    UNLOAD.store(false, Ordering::SeqCst);
    let Some(env) = jvm::attach(vm, "Prism Agent") else {
        log("can't attach to the JVM");
        return;
    };
    let ti = Jvmti::get(vm);
    if let Some(t) = ti {
        let redefine = t.enable_redefine();
        log(&format!("JVMTI ready (redefine classes: {redefine})"));
        JVMTI.store(
            unsafe { std::mem::transmute::<Jvmti, *mut c_void>(t) },
            Ordering::SeqCst,
        );
    } else {
        log("JVMTI not available");
    }

    // wait for Minecraft to be loaded (injecting at the launcher screen is fine)
    let started = Instant::now();
    let game = loop {
        if UNLOAD.load(Ordering::SeqCst) || extra_unload() {
            return finish(vm, ti, None, None);
        }
        env.push_frame(256);
        let g = find_game(env, ti);
        env.pop_frame();
        if let Some(g) = g {
            break g;
        }
        if started.elapsed() > Duration::from_secs(300) {
            log("gave up: no Minecraft 1.8.9 classes found in 5 minutes");
            return finish(vm, ti, None, None);
        }
        std::thread::sleep(Duration::from_millis(500));
    };
    log(&format!(
        "game found: {} names ({})",
        game.mode,
        match game.mode {
            "mcp" => "Lunar",
            "srg" => "Forge",
            _ => "vanilla based",
        }
    ));

    let core = match start_core(env, &paths, &game) {
        Ok(c) => Some(c),
        Err(e) => {
            log(&format!("core failed to start: {e}"));
            None
        }
    };

    if core.is_some() {
        while !UNLOAD.load(Ordering::SeqCst) && !extra_unload() {
            std::thread::sleep(Duration::from_millis(100));
        }
        log("unload requested");
    }
    finish(vm, ti, Some(game), core)
}

pub struct Core {
    loader: jobject, // global
    native: jclass,  // global
    prism: jclass,   // global
}

fn start_core(env: Env, paths: &Paths, game: &Game) -> Result<Core, String> {
    if !paths.core_jar.exists() {
        return Err(format!("{} is missing", paths.core_jar.display()));
    }
    env.push_frame(64);
    let r = (|| {
        let loader = core_loader(env, &paths.core_jar, game.loader)?;
        let native = env
            .load_class(loader, "prism.core.Native")
            .ok_or("prism.core.Native not in core jar")?;
        env.register_natives(
            native,
            &[
                ("log0", "(Ljava/lang/String;)V", n_log as *mut c_void),
                ("unload0", "()V", n_unload as *mut c_void),
                (
                    "redefineClass0",
                    "(Ljava/lang/Class;[B)Z",
                    n_redefine as *mut c_void,
                ),
            ],
        )?;
        let prism = env
            .load_class(loader, "prism.core.Prism")
            .ok_or("prism.core.Prism not in core jar")?;
        let start = env
            .static_method(
                prism,
                "start",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/String;",
            )
            .ok_or("Prism.start missing")?;
        let home = env.string(&paths.home.to_string_lossy());
        let mode = env.string(game.mode);
        let err = env.call_static_object(
            prism,
            start,
            &[
                jvalue { l: home },
                jvalue { l: mode },
                jvalue { l: game.minecraft },
            ],
        )?;
        if !err.is_null() {
            env.unregister_natives(native);
            return Err(env.rust_string(err));
        }
        log("core started");
        Ok(Core {
            loader: env.global(loader),
            native: env.global(native),
            prism: env.global(prism),
        })
    })();
    env.pop_frame();
    r
}

/// Stops the core, removes our natives and lets go of every Java reference, then detaches.
fn finish(vm: *mut JavaVM, ti: Option<Jvmti>, game: Option<Game>, core: Option<Core>) {
    if let Some(env) = jvm::env_of(vm) {
        if let Some(core) = core {
            if let Some(stop) = env.static_method(core.prism, "stop", "()V") {
                if let Err(e) = env.call_static_void(core.prism, stop, &[]) {
                    log(&format!("Prism.stop failed: {e}"));
                }
            }
            // after this, Native methods throw UnsatisfiedLinkError instead of calling into a freed DLL
            env.unregister_natives(core.native);
            if let Ok(ucl) = env.find_class("java/net/URLClassLoader") {
                if let Ok(close) = env.method(ucl, "close", "()V") {
                    let _ = env.call_void(core.loader, close, &[]);
                }
            }
            env.delete_global(core.native);
            env.delete_global(core.prism);
            env.delete_global(core.loader);
        }
        if let Some(g) = game {
            env.delete_global(g.minecraft);
            env.delete_global(g.loader);
        }
    }
    JVMTI.store(null_mut(), Ordering::SeqCst);
    if let Some(t) = ti {
        t.dispose();
    }
    jvm::detach(vm);
    log("unloaded");
}
