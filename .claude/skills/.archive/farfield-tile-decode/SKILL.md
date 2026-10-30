---
name: farfield-tile-decode
description: Use when decoding a Farfield .ffp preview tile offline.
category: minecraft-modding
---

# Farfield tile decode

Decode a stored tile with the `core` classes instead of judging the screen: the cell letters and the legend show the real top and filler of each cell.

1. Build `core` (`.\gradlew.bat :core:classes --no-daemon`) so `core/build/classes/java/main` exists.
2. Run a single-file Java program kept in a scratch folder, never committed, with `core/build/classes/java/main` and `aircompressor-0.27.jar` (Gradle cache `modules-2`) on the class path. Arguments: the tile file, the store's `block_states.txt`, the cell box `x0 z0 x1 z1`, then the tile's `x z`.
3. Read the output: one letter per cell, then `<letter> top <block> filler <block> x<count>` lines.
4. A pattern repeated at the first row and column of every chunk points at the biome lookup or the stand-in, not at the ground. Compare the letters with the real chunk's tops before blaming the renderer.
