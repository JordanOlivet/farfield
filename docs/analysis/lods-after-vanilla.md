# Drawing the LODs after the game's terrain (possible path, not scheduled)

Status: analysis only (2026-10-20). Not scheduled: no real usage feedback
yet, the current performance may be enough. Context: ADR-047 (GPU occlusion
culling built, measured, dropped; code on branch
`experiment/occlusion-culling`, commit `7fa2d7b`).

## Why occlusion culling did not pay

The LODs draw after the sky and before the game's terrain, with their own
depth range (8 to 8,192 blocks), then clear the depth so the game draws on
top; where the game draws, `lod.frag` discards (the hand-off). The game's
projection has its far plane near its render distance, so the two depths
cannot be compared as they are. The terrain that hides the horizon is the
game's near terrain, drawn after the LODs and outside their depth: the
culling pass only had far LOD terrain as occluders, and culled 2 to 7 % of
the draws for no GPU gain.

## What drawing after the game's terrain needs

1. **Injection point**: in `LevelRenderer.renderLevel`, after the game's
   opaque terrain layers (solid, cutout) and before its translucent layer
   (water, glass). The sky and the near terrain are drawn; the game's
   water blends over the LODs afterwards, in the right order.
2. **Own framebuffer**: the game's color texture with a depth texture of
   the LODs' own, so the game's depth (used later for its water, entities,
   particles) is left untouched.
3. **Depth conversion pass**, full screen, every frame (estimated 0.1 ms):
   read the game's depth texture (a texture, checked while building the
   culling pass), turn it into a distance with the game's projection, then
   into the LOD depth mapping of `lod.vert`, and write it as the LODs'
   starting depth. The hardware depth test then rejects every LOD fragment
   behind the game's terrain before its shader runs.
4. **Hand-off kept**: where the game draws, LOD fragments are still
   discarded (a coarse LOD must not show in front of the real terrain, or
   through leaves and glass), and sections the game draws entirely are still
   skipped (`gameDrawsAll`).
5. **Cases to handle**: "Fabulous" graphics (translucent layers in separate
   targets, composited by a post shader); Sodium (it rewrites section
   rendering; the call site should hold, to be checked); Iris with a shader
   pack (own pipeline, out of scope as today); the fog, read later in the
   frame.

## What it would bring

- Without any culling: the depth test against the game's terrain rejects
  every hidden LOD pixel before shading; only vertex work remains for them.
- With the culling of the experiment branch, fed with that depth: near
  hills would hide whole sections. Estimated 30 to 70 % of the sections in
  ground or mountain views (2 to 7 % measured without it); nearly nothing
  in views from above the sea.

## Cheaper alternative, not recommended

Keep the current order and build the depth pyramid from the game's depth
of the previous frame, reprojected. Fewer changes, but a section coming out
from behind a hill can be missing for a frame while the camera turns: the
kind of one-frame flicker already fixed elsewhere (E.7).

## Steps, if taken up

1. Move the injection and add the depth conversion; check the hand-off,
   one-frame flicker, water at the border and "Fabulous" on both loaders
   and with Sodium; measure (the per-pixel gain should already show).
2. Take the culling back from `experiment/occlusion-culling` with the
   converted depth as its source; measure in an amplified world.

About one working session each.
