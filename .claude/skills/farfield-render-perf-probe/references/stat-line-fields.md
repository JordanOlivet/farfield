# LOD renderer stat line fields

Source: `LodRenderer` performance log, enabled by `-Dfarfield.perfLogs=true`.

- `per frame X us on the render thread`: mean LOD frame time on the render thread.
- `on the GPU (depth pre-pass, opaque, plants, translucent)`: mean GPU time per pass.
- `N sections culled on the GPU, M on the CPU`: GPU culling share (ADR-146); `-Dfarfield.cpuCulling=true` forces the CPU path.
- `the longest LOD frame U us (blends, rewrites, uploads, colors, selection, wanted, commands, eviction, draw; K meshes uploaded, quads sent, blends)`: step breakdown of the worst frame since the last line.
- `steps per frame`: mean per step over all timed frames.

Baseline in flight, Fabric pack, 4K reduced, FOV 70, ZGC, 60 blocks/s (ADR-147 state): render thread 1.5 to 1.65 ms per frame, GPU about 3.5 ms, mesh upload mean about 0.25 ms per frame. Worst frames of 10 to 17 ms came from batched preview uploads (400 to 600 meshes in one frame), blends up to 8 ms, selection up to 14 ms, evictions 4 to 7 ms.
