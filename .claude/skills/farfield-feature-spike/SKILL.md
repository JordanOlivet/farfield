---
name: farfield-feature-spike
description: Use when a feature spike, mod trees, or snowy-forest trees miss in the preview.
category: minecraft-modding
---

# Farfield feature spike

Judge a feature in the preview by decorating the same chunks the real generator decorates, and compare the counts. Do not judge a biome by eye in a client first.

1. Copy the worldgen mods (the ones adding biomes or features) from the user's server into `neoforge/run/mods`, and use a fresh world so the mods generate their biomes.
2. Run the spike on the dev server and read its `Feature spike: tile ...` line: build time, feature columns, runs, failed features.
3. Compare with the same chunks generated for real by the game, feature by feature.
4. Afterwards move the jars back to `neoforge/run/mods-worldgen/` and restore `neoforge/run/server.properties` from its backup. A spike must leave the dev server as it was.

Trees missing in a cold biome: check the ground under the features before judging the feature. The preview's stand-in puts the tile's top block where the features look; a snow block lets no sapling survive, so every sapling filter fails and the forest comes out bare. Snow over dirt is snowy grass in the stand-in since tile format 13 (`PreviewFeatures.soil`); snow over snow stays, as on peaks. Before blaming the preview, confirm the LOD holds the trees (`farfield-mirror-probe`, step 9).

Run details and the last result: see `references/spike-run.md`.
