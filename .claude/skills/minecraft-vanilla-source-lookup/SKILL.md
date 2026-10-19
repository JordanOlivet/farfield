---
name: minecraft-vanilla-source-lookup
description: Use when checking vanilla Minecraft source or API names.
category: minecraft-modding
---

# Look up vanilla Minecraft source before guessing an API

Read the NeoForm-merged vanilla source instead of guessing a method, field, or option name.

1. Find the merged jar: `ls ~/.gradle/caches/neoformruntime/intermediate_results/mergeWithSources_*_output.jar`.
2. Print one class: `unzip -p <jar> net/minecraft/client/Options.java`, then narrow with `grep -n <name>` or `sed -n <a>,<b>p`.
3. Check the vanilla default, range and label of an option in the same file before mirroring it in a mod setting.

Example: `grep -n biomeBlendRadius` on `net/minecraft/client/Options.java` shows the option field, its range (0 to 7) and its default (2).
