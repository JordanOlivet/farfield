#!/usr/bin/env bash
# A/B series of agent runs on a dev client in single player (ADR-111), from Git Bash.
#
#   dev/agent/series.sh <label> <runs> <command file> <mode>...
#
# Each mode is name=JVM options, e.g. `base=` and `fix=-Dfarfield.myFix=true`
# (a temporary switch, never committed). Each round runs every mode once, on
# the NeoForge dev client with -PfreshWorld (the farfield-test template, so
# every run flies over terrain never generated), the frame probe on with up to
# 400 dumps. One result line per run goes to build/agent-series/<label>.txt:
# the flash counts of score-flashes.py, the run's exit code, and the game log
# copied beside it. Compare medians over about 5 rounds, never one run.
#
# Other loaders or the pack: CLIENT_TASK=:neoforge:runPackClient RUN_DIR=neoforge/runs/pack.
set -u

LABEL=$1
ROUNDS=$2
CMD=$3
shift 3
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CMD=$(cd "$(dirname "$CMD")" && pwd)/$(basename "$CMD")
CLIENT_TASK=${CLIENT_TASK:-:neoforge:runClient}
RUN=${RUN_DIR:-neoforge/run}
OUTDIR=$ROOT/build/agent-series
OUT=$OUTDIR/$LABEL.txt
mkdir -p "$OUTDIR"
cd "$ROOT"
for i in $(seq 1 "$ROUNDS"); do
    for mode in "$@"; do
        name=${mode%%=*}
        opts=${mode#*=}
        rm -f "$RUN/saves/farfield-test/session.lock" "$RUN/farfield-agent/outbox.jsonl"
        JAVA_TOOL_OPTIONS="-Dfarfield.frames.dumps=400 $opts" ./gradlew.bat "$CLIENT_TASK" \
            -PquickPlay=farfield-test -PfreshWorld -PagentWindow=minimized --no-daemon --console plain \
            > "$OUTDIR/$LABEL-client.log" 2>&1 &
        sleep 5
        "$HERE/run-commands.sh" "$RUN" "$CMD" 900 > "$OUTDIR/$LABEL-run.log" 2>&1
        code=$?
        wait
        sleep 3
        frames=$(ls -td "$RUN"/farfield/frames/*/ | head -1)
        echo "round $i mode $name exit $code | $(python "$HERE/score-flashes.py" "${frames%/}")" >> "$OUT"
        cp "$RUN/logs/latest.log" "$OUTDIR/$LABEL-$i-$name.log"
    done
done
echo done >> "$OUT"
