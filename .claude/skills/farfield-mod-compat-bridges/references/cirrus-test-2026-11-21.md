# Cirrus beside Farfield, test of 2026-11-21

Setup: Cirrus 1.4.2 (JevenDev, All Rights Reserved, public source) with Architectury 13.0.12 and YACL 3.8.2, NeoForge 1.21.1 dev client (`neoforge/run`), vanilla renderer (no Sodium), no shaders, render distance 8. Create: Sky jars moved aside for the run and restored after.

## Depth order

The 2026-11-21 reading was wrong; the 2026-11-22 probe and the user's own look replace it.

- 2026-11-21 (screenshot pair only): clouds behind a LOD peak looked masked by its silhouette. Not reproduced.
- 2026-11-22, probe (TEMP, removed): at one frame every 5 s, the main depth and colour read at 48 x 27 points after the LODs, after Farfield's depth clear, right before and right after the game's `renderClouds` call (Cirrus replaces it at its head). After the LODs 594 of 1,296 points held depth, after the clear 0, before the clouds 85 (vanilla terrain only). Where its clouds fell on LODs, Cirrus changed their pixels: 135 of 454 and 161 of 509 LOD points.
- Cirrus's cloud shader reads no depth (`assets/cirrus/shaders/core/cirrus_clouds.fsh`); it draws at the vanilla cloud stage, after the game's terrain.
- The user, in a visible client the same day: clouds draw over the LODs; a capture shows cloud bands crossing a tall distant peak without a break.
- So clouds draw over every LOD, in front of it or behind it, as `docs/api/client-api.md` says. A view where the cloud field ends above the far terrain looks like masking in a pair; read the probe, not the pair.

## Range gap (measured)

- Cirrus clouds stop at its `renderDistanceChunks`: 64 by default, 128 max, which is 2048 blocks. Bare sky shows above the far LOD horizon.
- Only Cirrus can close it, by syncing its distance to `FarfieldClientApi` `viewDistance()`, as it already does for Distant Horizons.

## Test recipe

- Jars (NeoForge 1.21.1, Modrinth): `cirrus-neoforge-1.21.1-1.4.2.jar`, `architectury-13.0.12-neoforge.jar`, `yet_another_config_lib_v3-3.8.2+1.21.1-neoforge.jar`; check their sha512 against the Modrinth API.
- Lower the cloud layer with `lowerLayerHeightOffset` (-128 to 128) in `config/cirrus-client.json`. A file holding only that key was not shown to apply (the clouds looked at their usual height); edit the full file Cirrus generates. Its per-dimension `cloud_height` rule comes only from a resource pack (`assets/<ns>/cirrus/cloud_dimensions/<dim>.json`), not from that config.
- Used: layer near y 112, camera at `tp 733.5 145 -470.5 -126 25` (looking down) in the test world.
- Inbox file for the pair: `screenshot`, `command farfield render off`, `wait seconds 3`, `screenshot`, `command farfield render on`, then the level colors toggle, each reply read in the log.
- Cleanup: restore `cirrus-client.json` and `options.txt` from their `.orig` copies, put the jars back from `_disabled`, check `ls mods`, sweep the JVMs.

## Not tested

Fabric, Sodium, 1.21.11, 26.x, night.
