# Last spike run (2026-10-28)

Tile 8 7 over biomesoplenty:overgrown_greens at 4416, 113, 3808: built in 2749 ms; file 15 KB, 1222 feature columns, 1334 runs; features: stand-in chunks 854 ms, raw_generation 63 ms, lakes 20 ms.

The spike decorates a grid of chunks in a fake world, then compares with the same chunks generated for real. Mods and world are the user's worldgen packs (Biomes O' Plenty, Terralith, Lithostitched).

# Surface spike (ADR-101, 2026-10-30)

`/farfield spike surface <biome> [side]` on the dev server with the worldgen jars in `mods/`: the generator's surface rules on a stand-in (one column per 4-block cell), and the old guess, against the real top of chunks generated to their surface on their real heights. Report lines `Surface spike: tops right, ...`, ms per stand-in chunk, the five commonest mistakes. 2026-10-30: ruled column 98.2 to 100 % right over 8 biomes, the guess 0 to 77 %. `ChunkAccess.getHeight` is the top block itself, not the free block above (a first run compared the block under the top). To look at a spike world in a client: copy it to `saves/`, set `allowCommands` to 1 in its `level.dat` (a dedicated server's world has it off), `-PquickPlay=<name>`, `time set noon`.
