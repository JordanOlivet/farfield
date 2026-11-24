#!/usr/bin/env bash
# Starts a production client (the launcher's layout, the shipped jars, no dev
# remapping) through portablemc with an offline name, and runs one Rehearsal
# command file in it. For the versions no pack instance exists for (1.20.1):
# Forge, NeoForge and Fabric installs are downloaded once into runs/pmc
# (gitignored), the user's own launcher is not touched.
#
#   dev/rehearsal/prod-client.sh <version> <run dir> <command file> [timeout s, default 600]
#
#   <version>   portablemc's: fabric:1.20.1, forge:1.20.1-47.4.29, neoforge:1.20.1-47.1.106
#   <run dir>   the game directory, prepared by the caller: mods/ (Farfield's jar
#               of output/, Rehearsal's jar of its output/, the mods under test),
#               config/ and an options.txt with the sound off (ADR-107)
#
# Environment:
#   FARFIELD_JAVA=<java.exe>     the JVM (Java 17 for 1.20.1); portablemc's own otherwise
#   FARFIELD_START=<args>        more arguments for portablemc's start (-s localhost joins a server)
#   FARFIELD_JVM_ARGS=<args>     more JVM arguments
#
# The game log is <run dir>/logs/latest.log, Rehearsal's answers and captures
# are in <run dir>/rehearsal. Needs uvx (uv) on the PATH.
set -u
if [ $# -lt 3 ]; then
    sed -n '2,20p' "$0"
    exit 2
fi
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
spec=$1
run=$2
cmd=$3
timeout=${4:-600}
if ! grep -q '^soundCategory_master:0.0' "$run/options.txt" 2>/dev/null; then
    echo "no options.txt with soundCategory_master:0.0 in $run (ADR-107)" >&2
    exit 2
fi
jvm_args="-Xmx4G -Drehearsal=true -Drehearsal.window=minimized"
jvm_args="$jvm_args -Drehearsal.extensions=io.lakio.farfield.client.FarfieldRehearsal -Dfarfield.perfLogs=true"
jvm_args="$jvm_args ${FARFIELD_JVM_ARGS:-}"
launch=(uvx --from "portablemc<5" portablemc --main-dir "$(cygpath -w "$ROOT/runs/pmc")"
        --work-dir "$(cygpath -w "$run")" start "$spec" ${FARFIELD_START:-} -u Dev)
if [ -n "${FARFIELD_JAVA:-}" ]; then
    launch+=(--jvm "$FARFIELD_JAVA")
fi
"${launch[@]}" --jvm-args "$jvm_args" > "$run/launch.log" 2>&1 &
launcher=$!
"$HERE/run-commands.sh" "$run" "$cmd" "$timeout"
status=$?
if [ $status -ne 0 ]; then
    # A run that never answered (a failed join, a crash screen): close the window it left.
    powershell -NoProfile -Command "Get-Process java -ErrorAction SilentlyContinue | Where-Object { \$_.MainWindowTitle -like 'Minecraft*' } | ForEach-Object { [void]\$_.CloseMainWindow() }"
fi
wait $launcher 2>/dev/null
exit $status
