use crate::GameProcess;
use std::collections::HashMap;
use std::ffi::c_void;
use std::os::windows::ffi::OsStrExt;
use std::path::Path;
use std::ptr::null_mut;
use windows_sys::Win32::Foundation::{
    CloseHandle, BOOL, HANDLE, HMODULE, HWND, LPARAM, TRUE, WAIT_OBJECT_0,
};
use windows_sys::Win32::System::Diagnostics::Debug::WriteProcessMemory;
use windows_sys::Win32::System::LibraryLoader::{GetModuleHandleA, GetProcAddress};
use windows_sys::Win32::System::Memory::{
    VirtualAllocEx, VirtualFreeEx, MEM_COMMIT, MEM_RELEASE, MEM_RESERVE, PAGE_READWRITE,
};
use windows_sys::Win32::System::ProcessStatus::{
    EnumProcessModulesEx, GetModuleBaseNameW, LIST_MODULES_ALL,
};
use windows_sys::Win32::System::Threading::{
    CreateRemoteThread, GetExitCodeThread, OpenProcess, WaitForSingleObject, PROCESS_CREATE_THREAD,
    PROCESS_QUERY_INFORMATION, PROCESS_VM_OPERATION, PROCESS_VM_READ, PROCESS_VM_WRITE,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetWindowTextLengthW, GetWindowTextW, GetWindowThreadProcessId, IsWindowVisible,
};

struct Handle(HANDLE);
impl Drop for Handle {
    fn drop(&mut self) {
        unsafe { CloseHandle(self.0) };
    }
}

fn open(pid: u32, access: u32) -> Option<Handle> {
    let h = unsafe { OpenProcess(access, 0, pid) };
    (!h.is_null()).then(|| Handle(h))
}

/// Lower-case names of the modules loaded in a process.
fn modules(pid: u32) -> Vec<String> {
    let Some(p) = open(pid, PROCESS_QUERY_INFORMATION | PROCESS_VM_READ) else {
        return Vec::new();
    };
    let mut mods: Vec<HMODULE> = vec![null_mut(); 1024];
    let mut needed = 0u32;
    let size = (mods.len() * std::mem::size_of::<HMODULE>()) as u32;
    if unsafe { EnumProcessModulesEx(p.0, mods.as_mut_ptr(), size, &mut needed, LIST_MODULES_ALL) }
        == 0
    {
        return Vec::new();
    }
    let n = (needed as usize / std::mem::size_of::<HMODULE>()).min(mods.len());
    mods[..n]
        .iter()
        .filter_map(|&m| {
            let mut buf = [0u16; 260];
            let len =
                unsafe { GetModuleBaseNameW(p.0, m, buf.as_mut_ptr(), buf.len() as u32) } as usize;
            (len > 0).then(|| String::from_utf16_lossy(&buf[..len]).to_lowercase())
        })
        .collect()
}

unsafe extern "system" fn collect_window(hwnd: HWND, lparam: LPARAM) -> BOOL {
    let out = &mut *(lparam as *mut HashMap<u32, String>);
    if IsWindowVisible(hwnd) == 0 {
        return TRUE;
    }
    let len = GetWindowTextLengthW(hwnd);
    if len <= 0 {
        return TRUE;
    }
    let mut buf = vec![0u16; len as usize + 1];
    let n = GetWindowTextW(hwnd, buf.as_mut_ptr(), buf.len() as i32) as usize;
    let mut pid = 0u32;
    GetWindowThreadProcessId(hwnd, &mut pid);
    out.entry(pid)
        .or_insert_with(|| String::from_utf16_lossy(&buf[..n]));
    TRUE
}

/// Every process with a visible window that has a JVM loaded (Minecraft Java clients).
pub fn list_games() -> Vec<GameProcess> {
    let mut windows: HashMap<u32, String> = HashMap::new();
    unsafe { EnumWindows(Some(collect_window), &mut windows as *mut _ as LPARAM) };
    let mut games: Vec<GameProcess> = windows
        .into_iter()
        .filter_map(|(pid, title)| {
            let mods = modules(pid);
            if !mods.iter().any(|m| m == "jvm.dll") {
                return None;
            }
            let injected = mods.iter().any(|m| m.starts_with("prism_agent"));
            Some(GameProcess {
                pid,
                title,
                injected,
            })
        })
        .collect();
    games.sort_by_key(|g| g.pid);
    games
}

/// Loads `dll` into process `pid`.
pub fn inject(pid: u32, dll: &Path) -> Result<(), String> {
    let wide: Vec<u16> = dll
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let bytes = wide.len() * 2;
    let p = open(
        pid,
        PROCESS_CREATE_THREAD
            | PROCESS_QUERY_INFORMATION
            | PROCESS_VM_OPERATION
            | PROCESS_VM_WRITE
            | PROCESS_VM_READ,
    )
    .ok_or_else(|| {
        format!("can't open process {pid} (try running Prism as the same user as the game)")
    })?;
    unsafe {
        let mem = VirtualAllocEx(
            p.0,
            null_mut(),
            bytes,
            MEM_COMMIT | MEM_RESERVE,
            PAGE_READWRITE,
        );
        if mem.is_null() {
            return Err("VirtualAllocEx failed".into());
        }
        let result = (|| {
            if WriteProcessMemory(p.0, mem, wide.as_ptr() as *const c_void, bytes, null_mut()) == 0
            {
                return Err("WriteProcessMemory failed".to_string());
            }
            // kernel32 is mapped at the same address in every process of a boot session
            let k32 = GetModuleHandleA(b"kernel32.dll\0".as_ptr());
            let load =
                GetProcAddress(k32, b"LoadLibraryW\0".as_ptr()).ok_or("LoadLibraryW not found")?;
            let thread = CreateRemoteThread(
                p.0,
                null_mut(),
                0,
                Some(std::mem::transmute::<
                    _,
                    unsafe extern "system" fn(*mut c_void) -> u32,
                >(load)),
                mem,
                0,
                null_mut(),
            );
            if thread.is_null() {
                return Err("CreateRemoteThread failed".to_string());
            }
            let t = Handle(thread);
            if WaitForSingleObject(t.0, 15_000) != WAIT_OBJECT_0 {
                return Err("the game didn't load the DLL in time".to_string());
            }
            let mut code = 0u32;
            GetExitCodeThread(t.0, &mut code);
            if code == 0 {
                return Err(
                    "LoadLibraryW failed in the game (antivirus or missing file?)".to_string(),
                );
            }
            Ok(())
        })();
        VirtualFreeEx(p.0, mem, 0, MEM_RELEASE);
        result
    }
}
