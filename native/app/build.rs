// Embeds the agent DLL and the core jar into Prism.exe so the app is a single file.
// Set PRISM_AGENT_DLL and PRISM_CORE_JAR (build.sh / build.ps1 do). Without them the app looks for
// prism_agent.dll and prism-core.jar next to the exe instead (handy while developing).
use std::{env, fs, path::Path};

fn main() {
    let out = env::var("OUT_DIR").unwrap();
    for (var, name) in [
        ("PRISM_AGENT_DLL", "agent.bin"),
        ("PRISM_CORE_JAR", "core.bin"),
    ] {
        println!("cargo:rerun-if-env-changed={var}");
        let dest = Path::new(&out).join(name);
        match env::var(var) {
            Ok(src) if !src.is_empty() => {
                println!("cargo:rerun-if-changed={src}");
                fs::copy(&src, &dest).unwrap_or_else(|e| panic!("{var}={src}: {e}"));
            }
            _ => fs::write(&dest, b"").unwrap(),
        }
    }
}
