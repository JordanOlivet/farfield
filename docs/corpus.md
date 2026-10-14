# Reference world corpus

The worlds every spike measures against (plan item A.8). They live outside the
repository, in `C:\Users\Lakio\farfield-corpus\<name>\` (copies of
`level.dat` and the region folders only; the source saves are never touched).
To rebuild the corpus on another machine, use any worlds of the same kinds;
numbers in progress logs always name the corpus world they come from.

| Name | Source | Minecraft | Content | Chunks (overworld) | Region files |
|---|---|---|---|---|---|
| `server-pregen` | Neo-Voxy test server (`dev/server-neoforge/world`) | 1.21.1 (DataVersion 3955), vanilla generation | Mostly Chunky pre-generation, little building | 35,793 | 264 MB |
| `modded-large` | "Gotta Craft Em All - Reforged (1)", world `mctest` | 1.21.1, NeoForge, ~400 mods | Large modded world, many modded blocks; also Nether (2,601 chunks) and End (3,840) | 67,386 | 760 MB |
| `modded-played` | "Gotta Craft Em All - Reforged", world `New World` | 1.21.1, NeoForge, ~400 mods | Played survival world with builds | 33,502 | 293 MB |
| `main-install` | Vanilla launcher `.minecraft`, world `New World` | 1.21.1 | The user's main test world (Neo-Voxy testing) | 85,999 | 831 MB |
| `amplified` | Generated for the corpus: Fabric 1.21.1 server, `level-type=minecraft:amplified`, `level-seed=farfield-amplified`, Chunky radius 512 around 0,0 | 1.21.1, vanilla | Extreme terrain (cliffs, overhangs, caves) | 7,569 | 70 MB |
| `dh-baseline-1.20.1` | "Dragon Mine Z", world `Test` | 1.20.1 (DataVersion 3465), Forge, ~100 mods | Older chunk format; has a Distant Horizons database | 189,072 (includes partially generated chunks) | 795 MB |
| `server-spawn` | User's server (below): 6x6 regions around spawn (r.-4..1, r.-3..2) | 1.21.1, NeoForge, modded | Player builds, bases, modded structures | 36,864 | 437 MB |
| `server-dense` | User's server (below): 4x4 regions of the heaviest area (r.-17..-14, r.2..5) | 1.21.1, NeoForge, modded | Densest region files of the map | 16,384 | 220 MB |

## The user's server (very large world)

A copy of the user's public modded server, `D:\MC server save\minecraft-gotta-craft-em-all-reforged\data\world`
(modpack: CurseForge instance "Gotta Craft Em All - Reforged (1)", NeoForge
21.1.x, ~400 mods; the server ran Neo-Voxy with server-side streaming). It is
**read in place, never modified and not copied**, for full-scale runs
(import throughput, the "build LOD for an existing world" command, scaling);
`server-spawn` and `server-dense` above are the everyday subsets.

| Dimension | Regions | Chunks | Region files |
|---|---|---|---|
| Overworld (x -169..81, z -103..105 in regions; spawn -64, 48) | 2,092 | 1,926,613 | 16.7 GB |
| Nether | 33 | 19,060 | 117 MB |
| End | 65 | 49,282 | 156 MB |
| Modded dimensions (raid dens, compact machines) | | | 29 MB |

Average overworld region file 8.4 MB, heaviest 17.6 MB; far pre-generated
regions are around 0.9 MB.

Chunk counts come from the region headers (non-empty entries), so they include
chunks that are not fully generated; the S5 reader will report the status
distribution.

## Existing LOD databases (size baselines)

Measured on the original saves on 2026-10-14. The LOD databases are not
copied into the corpus.

| World | Mod | LOD database | Region files | Ratio |
|---|---|---|---|---|
| User's server, overworld | Voxy (Neo-Voxy, server-side) | 26.5 GB | 16.7 GB | 1.6x |
| `server-pregen` | Voxy (Neo-Voxy, RocksDB) | 408 MB | 253 MB | 1.6x |
| `modded-large` | Voxy (Neo-Voxy) | 720 MB | 725 MB | 1.0x |
| `modded-played` | Voxy (Neo-Voxy) | 169 MB | 280 MB | 0.6x |
| `main-install` | Voxy (Neo-Voxy) | 496 MB | 793 MB | 0.6x |
| `dh-baseline-1.20.1` | Distant Horizons (SQLite) | 637 MB | 759 MB | 0.8x |

Caveat: an LOD database may cover a slightly different area than the region
files (for example data received from a server, or chunks unloaded before
saving), so these ratios are indicative. The target of ADR-009 / spike S1 is
well below them: under 25 % of the region files.
