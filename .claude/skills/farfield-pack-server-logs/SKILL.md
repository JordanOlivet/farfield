---
name: farfield-pack-server-logs
description: Use when reading docker logs or boot errors on the pack.
category: debugging
---

# Farfield pack server logs

Before calling a boot error Farfield's, find a stack frame that names it (`farfield` or `io.lakio`). A line with no such frame belongs to another mod, even when it sits in the same boot.

1. Pull the window and strip colour codes: `ssh <pack server host> "docker logs --since 25m <container> 2>&1" | sed -e 's/\x1b\[[0-9;]*m//g' > <scratch>/boot-now.txt`. The container's log starts at its last restart; an older boot needs `--since 30h --until 27h`. The host and container names are in the machine-local `farfield-deploy` skill.
2. Compare the same error across boots with `grep -a -c '<pattern>'` on each file. An error absent from earlier boots is new; one present in all of them is baseline.
3. Frames: `grep -a -m1 -A25 '<pattern>' <file>`, then drop `java`, `jdk`, `net.minecraft`, `net.neoforged`, `cpw`, `com.mojang`. Nothing left means no mod frame of ours.
4. Known pack noise, not Farfield (2026-11-16): `RuntimeDistCleaner ... invalid dist DEDICATED_SERVER` (120 lines through `BlockBehaviour`); a MineColonies NPE on `chicken_roost` animals (`AttributeSupplier`). Farfield's only warning, `Reference map 'farfield.refmap.json' could not be read`, is harmless on NeoForge.
5. A `Done (` line plus the `LOD store of ... opened` lines marks a finished boot.
6. In the bash tool, quote `===` in `echo` (`zsh:1: == not found`), or put the check in a scratchpad `.sh` run with `bash`.
