#!/usr/bin/env bash
# A/B series with streaming (ADR-111), from Git Bash: per mode, a fresh Fabric
# dev server world, the Fabric dev client joins it and runs a command file.
#
#   dev/agent/stream-series.sh <label> <command file> <mode>...
#
# Modes as in series.sh (name=JVM options for the client). The command file
# must not wait for a stable view before the join settles: see
# scenarios/band-sea-server.cmd. Results in build/agent-series/stream-<label>.txt.
# The server's `online-mode` must be false in fabric/runs/server/server.properties.
set -u

LABEL=$1
CMD=$2
shift 2
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CMD=$(cd "$(dirname "$CMD")" && pwd)/$(basename "$CMD")
OUTDIR=$ROOT/build/agent-series
OUT=$OUTDIR/stream-$LABEL.txt
mkdir -p "$OUTDIR"
cd "$ROOT"
S=fabric/runs/server
C=fabric/runs/client
for mode in "$@"; do
    name=${mode%%=*}
    opts=${mode#*=}
    # A world never generated: the template's level.dat alone (same seed, no region).
    rm -rf $S/fresh-s1 && mkdir -p $S/fresh-s1 && cp dev/server/world/level.dat $S/fresh-s1/
    rm -rf $C/farfield/servers $C/farfield-agent/outbox.jsonl
    cmds=$OUTDIR/stream-$LABEL-console-$name.txt
    : > "$cmds"
    start=$(date +%s)
    (tail -f "$cmds" | ./gradlew.bat :fabric:runServer --no-daemon --console plain \
        > "$OUTDIR/stream-$LABEL-server-$name.log" 2>&1) &
    until [ -f $S/logs/latest.log ] && [ "$(stat -c %Y $S/logs/latest.log)" -ge "$start" ] \
            && grep -aq "Done (" $S/logs/latest.log; do
        sleep 2
        [ $(( $(date +%s) - start )) -gt 300 ] && { echo "mode $name: server never ready" >> "$OUT"; break; }
    done
    JAVA_TOOL_OPTIONS="-Dfarfield.frames.dumps=400 $opts" ./gradlew.bat :fabric:runClient \
        -PquickPlayServer=localhost:25565 -PclientUsername=Dev -PagentWindow=minimized --no-daemon --console plain \
        > "$OUTDIR/stream-$LABEL-client-$name.log" 2>&1 &
    client=$!
    sleep 5
    "$HERE/run-commands.sh" $C "$CMD" 900 > "$OUTDIR/stream-$LABEL-run-$name.log" 2>&1
    code=$?
    wait $client
    echo stop >> "$cmds"
    until grep -aq "closed:" $S/logs/latest.log; do
        sleep 2
        [ $(( $(date +%s) - start )) -gt 1200 ] && break
    done
    sleep 8
    # The tail feeding the server's stdin outlives it.
    ps -ef | grep "tail -f $cmds" | grep -v grep | awk '{print $2}' | xargs -r kill
    sleep 2
    frames=$(ls -td $C/farfield/frames/*/ | head -1)
    streamed=$(grep -ac "LOD streaming from the server" $C/logs/latest.log)
    echo "mode $name exit $code streaming lines $streamed | $(python "$HERE/score-flashes.py" "${frames%/}")" >> "$OUT"
    cp $C/logs/latest.log "$OUTDIR/stream-$LABEL-$name.log"
done
echo done >> "$OUT"
