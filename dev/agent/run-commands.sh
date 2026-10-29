#!/usr/bin/env bash
# Runs one agent command file in a dev client (ADR-095, ADR-097), from Git Bash.
#
#   dev/agent/run-commands.sh <run dir> <command file> [timeout in seconds, default 1500]
#
# Start the client first (runClient or runPackClient, in the background); this
# waits for its agent inbox, drops the file into it, then waits for the reply
# to `quit` in <run dir>/farfield-agent/outbox.jsonl.
#
# A crash or a mod loading failure leaves the game open on an error screen
# (FML's, or the game's own), so the client would never quit: on a crash
# report in the game log, or at the timeout, this closes the game JVMs that
# this checkout started since the script began, and exits non-zero.
#
# Exit codes: 0 quit answered, 2 the game crashed, 3 timeout.
set -u

RUN=$1
CMD=$2
TIMEOUT=${3:-1500}
LOG="$RUN/logs/latest.log"
INBOX="$RUN/farfield-agent/inbox"
OUTBOX="$RUN/farfield-agent/outbox.jsonl"
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd -W)
start=$(date +%s)

# The game log of this run, not the one an earlier run left.
fresh_log() {
    [ -f "$LOG" ] && [ "$(stat -c %Y "$LOG")" -ge "$start" ]
}

crashed() {
    fresh_log && grep -aqE "Crash report saved to|---- Minecraft Crash Report ----" "$LOG"
}

close_game() {
    powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$HERE/close-game.ps1")" \
        -Root "$ROOT" -Since "$start"
}

stop_on_crash() {
    if crashed; then
        echo "game crashed after $(( $(date +%s) - start )) s"
        grep -a -m1 "Crash report saved to" "$LOG" | sed 's/.*Crash report saved to /crash report: /'
        report=$(ls -t "$RUN"/crash-reports/*.txt 2>/dev/null | head -1)
        [ -n "$report" ] && grep -a -m3 -E "Failure message:|^Description:" "$report"
        close_game
        exit 2
    fi
}

stop_on_timeout() {
    if [ $(( $(date +%s) - start )) -gt "$TIMEOUT" ]; then
        echo "$1 after $TIMEOUT s"
        close_game
        exit 3
    fi
}

# The test flights of dev/agent/flights/ for `replay` (ADR-099); a flight the
# run dir already has (the user's, recorded in game) is left as it is.
mkdir -p "$RUN/farfield/flights"
for flight in "$HERE"/flights/*.flight; do
    [ -e "$flight" ] && [ ! -e "$RUN/farfield/flights/$(basename "$flight")" ] && cp "$flight" "$RUN/farfield/flights/"
done

# Opening the inbox deletes the files already there: drop ours only after it.
until fresh_log && grep -aq "Agent inbox on" "$LOG"; do
    stop_on_crash
    stop_on_timeout "no agent inbox"
    sleep 2
done
echo "inbox on after $(( $(date +%s) - start )) s"
name=$(date +%Y%m%d-%H%M%S)
cp "$CMD" "$INBOX/$name.tmp" && mv "$INBOX/$name.tmp" "$INBOX/$name.cmd"

until grep -q '"cmd":"quit"' "$OUTBOX" 2>/dev/null; do
    stop_on_crash
    stop_on_timeout "no reply to quit"
    sleep 2
done
echo "quit answered after $(( $(date +%s) - start )) s"
