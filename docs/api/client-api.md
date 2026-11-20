# Farfield client API: the far terrain's depth

For mods that draw over the far terrain (clouds, sky, fog, weather) and need
to know where Farfield's distant terrain (the LODs) is on screen. API version 1
(ADR-172); Minecraft 1.21.1 to 26.2, NeoForge and Fabric.

Farfield draws its LODs after the sky and before the game's terrain, with a
depth range of its own, then clears the depth for the game's terrain. A mod
that reads the game's depth at the end of the level sees sky where the LODs
are: its clouds go over distant mountains and its effects stop at the vanilla
render distance. The API gives back what the LODs drew.

## Setup

Compile against Farfield's jar without bundling it (`compileOnly`), and call
the API only when Farfield is loaded (mod id `farfield`), for example from a
class that only loads in that case.

```java
import io.lakio.farfield.api.client.FarfieldClientApi;
import io.lakio.farfield.api.client.LodFrame;

// Once, at client setup. Farfield copies its depth each frame only once a mod asked.
FarfieldClientApi.requestLodDepth("mymod");
```

Farfield logs `LOD depth shared with mymod`, then the size and format on the
first copy.

## Each frame

Read the frame on the render thread, after the game's terrain (when the game
draws its clouds, or at the end of the level), never before the level renders.

```java
private static final float[] INVERSE = new float[16];

LodFrame lods = FarfieldClientApi.lodFrame();
if (lods.active()) {
    lods.inverseViewProjection(INVERSE);
    // bind lods.depthTexture() to a sampler, upload INVERSE (column-major),
    // and extend your effect to lods.viewDistance() blocks
}
```

`lodFrame()` never returns null. It is inactive when Farfield drew nothing this
frame: no LODs yet, rendering off, a shader pack in charge (shader packs draw
the LODs through their own programs), or no mod asked for the depth.

## Reading the depth in a shader

The texture holds the LODs alone. Read the game's own depth first, and use the
LOD depth only where the game drew nothing (its depth is at the far value):
near the player the LODs give way to the game's chunks.

```glsl
uniform sampler2D uLodDepth;     // lods.depthTexture()
uniform mat4 uLodInverse;        // lods.inverseViewProjection(...)
uniform int uLodReverseZ;        // lods.reverseZ()
uniform int uLodZeroToOne;       // lods.zeroToOne()

// True, with the position relative to the camera in blocks, where a LOD was drawn.
bool lodSurface(vec2 uv, out vec3 position) {
    float d = texture(uLodDepth, uv).r;
    if (uLodReverseZ == 1 ? d <= 0.0 : d >= 1.0) {
        return false;
    }
    float ndcDepth = uLodZeroToOne == 1 ? d : d * 2.0 - 1.0;
    vec4 p = uLodInverse * vec4(uv * 2.0 - 1.0, ndcDepth, 1.0);
    position = p.xyz / p.w;
    return true;
}
```

`length(position)` is the distance to the camera; `position + cameraPosition`
is the world position.

## What the values mean

| Method | Value |
|---|---|
| `active()` | the values below describe this frame |
| `depthTexture()` | OpenGL name of a 2D depth texture, the size of the game's main render target; Farfield owns it (sample it, never write or delete it); recreated when the window size changes |
| `reverseZ()` | nearer surfaces have larger values (far is 0); false today on every version |
| `zeroToOne()` | normalized device depth is the texture value itself; false today (it is `value * 2 - 1`) |
| `inverseViewProjection(float[16])` | column-major, as `glUniformMatrix4fv` takes it with transpose false: normalized device position to camera-relative position, in blocks, after the division by w |
| `viewDistance()` | the farthest LOD geometry drawn, in blocks from the camera, the noise preview's included; never the draw distance setting (players set it up to 33,554,432 blocks); it grows while the LODs load |

Notes:

- Translucent LOD surfaces (water) write no depth: the depth there is the
  ground under the water.
- Farfield's draw distance goes from a few thousand blocks to tens of
  thousands. If your effect is tuned for a shorter range, cap
  `viewDistance()` on your side.
- Test the flags rather than assuming them: a later Farfield version may draw
  its LODs with reversed depth.
- The game turns its clouds off below a render distance of 4 chunks, a
  common setting with Farfield since the LODs draw the rest. If your mod
  keeps its clouds on there for another distant terrain mod, do the same
  when `lodFrame()` is active.
