//! Finding games and loading the agent DLL into them.
//!
//! Injection is the classic, documented way: allocate the DLL path in the game process,
//! then start a thread there at LoadLibraryW. The agent does the rest from DllMain.

use std::path::Path;

#[derive(Clone, Debug)]
pub struct GameProcess {
    pub pid: u32,
    /// Window title, e.g. "Lunar Client 1.8.9 (...)".
    pub title: String,
    /// True when a prism_agent DLL is loaded in it.
    pub injected: bool,
}

impl GameProcess {
    /// Lunar 1.8.9 (what auto-inject targets).
    pub fn is_lunar_189(&self) -> bool {
        let t = self.title.to_lowercase();
        t.contains("lunar") && t.contains("1.8.9")
    }
}

#[cfg(windows)]
mod win;
#[cfg(windows)]
pub use win::{inject, list_games};

#[cfg(not(windows))]
pub fn list_games() -> Vec<GameProcess> {
    Vec::new()
}

#[cfg(not(windows))]
pub fn inject(_pid: u32, _dll: &Path) -> Result<(), String> {
    Err("injection is only supported on Windows".into())
}

/// Asks the agent in `pid` to unload (it polls for this file next to its core jar).
pub fn request_unload(bin_dir: &Path, pid: u32) -> std::io::Result<()> {
    std::fs::write(bin_dir.join(format!("unload-{pid}")), b"")
}
