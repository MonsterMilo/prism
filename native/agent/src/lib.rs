//! prism_agent: the native part of Prism.
//!
//! On Windows the Prism app loads this DLL into the game with LoadLibraryW. DllMain starts a
//! thread that finds the JVM (JNI_GetCreatedJavaVMs), waits for Minecraft's classes, loads
//! prism-core.jar on top of the game's class loader and calls prism.core.Prism.start().
//!
//! File layout (the Prism folder, %APPDATA%\Prism):
//!   bin\prism_agent-<build>.dll   this DLL (named per build so updates never hit a locked file)
//!   bin\prism-core-<build>.jar    the Java core of the same build
//!   bin\unload-<pid>              created by the app to unload Prism from that game
//!
//! On other systems the same code can be loaded as a JVMTI agent (Agent_OnAttach), which the
//! tests use: options = "<prism folder>|<core jar>".

mod boot;
mod jvm;
mod log;

use std::path::PathBuf;

/// bin\prism_agent-abc.dll -> (Prism folder, bin\prism-core-abc.jar)
#[allow(dead_code)]
fn paths_from_dll(dll: &std::path::Path) -> boot::Paths {
    let bin = dll.parent().map(PathBuf::from).unwrap_or_default();
    let home = bin
        .parent()
        .map(PathBuf::from)
        .unwrap_or_else(|| bin.clone());
    let stem = dll
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let jar = match stem.strip_prefix("prism_agent-") {
        Some(build) => format!("prism-core-{build}.jar"),
        None => "prism-core.jar".to_string(),
    };
    boot::Paths {
        home,
        core_jar: bin.join(jar),
    }
}

#[cfg(windows)]
mod win {
    use super::*;
    use jni_sys::{jint, jsize, JavaVM, JNI_OK};
    use std::ffi::c_void;
    use std::os::windows::ffi::OsStringExt;
    use std::ptr::null_mut;
    use windows_sys::Win32::Foundation::{BOOL, HMODULE, TRUE};
    use windows_sys::Win32::System::LibraryLoader::{
        DisableThreadLibraryCalls, FreeLibraryAndExitThread, GetModuleFileNameW, GetModuleHandleA,
        GetProcAddress,
    };
    use windows_sys::Win32::System::SystemServices::DLL_PROCESS_ATTACH;
    use windows_sys::Win32::System::Threading::{CreateThread, GetCurrentProcessId};

    static mut SELF: HMODULE = null_mut();

    type GetCreatedJavaVMs = unsafe extern "system" fn(*mut *mut JavaVM, jsize, *mut jsize) -> jint;

    fn find_vm() -> Option<*mut JavaVM> {
        unsafe {
            let jvm = GetModuleHandleA(b"jvm.dll\0".as_ptr());
            if jvm.is_null() {
                return None;
            }
            let f = GetProcAddress(jvm, b"JNI_GetCreatedJavaVMs\0".as_ptr())?;
            let f: GetCreatedJavaVMs = std::mem::transmute(f);
            let mut vm: *mut JavaVM = null_mut();
            let mut n: jsize = 0;
            (f(&mut vm, 1, &mut n) == JNI_OK && n > 0 && !vm.is_null()).then_some(vm)
        }
    }

    fn own_path() -> PathBuf {
        let mut buf = [0u16; 1024];
        let n = unsafe { GetModuleFileNameW(SELF, buf.as_mut_ptr(), buf.len() as u32) } as usize;
        PathBuf::from(std::ffi::OsString::from_wide(&buf[..n]))
    }

    unsafe extern "system" fn agent_thread(_: *mut c_void) -> u32 {
        let paths = paths_from_dll(&own_path());
        log::open(&paths.home.join("logs"));
        log::log(&format!(
            "Prism agent {} loaded into pid {}",
            env!("CARGO_PKG_VERSION"),
            GetCurrentProcessId()
        ));
        let unload_file = paths
            .core_jar
            .with_file_name(format!("unload-{}", GetCurrentProcessId()));
        let _ = std::fs::remove_file(&unload_file);
        let mut vm = None;
        for _ in 0..60 {
            vm = find_vm();
            if vm.is_some() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(500));
        }
        match vm {
            Some(vm) => boot::run(vm, paths, || {
                let hit = unload_file.exists();
                if hit {
                    let _ = std::fs::remove_file(&unload_file);
                }
                hit
            }),
            None => log::log("no JVM in this process"),
        }
        // give any thread that was just inside our code a moment to leave, then unload the DLL
        std::thread::sleep(std::time::Duration::from_millis(300));
        FreeLibraryAndExitThread(SELF, 0);
    }

    #[no_mangle]
    #[allow(non_snake_case)]
    pub extern "system" fn DllMain(module: HMODULE, reason: u32, _: *mut c_void) -> BOOL {
        if reason == DLL_PROCESS_ATTACH {
            unsafe {
                SELF = module;
                DisableThreadLibraryCalls(module);
                let h = CreateThread(null_mut(), 0, Some(agent_thread), null_mut(), 0, null_mut());
                if !h.is_null() {
                    windows_sys::Win32::Foundation::CloseHandle(h);
                }
            }
        }
        TRUE
    }
}

/// JVMTI entry for loading the agent with the attach API (used by the tests on Linux).
#[cfg(not(windows))]
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Agent_OnAttach(
    vm: *mut jni_sys::JavaVM,
    options: *const std::ffi::c_char,
    _reserved: *mut std::ffi::c_void,
) -> jni_sys::jint {
    let opts = if options.is_null() {
        String::new()
    } else {
        unsafe { std::ffi::CStr::from_ptr(options) }
            .to_string_lossy()
            .into_owned()
    };
    let mut parts = opts.splitn(2, '|');
    let home = PathBuf::from(parts.next().unwrap_or("."));
    let core_jar = parts
        .next()
        .map(PathBuf::from)
        .unwrap_or_else(|| home.join("bin").join("prism-core.jar"));
    log::open(&home.join("logs"));
    log::log("Prism agent loaded (attach)");
    let vm_addr = vm as usize;
    let unload_file = home
        .join("bin")
        .join(format!("unload-{}", std::process::id()));
    let _ = std::fs::remove_file(&unload_file);
    std::thread::spawn(move || {
        let unload = || {
            let hit = unload_file.exists();
            if hit {
                let _ = std::fs::remove_file(&unload_file);
            }
            hit
        };
        boot::run(
            vm_addr as *mut jni_sys::JavaVM,
            boot::Paths { home, core_jar },
            unload,
        )
    });
    0
}
