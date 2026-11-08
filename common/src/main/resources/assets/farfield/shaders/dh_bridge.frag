#version 460 compatibility

// The shader pack bridge of the DH contract, fragment side (ADR-119): the
// pack's dh_terrain or dh_water fragment shader calls farfield_clip() first
// (added to its main), which drops the preview's fragments where the real
// chunks took over from it, and translucent LODs behind the game's opaque
// terrain. Unlike lod.frag, the LODs do not give way to the game's terrain:
// as under Distant Horizons, the pack blends the two (dh_bridge.vert).

uniform ivec3 u_cameraBlock;
uniform vec3 u_cameraFrac;
#include "handoff.glsl"

uniform bool u_translucent;
// The game's depth at its translucent stage: its opaque terrain, nearer than any LOD.
uniform sampler2D u_gameDepth;

in vec3 farfield_relative;
flat in uint farfield_handoff;
flat in uint farfield_direction;

void farfield_clip() {
    if (u_translucent && texelFetch(u_gameDepth, ivec2(gl_FragCoord.xy), 0).r < 1.0) {
        discard;
    }
    if (farfield_handoff != 0u) {
        // The block this face belongs to: a hair inside its voxel, as lod.frag.
        vec3 normal = vec3(0.0);
        if (farfield_direction < 6u) {
            normal[farfield_direction >> 1] = (farfield_direction & 1u) != 0u ? 1.0 : -1.0;
        }
        ivec3 block = u_cameraBlock + ivec3(floor(farfield_relative + u_cameraFrac - normal * 0.01));
        if ((farfield_handoff & 1u) != 0u && vanillaDraws(block)
                || (farfield_handoff & 2u) != 0u && previewHiddenAt(block)) {
            discard;
        }
    }
}
