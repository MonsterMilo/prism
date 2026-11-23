#!/usr/bin/env bash
# End-to-end test on Linux: a stand-in game (MCP names, own class loader, client thread) gets the
# agent loaded with jcmd, then we check load, chat, hot reload, removal and unload.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GAME=""; W="$(mktemp -d)"; trap 'kill $GAME 2>/dev/null || true; rm -rf "$W"' EXIT
fail() { echo "FAIL: $1"; echo "--- game output"; cat "$W/out.txt"; echo "--- prism.log"; cat "$W/home/logs/prism.log" 2>/dev/null; exit 1; }
wait_for() { for _ in $(seq 1 100); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 0.1; done; fail "timed out waiting for '$2' in $(basename "$1")"; }

(cd "$ROOT/java" && gradle -q build)
(cd "$ROOT" && cargo build -q -p prism-agent)
mkdir -p "$W/classes/game" "$W/classes/launcher" "$W/home/bin" "$W/home/plugins"
SRC="$ROOT/tests/fake-game/src"
NETTY="$W/netty.jar"
curl -sSfL -o "$NETTY" https://repo1.maven.org/maven2/io/netty/netty-all/4.0.23.Final/netty-all-4.0.23.Final.jar
javac -d "$W/classes/game" -cp "$NETTY" $(find "$SRC/net" -name '*.java')
(cd "$W/classes/game" && jar cf "$W/game.jar" .)
javac -d "$W/classes/launcher" "$SRC/launcher/Launcher.java"
cp "$ROOT/java/core/build/libs/prism-core.jar" "$W/home/bin/prism-core.jar"
cp "$ROOT/java/example-plugin/build/libs/example-plugin.jar" "$W/home/plugins/example.jar"
mkdir -p "$W/hooktest"
javac -d "$W/hooktest" -cp "$ROOT/java/api/build/libs/prism-api.jar" "$ROOT/tests/hook-plugin/src/hooktest/HookTestPlugin.java"
cp "$ROOT/tests/hook-plugin/plugin.properties" "$W/hooktest/"
(cd "$W/hooktest" && jar cf "$W/home/plugins/hooktest.jar" .)

mkdir -p "$W/net"; java -Dfake.net="$W/net" -Dfake.keys="$W/keys" -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -XX:+EnableDynamicAgentLoading -cp "$W/classes/launcher" launcher.Launcher "$W/game.jar" "$NETTY" > "$W/out.txt" 2>&1 &
GAME=$!
sleep 0.5   # inject while the game is still "loading"
jcmd $GAME JVMTI.agent_load "$ROOT/target/debug/libprism_agent.so" "$W/home|$W/home/bin/prism-core.jar" >/dev/null

wait_for "$W/out.txt" "GAME: started"
wait_for "$W/out.txt" "CHAT\[Client thread\]: §d\[Prism\] §fLoaded Example, HookTest"
wait_for "$W/out.txt" "CHAT\[Client thread\]: §d\[Example\] §fHello!"
grep -q "mcp names" "$W/home/logs/prism.log" || fail "mappings not detected as mcp"
echo "ok  injected, core started on the game's class loader, plugin loaded, chat on client thread"

# hooks
grep -q "hooks placed: 6" "$W/home/logs/prism.log" || fail "not all 6 hooks placed"
wait_for "$W/home/logs/prism.log" "counts tick=[1-9][0-9]* overlay=[1-9][0-9]* world=[1-9]"
grep -q "overlay size 427x240 pt 0.5" "$W/home/logs/prism.log" || fail "overlay event values wrong"
wait_for "$W/out.txt" "DRAW text '§dPrism"
echo "ok  tick, HUD and world events fire; drawing works"
touch "$W/keys"
wait_for "$W/out.txt" "GAME KEY -100 true"
grep -q "GAME KEY 31 true" "$W/out.txt" || fail "key 31 should reach the game"
grep -q "GAME KEY 30 " "$W/out.txt" && fail "key 30 should have been cancelled"
grep -q "key event 30 true" "$W/home/logs/prism.log" || fail "no key event"
grep -q "mouse event 0 true" "$W/home/logs/prism.log" || fail "no mouse event"
echo "ok  key and mouse events, cancel works"

# packets
touch "$W/net/connect"
wait_for "$W/home/logs/prism.log" "connect event embedded"
wait_for "$W/out.txt" "SERVER GOT C01PacketChatMessage hello from plugin"
wait_for "$W/out.txt" "GAME RECV CLIENTBOUND S02PacketChat injected"
echo "ok  connect event; plugin sends to the server and to the game"
touch "$W/net/traffic"
wait_for "$W/out.txt" "SERVER GOT C01PacketChatMessage /hub"
wait_for "$W/out.txt" "GAME RECV CLIENTBOUND S02PacketChat replaced"
wait_for "$W/out.txt" "GAME RECV SERVERBOUND S02PacketChat server side"
grep -q "S02PacketChat secret" "$W/out.txt" && fail "cancelled packet reached the game"
grep -q "/blocked" "$W/out.txt" && fail "cancelled packet reached the server"
grep -q "recv S02PacketChat server side" "$W/home/logs/prism.log" && fail "server-side connection was hooked"
grep -q "recv S02PacketChat hello" "$W/home/logs/prism.log" || fail "no receive event"
grep -q "send C01PacketChatMessage /hub" "$W/home/logs/prism.log" || fail "no send event"
grep -q "S02PacketChat injected" "$W/home/logs/prism.log" && fail "receive() should not raise an event"
echo "ok  receive/send events, cancel, replace; server side left alone"
touch "$W/net/close"
wait_for "$W/home/logs/prism.log" "disconnect event"
echo "ok  disconnect event"
touch "$W/net/connect"   # leave a connection open for the re-inject check at the end
wait_for "$W/out.txt" "GAME connected"

# hot reload: replace the jar with a new version (different properties -> different file)
mkdir -p "$W/p2"; (cd "$W/p2" && unzip -q "$W/home/plugins/example.jar" && sed -i 's/version=1.0.0/version=1.0.1/' plugin.properties && jar cf "$W/example2.jar" .)
cp "$W/example2.jar" "$W/home/plugins/example.jar"
wait_for "$W/home/logs/prism.log" "enabled Example 1.0.1"
wait_for "$W/out.txt" "§fReloaded Example"
grep -q "disabled Example 1.0.0" "$W/home/logs/prism.log" || fail "old version not disabled"
echo "ok  hot reload"

# a broken plugin must not break the others
echo "not a jar" > "$W/home/plugins/broken.jar"
wait_for "$W/out.txt" "broken.jar failed to load"
echo "ok  broken plugin reported"
rm "$W/home/plugins/broken.jar"

rm "$W/home/plugins/example.jar" "$W/home/plugins/hooktest.jar"
wait_for "$W/home/logs/prism.log" "disabled Example 1.0.1"
echo "ok  plugin removed"

touch "$W/home/bin/unload-$GAME"
wait_for "$W/home/logs/agent.log" "unloaded"
grep -q "stopped" "$W/home/logs/prism.log" 2>/dev/null || grep -q "stopped" "$W/home/logs/prism.old.log" || fail "core not stopped"
kill -0 $GAME || fail "game died after unload"
grep -q "hooks removed" "$W/home/logs/prism.log" "$W/home/logs/prism.old.log" 2>/dev/null || fail "hooks not removed"
touch "$W/keys"
wait_for "$W/out.txt" "GAME KEY 30 true"
touch "$W/net/traffic"
wait_for "$W/out.txt" "GAME RECV CLIENTBOUND S02PacketChat secret"
echo "ok  unloaded, hooks and packet handler removed (game back to normal), game still running"

# inject again after unload (the app's Inject button after Unload)
cp "$ROOT/java/example-plugin/build/libs/example-plugin.jar" "$W/home/plugins/example.jar"
(cd "$W/hooktest" && jar cf "$W/home/plugins/hooktest.jar" .)
: > "$W/out.txt.mark"; LINES=$(wc -l < "$W/out.txt")
jcmd $GAME JVMTI.agent_load "$ROOT/target/debug/libprism_agent.so" "$W/home|$W/home/bin/prism-core.jar" >/dev/null
for _ in $(seq 1 100); do tail -n +$((LINES+1)) "$W/out.txt" | grep -q "Loaded Example" && break; sleep 0.1; done
tail -n +$((LINES+1)) "$W/out.txt" | grep -q "Loaded Example" || fail "re-inject didn't load"
for _ in $(seq 1 50); do grep -q "hooks placed: 6" "$W/home/logs/prism.log" && break; sleep 0.1; done
grep -q "hooks placed: 6" "$W/home/logs/prism.log" || fail "hooks not placed after re-inject"
wait_for "$W/home/logs/prism.log" "enabled, connected=true server=embedded"   # attached to the open connection
echo "ok  injected again, hooks placed again"
echo "ALL PASSED"
