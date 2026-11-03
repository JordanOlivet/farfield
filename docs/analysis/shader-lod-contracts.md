# Shader pack LOD contracts: Distant Horizons and Voxy

2026-11-03. Input for C.1 (ADR-012, ADR-113). What a shader pack ships to
draw LODs, as Iris 1.21.1 (DH) and the packs themselves (Voxy) define it,
and what Farfield needs to feed each.

Sources and their status:

- Distant Horizons: the Iris source, branch `1.21.1` (LGPL-3.0, may be
  read and reused): `compat/dh/DHCompat`, `DHCompatInternal`,
  `IrisLodRenderProgram`, `LodRendererEvents`,
  `pipeline/transform/transformer/DHTerrainTransformer`,
  `StandardMacros`, `IrisSamplers`, `CommonUniforms`, `MatrixUniforms`;
  and the Iris page
  <https://shaders.properties/current/reference/mod-support/distant_horizons/>.
- Voxy: its code is "All rights reserved. Do not redistribute"
  (`LICENSE.md` of MCRcortex/voxy). Not read (ADR-001). The contract below
  is what the packs consume: the six packs of the user's NeoForge instance
  (BSL 10.1.1, Complementary Reimagined and Unbound r5.9, MakeUp
  UltraFast 9.5e, Photon 1.3b, `photon-gcea`), and on GitHub Photon,
  Revelation, Alpha-Piscium, Shrimple, Solas.

All six packs of the user's instance ship both contracts.
`photon-gcea`'s LOD files are Photon 1.3b's; its changes are
`block.properties`, `item.properties`, `dimension.properties`, settings.

## Distant Horizons (in Iris)

### Programs

| Program | When | Notes |
|---|---|---|
| `dh_terrain` | before the vanilla terrain | opaque LODs |
| `dh_water` | before the vanilla water | translucent LODs, cull face off; falls back to `dh_terrain` |
| `dh_shadow` | in the shadow pass, before shadow terrain | all LODs, no shadow distance; `dhShadow.enabled` in `shaders.properties` |
| `dh_generic` | DH's generic objects (beacons, clouds) | not needed by Farfield |

All use the `compatibility` profile. Draw buffers come from each
program's own `DRAWBUFFERS`/`RENDERTARGETS` directive:
`pipeline.createDHFramebuffer(source, translucent)` builds the
framebuffer (flipped buffers after prepare, or after translucent for
water); it does not touch the DH API.

### Vertex inputs

Iris rewrites the pack's vertex shader (`DHTerrainTransformer`) and
prepends `_vert_init()` to its `main`. The real attributes:

| Attribute | Type | Content |
|---|---|---|
| `vPosition` | `uvec4` | xyz in blocks relative to the uniform `vec3 modelOffset`; `w` meta: bits 0-7 light (`block << 4 \| sky`), bits 8-15 micro-offset (2 bits per axis, scaled by `mircoOffset`) |
| `iris_color` | `vec4` | vertex color (no texture) |
| `irisExtra` | `uvec4` | `x` material id, `y` face: 0 -Y, 1 +Y, 2 -Z, 3 +Z, 4 -X, 5 +X |

What the pack sees: `gl_Vertex` (`modelOffset + position`), `gl_Color`,
`gl_Normal`, `gl_MultiTexCoord1`/`2` (the lightmap coordinate,
`(level + 0.5) / 16`), `gl_MultiTexCoord0` = 0, `dhMaterialId`,
`gl_ModelViewMatrix`, `gl_ProjectionMatrix` (and inverses),
`gl_NormalMatrix`. No `mc_Entity`, no texture. Other uniforms the program
sets: `worldYOffset`, `clipDistance`, model view and projection for DH.

### Material ids (`StandardMacros`)

`DH_BLOCK_UNKNOWN` 0, `LEAVES` 1, `STONE` 2, `WOOD` 3, `METAL` 4, `DIRT`
5, `LAVA` 6, `DEEPSLATE` 7, `SNOW` 8, `SAND` 9, `TERRACOTTA` 10,
`NETHER_STONE` 11, `WATER` 12, `GRASS` 13, `AIR` 14, `ILLUMINATED` 15.

### Composite side

- Macro `DISTANT_HORIZONS` in every program and property, set only when
  `isModLoaded("distanthorizons")` and DH rendering is on.
- Samplers `dhDepthTex` / `dhDepthTex0` (LOD terrain and water depth) and
  `dhDepthTex1` (a `DEPTH32F` copy taken before the water).
- Uniforms: `dhNearPlane`, `dhFarPlane` (DH: `(distance + 512) * sqrt 2`),
  `dhRenderDistance` (`int`, blocks), `dhProjection`,
  `dhProjectionInverse`, `dhPreviousProjection` (a perspective with the
  game's FOV and aspect and DH's planes).
- Shadow matrices use `dhRenderDistance * 16` as near and far planes when
  the pack leaves them at -1.
- Before the DH opaque pass Iris clears the DH depth; DH's own fog and
  ambient occlusion are turned off under a pack.

### What Farfield needs

- Iris gates the whole path on DH: the macro on the mod id, `DHCompat` on
  the DH API classes, and `IrisLodRenderProgram` and `DHCompatInternal`
  reference `com.seibel.distanthorizons.api` types, so they cannot load
  without DH. Mixins: `StandardMacros` (the macro), `DHCompat`'s getters
  (depth textures, planes, distance, projection), and Farfield's own
  program wrapper over Iris's program builder and transformer.
- Our quads are packed and read from storage buffers (`lod.vert` takes
  the quad from `gl_VertexID / 4`). Either build a DH-format vertex
  stream, or replace the body of the injected `_vert_init()` after Iris's
  transform to decode our quads. The second costs no memory but depends
  on Iris's injected code.
- Colors only: our textures are lost; 16 material classes from our
  `MaterialClassifier`.

## Voxy (as the packs use it)

### `voxy.json`

Run through Iris's preprocessor (pack options and `#ifdef` apply), in the
program folder beside the snippets (`program/`, `world-1/`...; MakeUp
keeps it in `common/`).

| Key | Seen as | Meaning (from the packs' use) |
|---|---|---|
| `version` | `1` | contract version |
| `opaqueDrawBuffers` | `[1]`, `[1, 4]` | `colortex` indices the opaque snippet writes, in `location` order |
| `translucentDrawBuffers` | `[13, 16]`, `[1]` | same for the translucent snippet |
| `blending` | `{ -1: "off", 0: "ONE ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA" }` | per draw buffer (-1: default), off or four GL factors |
| `uniforms` | list of names | Iris uniforms (and pack custom uniforms) made available to the snippets |
| `samplers` | list of names, or `{ name: type }` | Iris samplers for the snippets (`noisetex`, `colortexN`, `depthtex1`, `shadowtex0` as `sampler2DShadow`...) |
| `excludeLodsFromVanillaDepth` | `true` | most likely: LODs are not written into `depthtex0` |
| `taaOffset` | a GLSL body returning `vec2` | the pack's TAA jitter, applied to the LOD projection |
| `useViewportDims` | `true` | the viewport size under TAA |
| `renderScale` | `[s, s]` | TAAU render scale |
| `unusedString` | a string | Photon's trick to `#include` settings |

### Snippets

`voxy_opaque.glsl` and `voxy_translucent.glsl`: fragment code only. Each
declares its own `layout(location = N) out` and defines
`void voxy_emitFragment(VoxyFragmentParameters parameters)`. Packs set
their own `VOXY_PATCH` macro inside; Complementary aliases
`gbufferModelView = vxModelView` and the like.

Fields of `VoxyFragmentParameters` the packs read:

| Field | Type (from use) | Meaning |
|---|---|---|
| `sampledColour` | `vec4` | the block texture's sample |
| `tinting` | `vec4` | biome tint, multiplied by the packs |
| `face` | `uint` | axis `face >> 1` (0 Y, 1 Z, 2 X), sign `face & 1` (1 positive) |
| `lightMap` | `vec2` | block and sky light, vanilla's `(level + 0.5) / 16` |
| `customId` | `uint` | the block's id from the pack's `block.properties` (Photon's start at 10000) |
| `uv` | `vec2` | texture coordinate (Revelation only) |

### Composite side

- Macro `VOXY`.
- Samplers `vxDepthTexOpaque`, `vxDepthTexTrans`.
- Uniforms `vxModelView`, `vxModelViewInv`, `vxModelViewPrev`, `vxProj`,
  `vxProjInv`, `vxProjPrev`, `vxViewProjInv` (one pack),
  `vxRenderDistance` (`int`, chunks; Complementary: "a little
  unreliable"). Packs reconstruct LOD positions through `vxProjInv`, so
  the LOD projection may have its own planes.

### Unknown without Voxy's code (settled by tests on the packs)

1. The exact struct declaration: field order, types, fields no pack reads.
2. The `customId` of a block missing from `block.properties`.
3. The exact grammar of `blending` and the meaning of
   `excludeLodsFromVanillaDepth`.
4. Where the passes sit against the vanilla terrain and water.

A snippet that reads a field Farfield does not provide fails to compile:
catch the link error and fall back (DH contract, or LODs off with a
message).

### What Farfield needs

- Iris knows nothing of Voxy: read `voxy.json` and the snippets from the
  pack through Iris's pack access and preprocessor; build `lod.frag` with
  the snippet and call `voxy_emitFragment` from it; our vertex path,
  textures, tints and light stay ours.
- Mixins: the `VOXY` macro, the `vx*` samplers and uniforms in Iris's
  composite programs, our draws at the right points of Iris's pipeline.
- `customId`: the pack's `block.properties` mapped to our block keys
  (Iris parses it into its id map).
- Turn the path off when Voxy itself is loaded.

## Choice (ADR-113)

Voxy first: our textures, tints and the pack's block ids survive, and our
vertex shader stays ours. DH second, for packs that ship only DH. A pack
with both uses Voxy; a Voxy link error falls back to DH.
