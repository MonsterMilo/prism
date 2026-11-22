//! The Prism folder (%APPDATA%\Prism): installing the agent + core, settings, plugins, log.

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

static AGENT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/agent.bin"));
static CORE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/core.bin"));

pub fn home() -> PathBuf {
    if let Some(appdata) = std::env::var_os("APPDATA") {
        return PathBuf::from(appdata).join("Prism");
    }
    let base = std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."));
    base.join(".prism")
}

/// Files of this build, ready to inject.
pub struct Install {
    pub agent: PathBuf,
    pub build: String,
}

fn fnv(data: &[&[u8]]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for d in data {
        for b in d.iter() {
            h ^= *b as u64;
            h = h.wrapping_mul(0x100_0000_01b3);
        }
    }
    h
}

/// Writes bin\prism_agent-<build>.dll and bin\prism-core-<build>.jar (once per build).
/// Files are named by build so an update never has to overwrite a file a running game holds.
pub fn install(home: &Path) -> Result<Install, String> {
    let bin = home.join("bin");
    for d in ["bin", "plugins", "logs", "data"] {
        fs::create_dir_all(home.join(d))
            .map_err(|e| format!("can't create {}: {e}", home.join(d).display()))?;
    }
    let (agent, core) = if AGENT.is_empty() || CORE.is_empty() {
        // development build: files next to the exe
        let dir = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(PathBuf::from))
            .unwrap_or_default();
        let a = fs::read(dir.join("prism_agent.dll"))
            .map_err(|_| "prism_agent.dll not found next to Prism.exe")?;
        let c = fs::read(dir.join("prism-core.jar"))
            .map_err(|_| "prism-core.jar not found next to Prism.exe")?;
        (a, c)
    } else {
        (AGENT.to_vec(), CORE.to_vec())
    };
    let build = format!("{:012x}", fnv(&[&agent, &core]) & 0xffff_ffff_ffff);
    let agent_path = bin.join(format!("prism_agent-{build}.dll"));
    let core_path = bin.join(format!("prism-core-{build}.jar"));
    if fs::read(&agent_path).map(|d| d != agent).unwrap_or(true) {
        fs::write(&agent_path, &agent)
            .map_err(|e| format!("can't write {}: {e}", agent_path.display()))?;
    }
    if fs::read(&core_path).map(|d| d != core).unwrap_or(true) {
        fs::write(&core_path, &core)
            .map_err(|e| format!("can't write {}: {e}", core_path.display()))?;
    }
    // remove older builds (files still in use by a running game just stay until next time)
    if let Ok(rd) = fs::read_dir(&bin) {
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            let old = (name.starts_with("prism_agent-") || name.starts_with("prism-core-"))
                && !name.contains(&build);
            if old {
                let _ = fs::remove_file(e.path());
            }
        }
    }
    Ok(Install {
        agent: agent_path,
        build,
    })
}

// ------------------------------------------------------------------ settings

#[derive(Clone, PartialEq)]
pub struct Settings {
    pub auto_inject: bool,
}

pub fn load_settings(home: &Path) -> Settings {
    let text = fs::read_to_string(home.join("app.properties")).unwrap_or_default();
    let get = |k: &str| {
        text.lines().find_map(|l| {
            l.strip_prefix(&format!("{k}="))
                .map(str::trim)
                .map(String::from)
        })
    };
    Settings {
        auto_inject: get("auto_inject").map(|v| v == "true").unwrap_or(true),
    }
}

pub fn save_settings(home: &Path, s: &Settings) {
    let _ = fs::write(
        home.join("app.properties"),
        format!("auto_inject={}\n", s.auto_inject),
    );
}

// ------------------------------------------------------------------ plugins

pub struct PluginFile {
    pub file: String,
    pub name: String,
    pub version: String,
    pub error: Option<String>,
}

fn read_properties(jar: &Path) -> Result<Vec<(String, String)>, String> {
    let f = fs::File::open(jar).map_err(|e| e.to_string())?;
    let mut zip = zip::ZipArchive::new(f).map_err(|_| "not a valid jar".to_string())?;
    let mut entry = zip
        .by_name("plugin.properties")
        .map_err(|_| "no plugin.properties".to_string())?;
    let mut text = String::new();
    entry.read_to_string(&mut text).map_err(|e| e.to_string())?;
    Ok(text
        .lines()
        .filter(|l| !l.trim_start().starts_with('#'))
        .filter_map(|l| {
            l.split_once('=')
                .map(|(k, v)| (k.trim().to_string(), v.trim().to_string()))
        })
        .collect())
}

pub fn plugins(home: &Path) -> Vec<PluginFile> {
    let mut out = Vec::new();
    let Ok(rd) = fs::read_dir(home.join("plugins")) else {
        return out;
    };
    for e in rd.flatten() {
        let path = e.path();
        if path.extension().and_then(|x| x.to_str()) != Some("jar") {
            continue;
        }
        let file = e.file_name().to_string_lossy().into_owned();
        match read_properties(&path) {
            Ok(props) => {
                let get = |k: &str| props.iter().find(|(pk, _)| pk == k).map(|(_, v)| v.clone());
                out.push(PluginFile {
                    name: get("name")
                        .or_else(|| get("id"))
                        .unwrap_or_else(|| file.clone()),
                    version: get("version").unwrap_or_default(),
                    file,
                    error: None,
                });
            }
            Err(err) => out.push(PluginFile {
                name: file.clone(),
                version: String::new(),
                file,
                error: Some(err),
            }),
        }
    }
    out.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    out
}

/// The last `n` lines of the core's log.
pub fn log_tail(home: &Path, n: usize) -> String {
    let text = fs::read_to_string(home.join("logs").join("prism.log")).unwrap_or_default();
    let lines: Vec<&str> = text.lines().collect();
    lines[lines.len().saturating_sub(n)..].join("\n")
}
