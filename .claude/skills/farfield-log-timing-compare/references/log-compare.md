# Compare preview timings across saved logs

Run in Git Bash from the repository root. Each output line: log file, total tiles, weighted ms per tile.

```bash
for dir in fabric/runs/client/logs neoforge/run/logs "/c/Users/Lakio/curseforge/minecraft/Instances/Cobblemon Custom Modpack/logs"; do
  for f in "$dir"/*.log.gz "$dir"/latest.log; do
    [ -f "$f" ] || continue
    case "$f" in *.gz) reader=zcat;; *) reader=cat;; esac
    "$reader" "$f" | grep -a "tiles built in the last minute" | sed -E 's/.*: ([0-9]+) tiles built in the last minute \(([0-9]+) ms each.*/\1 \2/' | awk -v name="$(basename "$f")" '{t+=$1; w+=$1*$2} END {if (t) printf "%s %d tiles %.0f ms/tile\n", name, t, w/t}'
  done
done
```

The loop skips missing files and prints nothing for a log with no preview line. Add the thread count from `Noise preview threads: N` in the same file before comparing.
