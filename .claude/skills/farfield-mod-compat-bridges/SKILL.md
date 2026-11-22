---
name: farfield-mod-compat-bridges
description: Use when Cirrus, cloud mods or FarfieldClientApi come up.
category: minecraft-modding
---

# Farfield compatibility bridges

Before coding a bridge, find what the other mod reads for distance and depth, then run that mod alone at the distance a player would set. The pair with and without Farfield tells you whether a gap is ours or theirs.

- Settle depth order at the GL level, not from a screenshot pair alone. Farfield clears the main depth after the LODs (`GlCompat.clearMainDepth`), so a mod drawing at the game's cloud stage draws over every LOD, clouds behind a LOD peak included; `docs/api/client-api.md` is right. A 2026-11-21 on/off pair read Cirrus clouds as masked behind a LOD peak; a probe (main depth and colour read at a grid of points before and after the game's `renderClouds` call) disproved it on 2026-11-22: 0 of 1,296 points held LOD depth at the cloud call, Cirrus changed 135 of 454 LOD points, and the user saw clouds over the LODs. A pair is ambiguous: a cloud field that ends above the far terrain looks masked. Probe recipe and numbers: `references/cirrus-test-2026-11-21.md`.
- Cloud layer above the view: lower it through the cloud mod's own config so the camera can sit beside it. Recipe, positions and the Cirrus result: `references/cirrus-test-2026-11-21.md`.
- Read the other mod's contract from its own jar first (see `verify-external-contract-first`). Create: Sky findings and their limits are in `references/create-sky-test-2026-11-19.md`.
- Never modify a closed-source jar (All Rights Reserved), not even for a test. Put a separate test mod in the scratchpad that calls the Farfield API, and never commit it.
- The consumer side is the other author's code. Farfield only exposes data: the LOD depth texture, the inverse view-projection relative to the camera, the depth convention, the view distance in blocks, and an active flag. Farfield copies depth only when a consumer has declared itself. The exposed API is `FarfieldClientApi` (ADR-173, API version 1; the author-facing doc is `docs/api/client-api.md`): `requestLodDepth(modId)` once, then `lodFrame()` each frame, reading the depth texture, `inverseViewProjection`, `reverseZ()`, `zeroToOne()` and `viewDistance()`. Under a shader pack the frame is inactive, and the translucent LODs write no depth. Prove a bridge with a test mod in the scratchpad, and compare the same view with and without Farfield.
- Check what the announced distance does to the other mod's own volumes; a cap may be needed above its tuned range.
- `viewDistance()` is the extent of the geometry actually drawn, not the render distance setting (ADR-174). Before ADR-174 it announced about 33 million blocks on the first frame, with no LOD drawn. Do not reintroduce a setting-based value. The noise preview tiles count as LODs, so with the preview on at max setting the value legitimately grows to tens of millions of blocks; a consumer with a shorter range caps it on its side.
- Verify a bridge on the user's pack by grepping its client `latest.log` for the exact new line (`grep -a "LOD depth shared: view distance"`). Do not pipe through `head` before the filter: mod-load WARN lines bury the first matches and look like a missing line. Confirm the installed jar holds the new string before reading the log.
