#!/usr/bin/env bash
# End-to-end test on Linux: a stand-in game (MCP names, own class loader, client thread) gets the
# agent loaded with jcmd, then we check load, chat, hot reload, removal and unload.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="$(mktemp -d)"; trap 'kill $GAME 2>/dev/null || true; rm -rf "$W"' EXIT
fail() { echo "FAIL: $1"; echo "--- game output"; cat "$W/out.txt"; echo "--- prism.log"; cat "$W/home/logs/prism.log" 2>/dev/null; exit 1; }
wait_for() { for _ in $(seq 1 100); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 0.1; done; fail "timed out waiting for '$2' in $(basename "$1")"; }

(cd "$ROOT/java" && gradle -q build)
(cd "$ROOT" && cargo build -q -p prism-agent)
mkdir -p "$W/classes/game" "$W/classes/launcher" "$W/home/bin" "$W/home/plugins"
SRC="$ROOT/tests/fake-game/src"
javac -q -d "$W/classes/game" $(find "$SRC/net" -name '*.java') 2>/dev/null || javac -d "$W/classes/game" $(find "$SRC/net" -name '*.java')
(cd "$W/classes/game" && jar cf "$W/game.jar" .)
javac -d "$W/classes/launcher" "$SRC/launcher/Launcher.java"
cp "$ROOT/java/core/build/libs/prism-core-0.1.0.jar" "$W/home/bin/prism-core.jar"
cp "$ROOT/java/example-plugin/build/libs/example-plugin-0.1.0.jar" "$W/home/plugins/example.jar"

java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -XX:+EnableDynamicAgentLoading -cp "$W/classes/launcher" launcher.Launcher "$W/game.jar" > "$W/out.txt" 2>&1 &
GAME=$!
sleep 0.5   # inject while the game is still "loading"
jcmd $GAME JVMTI.agent_load "$ROOT/target/debug/libprism_agent.so" "$W/home|$W/home/bin/prism-core.jar" >/dev/null

wait_for "$W/out.txt" "GAME: started"
wait_for "$W/out.txt" "CHAT\[Client thread\]: §d\[Prism\] §fLoaded Example"
wait_for "$W/out.txt" "CHAT\[Client thread\]: §d\[Example\] §fHello!"
grep -q "mcp names" "$W/home/logs/prism.log" || fail "mappings not detected as mcp"
echo "ok  injected, core started on the game's class loader, plugin loaded, chat on client thread"

# hot reload: replace the jar with a new version (different properties -> different file)
mkdir -p "$W/p2"; (cd "$W/p2" && unzip -q "$W/home/plugins/example.jar" && sed -i 's/version=1.0.0/version=1.0.1/' plugin.properties && jar cf "$W/example2.jar" .)
cp "$W/example2.jar" "$W/home/plugins/example.jar"
wait_for "$W/home/logs/prism.log" "enabled Example 1.0.1"
wait_for "$W/out.txt" "Reloaded Example"
grep -q "disabled Example 1.0.0" "$W/home/logs/prism.log" || fail "old version not disabled"
echo "ok  hot reload"

# a broken plugin must not break the others
echo "not a jar" > "$W/home/plugins/broken.jar"
wait_for "$W/out.txt" "broken.jar failed to load"
echo "ok  broken plugin reported"
rm "$W/home/plugins/broken.jar"

rm "$W/home/plugins/example.jar"
wait_for "$W/home/logs/prism.log" "disabled Example 1.0.1"
echo "ok  plugin removed"

touch "$W/home/bin/unload-$GAME"
wait_for "$W/home/logs/agent.log" "unloaded"
grep -q "stopped" "$W/home/logs/prism.log" 2>/dev/null || grep -q "stopped" "$W/home/logs/prism.old.log" || fail "core not stopped"
kill -0 $GAME || fail "game died after unload"
echo "ok  unloaded, game still running"

# inject again after unload (the app's Inject button after Unload)
cp "$ROOT/java/example-plugin/build/libs/example-plugin-0.1.0.jar" "$W/home/plugins/example.jar"
: > "$W/out.txt.mark"; LINES=$(wc -l < "$W/out.txt")
jcmd $GAME JVMTI.agent_load "$ROOT/target/debug/libprism_agent.so" "$W/home|$W/home/bin/prism-core.jar" >/dev/null
for _ in $(seq 1 100); do tail -n +$((LINES+1)) "$W/out.txt" | grep -q "Loaded Example" && break; sleep 0.1; done
tail -n +$((LINES+1)) "$W/out.txt" | grep -q "Loaded Example" || fail "re-inject didn't load"
echo "ok  injected again"
echo "ALL PASSED"
