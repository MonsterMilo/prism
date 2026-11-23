#!/usr/bin/env bash
# Builds everything into dist/: Prism.exe (agent + core embedded), prism-api.jar, example-plugin.jar.
# On Linux this cross-compiles for Windows (needs mingw-w64 and `rustup target add x86_64-pc-windows-gnu`).
set -euo pipefail
cd "$(dirname "$0")"
TARGET_ARGS=()
OUT=target/release
if [[ "$(uname -s)" != MINGW* && "$(uname -s)" != MSYS* ]]; then
  TARGET_ARGS=(--target x86_64-pc-windows-gnu); OUT=target/x86_64-pc-windows-gnu/release
fi
(cd java && gradle -q build)
cargo build --release "${TARGET_ARGS[@]}" -p prism-agent
PRISM_AGENT_DLL="$PWD/$OUT/prism_agent.dll" PRISM_CORE_JAR="$PWD/java/core/build/libs/prism-core.jar" \
  cargo build --release "${TARGET_ARGS[@]}" -p prism-app
mkdir -p dist
cp "$OUT/Prism.exe" dist/
cp java/api/build/libs/prism-api.jar dist/prism-api.jar
cp java/example-plugin/build/libs/example-plugin.jar dist/example-plugin.jar
ls -la dist
