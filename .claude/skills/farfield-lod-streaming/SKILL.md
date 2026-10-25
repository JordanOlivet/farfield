---
name: farfield-lod-streaming
description: Use when editing Farfield LOD streaming or StreamClient.
category: minecraft-modding
---

# Farfield LOD streaming (ADR-068)

A client joined to a server with Farfield draws the server's LODs, mirrored locally, and never builds its own. Keep these invariants when touching the streaming path.

1. **Gate on the join, not the session.** `StreamClient.streaming(mc)` is true from the join on, before the stream session opens, so client mode never starts. It is false in single player and on a server without the channel (`Services.NETWORK.serverStreams()`), or once the server declined.
2. **Tick order: stream first.** On both loaders `StreamClient.tick` is registered before `LodClient.tick`. Reversing it lets client mode build for one tick.
3. **Provider precedence.** `LodWorldProvider` returns, in order: the integrated server's store (single player, LAN host), the streamed mirror, then the client's own store.
4. **Mirror first, cache second.** The renderer asks the stream for a section it lacks (`needsFetch`, `want`). A stale local copy stays drawn meanwhile. `sectionExists` reads the server's region index, not the local cache.
5. **Index arrival reselects.** When region indexes arrive the stream generation changes; the renderer must mark the selection dirty so newly listed sections get requested.
6. **Check with a dev server and a dev client.** A run on 2026-10-25 (Fabric, the test world copied into `fabric/runs/server/farfield-test`, `level-name=farfield-test`, `online-mode=false`, client `-PquickPlayServer=localhost:25565 -PclientUsername=Dev`) streamed 10,188 sections at the 1,024 KB/s cap, view settled at 24.6 s. Read: server `LOD streaming to <player>`, the 10 s `LOD streaming: ... KB sent` lines; client `LOD streaming from the server`, `... KB received ... in flight, ... wanted`, and the mirror's `closed: N sections held`. A tiny server world (49 chunks) proves the protocol only: use the test world for numbers. NeoForge and two clients are not checked yet.

Renderer details: `farfield-lod-renderer`. Launch and log reading: `farfield-dev-client-check`.
