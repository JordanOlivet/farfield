---
name: farfield-noise-preview
description: Use when editing the noise preview or /farfield preview.
category: minecraft-modding
---

# Farfield noise preview

The preview draws terrain not generated yet as LOD tiles, built from the noise router in the integrated server. Change it at tile level, never per column.

- Single player only: the integrated server builds the tiles. `/farfield preview on|off` and the `noise_preview` setting toggle it. Log lines: `Noise preview of <dim>: building ...` then `every tile within 4096 blocks is built`.
- Tiles are 512 x 512 blocks, 129 x 129 height samples every 4 blocks, bilinear per block, shared at tile edges so neighbors agree on the border. Keep the shared edge samples when changing the step.
- Far tiles compute heights every 8 blocks (beyond 1,024 blocks) or 16 (beyond 2,048) and fill the rest bilinearly (`fillBetweenSamples`); the grid is stored (format 5) and a tile is rebuilt finer when a player comes closer (`NoisePreview.grid`). Readers never see the grid: keep it that way.
- Trees (ADR-040): `PreviewTrees` reads each biome's tree features and density once and logs `Noise preview: <biome> grows N trees per chunk of K kinds`; check those lines first when trees look wrong. Tree kinds are baked into the tiles: after changing a tree rule, clear `<save>/farfield/preview` in the dev world, or bump the tile format, to see it.
- Storage: zstd files `preview/t.<x>.<z>.ffp` in the world save (`PreviewStore`). When the format changes, delete the old folder at open (the ADR-038 `L8` folder was removed that way). Never read old-format tiles.
- Cost: about 0.8 s per near tile and 0.25 s per far one on 4 threads (Auto on 16 logical processors), about 2 min for the 4096-block radius, nearest tiles first. Thread count: `preview_threads` (Auto by default). Judge a change from the log timings, then relaunch: the second open must build nothing and still print `every tile ... is built`.
- Real chunks (ADR-039): a preview column beside a loaded real chunk goes down to that chunk's real ground (`PreviewVoxels`), so a preview shore beside real sea drops to the sea floor. Real columns carry two mask materials: opaque up to the real ground, translucent (`farfield:preview_mask_fluid`) up to the real fluid's top. Preview water thus shows no wall toward real water, while preview land shows its cliff. Real ground and fluid top are read per chunk from the real level-4 voxels (`PreviewLayer`). See `farfield-lod-renderer` for the renderer side.
- Verify in the Fabric dev client at a spot never generated (for example 6000, 0): from above, then low at y 120 toward real terrain. Procedure in `farfield-dev-client-check`. NeoForge and Sodium are not verified yet.
- Plants and villages (ADR-041): `PreviewTrees` also follows huge mushrooms, bamboo, block columns (cactus), single blocks and random patches. Blocks without a collision shape (grass, flowers, sugar cane) are left out, as the LODs leave them out. Each kind needs its ground (grass or snow, mycelium, sand).
- Villages: `PreviewVillages` takes the village structure sets, asks each placement for its candidate chunk per region, and keeps it when a village of the set accepts the biome. Houses are approximate boxes, not the game's pieces. Placement on land: try the candidate center first, then retry around it many times. A center in a lake left 2 houses on an islet until the tries were raised to spread along the shore. Judge a village by its houses, not by its center.
- Known and not caused by the preview: a darker vanilla water rectangle at the render distance also shows with the preview off. Log it as a separate defect.
