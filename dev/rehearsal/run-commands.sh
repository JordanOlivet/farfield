#!/usr/bin/env bash
# Runs one command file in a test client through Rehearsal (ADR-122), from Git Bash:
# Rehearsal's scripts/run-commands.sh, with the test flights of dev/rehearsal/flights/
# copied into the run dir first (`replay`, ADR-099).
#
#   dev/rehearsal/run-commands.sh <run dir> <command file> [timeout in seconds, default 1500]
#
# The Rehearsal checkout is rehearsal.dir in local.properties.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
REHEARSAL=$(grep -E '^rehearsal\.dir=' "$ROOT/local.properties" 2>/dev/null | cut -d= -f2- | tr -d '\r')
if [ -z "$REHEARSAL" ] || [ ! -f "$REHEARSAL/scripts/run-commands.sh" ]; then
    echo "rehearsal.dir in local.properties must name a Rehearsal checkout" >&2
    exit 1
fi
REHEARSAL_FLIGHTS="$HERE/flights" exec "$REHEARSAL/scripts/run-commands.sh" "$@"
