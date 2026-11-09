---
name: farfield-screenshot-chunk-map
description: Use when screenshot shows dark squares; map them to chunks.
category: minecraft-modding
---

# Farfield screenshot chunk map

Project the dark pixels of a no-shader capture onto the sea plane, then compare the chunks they hit with the chunks in the world's region files. Run it on a capture from a known pose, before any new client run.

1. Set `CAM`, `PITCH`, `FOV` and `SEA` at the top of `scripts/squaremap.py` to the capture's camera position, pitch, vertical FOV and water height.
2. Run `python scripts/squaremap.py CAPTURE WORLD_DIR OUT_PNG`, with Pillow installed. WORLD_DIR is a copy of the world, never the live save; the script reads its `region` folder.
3. Read `chunks seen N, dark K, in region files M, dark and in region files J`. A chunk counts as dark when at least half of its sampled pixels are below luminance 25.
4. Read the last line, `level-1 cells`: the 4x4-chunk cells (level 1 sections) that hold dark chunks with no region file. A dark chunk absent from the region files sits on preview tiles, not on stored real chunks, so a real-chunk data hole does not explain it.

A dark cell only shows where pixels are dark in one capture. Confirm the pose and the level before naming a cause.

Script: `scripts/squaremap.py`.