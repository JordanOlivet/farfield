# Test worlds

Every dev run dir starts from the same world, so any machine generates the
same terrain and coordinates mean the same place in progress logs, bug
reports and screenshots (ADR-042). Spike measurements use the reference
corpus instead (`docs/corpus.md`).

## `farfield-test` (the dev world)

| Field | Value |
|---|---|
| Seed | `-6818606149209278498` |
| Minecraft | 1.21.1 (DataVersion 3955), vanilla generation, structures on |
| World spawn | 48, 75, 32 (snowy, by an ice biome border) |
| Game mode | spectator for a new player (`GameType` 3), commands allowed |
| Time and weather | noon, `doDaylightCycle false`, `doWeatherCycle false` |
| Mobs | `doMobSpawning false`, difficulty easy |

Templates (only `level.dat`; chunks generate on first visit):

- `dev/client/saves/farfield-test/level.dat`: the singleplayer save that
  `-PquickPlay=farfield-test` opens, copied into each loader's client run dir.
- `dev/server/world/level.dat`: the same file as the `world` of every
  server run dir (both dev servers and `runVanillaServer`), with
  `level-seed` set to the same seed in `dev/server/server.properties`.

The template was made from the save first used for the dev checks
("Test Ice Border", same seed, 2026-10-16): player data, mod data packs and
loader keys removed, the rules above set. Run dirs set up before 2026-10-19
may hold older worlds with other seeds; delete a run dir's `world` (or
`saves/farfield-test`) to start again from the template, LOD store included.
A world that tests have edited (`/fill` pits) is no longer pristine: say so
when citing coordinates from it.

## Places worth a look

Coordinates on the overworld of this seed. Add a row whenever a check or a
report names a spot.

| Place | Coordinates | Why |
|---|---|---|
| Spawn | 48, 75, 32 | Snowy start, ice border |
| Nearest village | `/locate structure #minecraft:village` | Noise preview villages (E.11) |
| Temperate land | `/locate biome minecraft:plains` | Grass and leaf tints |
