# Cirrus beside Farfield, test of 2026-11-21

Setup: Cirrus 1.4.2 (JevenDev, All Rights Reserved, public source) with Architectury 13.0.12 and YACL 3.8.2, NeoForge 1.21.1 dev client (`neoforge/run`), vanilla renderer (no Sodium), no shaders, render distance 8. Create: Sky jars moved aside for the run and restored after.

## Depth order (measured)

- Clouds in front of a LOD draw over it, with the same pattern as the LODs-off frame taken at the same tick.
- Clouds behind a LOD peak are masked by its silhouette and reappear once LODs are off.
- Level colors on the same view confirm the terrain under the clouds is LOD.

## Range gap (measured)

- Cirrus clouds stop at its `renderDistanceChunks`: 64 by default, 128 max, which is 2048 blocks. Bare sky shows above the far LOD horizon.
- Only Cirrus can close it, by syncing its distance to `FarfieldClientApi` `viewDistance()`, as it already does for Distant Horizons.

## Test recipe

- Lower the cloud layer with `lowerLayerHeightOffset` (-128 to 128) in `config/cirrus-client.json`. Its per-dimension `cloud_height` rule comes only from a resource pack (`assets/<ns>/cirrus/cloud_dimensions/<dim>.json`), not from that config.
- Used: layer near y 112, camera at `tp 733.5 145 -470.5 -126 25` (looking down) in the test world.
- Inbox file for the pair: `screenshot`, `command farfield render off`, `wait seconds 3`, `screenshot`, `command farfield render on`, then the level colors toggle, each reply read in the log.
- Cleanup: restore `cirrus-client.json` and `options.txt` from their `.orig` copies, put the jars back from `_disabled`, check `ls mods`, sweep the JVMs.

## Not tested

Fabric, Sodium, 1.21.11, 26.x, night.
