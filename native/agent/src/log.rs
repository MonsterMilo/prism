//! The agent's own log: logs/agent.log in the Prism folder (the core keeps logs/prism.log).

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::Path;
use std::sync::Mutex;

static FILE: Mutex<Option<File>> = Mutex::new(None);

pub fn open(logs_dir: &Path) {
    let _ = std::fs::create_dir_all(logs_dir);
    let f = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(logs_dir.join("agent.log"))
        .ok();
    *FILE.lock().unwrap() = f;
}

pub fn log(line: &str) {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let text = format!(
        "[{:02}:{:02}:{:02} UTC] {line}\n",
        secs / 3600 % 24,
        secs / 60 % 60,
        secs % 60
    );
    if let Ok(mut g) = FILE.lock() {
        if let Some(f) = g.as_mut() {
            let _ = f.write_all(text.as_bytes());
            let _ = f.flush();
        }
    }
    #[cfg(not(windows))]
    eprint!("[prism-agent] {text}");
}
