# Border-chunk reproduction on the dev server

Run from the repo root in Git Bash. The server reads console commands from stdin.

```bash
L=/c/Users/Lakio/AppData/Local/Temp/claude/<session-scratch>
(sleep 45; echo "forceload add 3000 3000 3100 3100"; sleep 3; echo "forceload remove 3000 3000 3100 3100"; sleep 2; echo "forceload add -3000 3000 -2900 3100"; sleep 1; echo "forceload remove -3000 3000 -2900 3100"; sleep 70; echo "stop") | ./gradlew.bat :neoforge:runServer --no-daemon --console plain > $L/neo-server.log 2>&1
grep -aE "LOD pipeline|LOD store|Marked|Unmarked|Exception|ERROR" $L/neo-server.log | grep -av "^\s*at " | tail -8
```

Forceload then release at once pushes new chunks into the border state. Expected output:

- `Marked 49 chunks` / `Unmarked 49 chunks` pairs.
- A minute line with `N snapshots in the last minute; ... M of border chunks`, with M > 0.
- The close line with `0 not snapshotted`.

If M is 0, the border path did not run: the test proves nothing about holes.

Hole count on a copy of the store: copy `neoforge/run/world/region` and `neoforge/run/world/farfield` into an empty scratch directory, then run `./gradlew.bat :core:storeHoles --no-daemon -q --args="<Windows path of the copy>"`.
