---
name: minecraft-vanilla-source-lookup
description: Use when vanilla Minecraft API source or moved classes.
category: minecraft-modding
---

# Look up vanilla Minecraft source before guessing an API

Read the NeoForm-merged vanilla source instead of guessing a method, field, or option name. For any other Minecraft version, read that version's source, never the current one's.

1. Find the merged jar: `ls ~/.gradle/caches/neoformruntime/intermediate_results/mergeWithSources_*_output.jar`.
2. Print one class: `unzip -p <jar> net/minecraft/client/Options.java`, then narrow with `grep -n <name>` or `sed -n <a>,<b>p`.
3. Check the vanilla default, range and label of an option in the same file before mirroring it in a mod setting.
4. When a stored value disagrees with the game (light, block state, biome), read how vanilla reads it before adding a default. Vanilla keeps no data for some sections and reads a neighbour instead: sky light of a section with none is the bottom layer of the nearest lit section above (`SkyLightSectionStorage.getLightValue`). Mirror that read, not the file's absence.
5. To see how a world places its features (trees, rocks, surface), read `ChunkGenerator.applyBiomeDecoration`: structures first, then per biome and per `GenerationStep`, each `PlacedFeature.placeWithBiomeCheck`, writing through `WorldGenRegion` (`ensureCanWrite` limits writes to the region). Grep those names in the merged jar before writing placement code.
6. Members that are not public (a rule context, its updates, a rule's `tryApply`): their names change per mapping, so find them by the shape of their parameters in the merged jar, not by name. The lookup must hold on every mapping, NeoForge and Fabric both; check it on each loader's build before trusting it.
7. A class that no longer resolves in a newer version has moved, not vanished. List the imports of the Java sources that fail against the target version: `python -I scripts/missing_imports.py <java dir> <target jar or source dir>`. Then `find` the simple name in the target tree. Known 26.x moves are in `references/26x-package-moves.md`.
8. From 26.1 Minecraft ships unobfuscated (Mojang's names, no mappings), so the names in the jar are the names to use.
9. Several merged jars exist, one per Minecraft version and loader: name the version first with `unzip -p <jar> version.json | grep -o '"id": "[^"]*"'`, then find the class with `unzip -l <jar> | grep <Simple>`. To see how a signature changed, loop the same `unzip -p` over every jar and grep the line: a parameter can keep its name and change meaning between versions (`proceed(boolean confirmed, ...)` became `proceed(final boolean backup, ...)` on 26.2).

Example: `grep -n biomeBlendRadius` on `net/minecraft/client/Options.java` shows the option field, its range (0 to 7) and its default (2).