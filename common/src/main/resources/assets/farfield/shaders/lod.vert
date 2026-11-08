#version 460 core

#include "lod_geometry.glsl"

// The depth pre-pass and the shaded pass are separate programs: they must
// compute the same depth to the bit for the shaded pass's GL_EQUAL test.
invariant gl_Position;
#ifdef VOXY_TAA
// The shader pack's TAA jitter (voxy.json taaOffset, ADR-113), in NDC.
vec2 voxy_taaOffset();
#endif

uniform mat4 u_viewProj;    // the game's projection * view rotation
uniform vec2 u_depthRange;  // near, far of the LOD depth mapping
uniform ivec3 u_gridMin;    // section coordinates of the compiled-sections grid's corner (lod.frag)
uniform ivec2 u_gridSize;   // its width (x and z) and height, in sections

out vec3 v_relative;
out vec2 v_texCoord;      // block units along the face's U and V axes: one tile per block
flat out vec4 v_flat;     // mean untinted face color, linear, before light; alpha: coverage
flat out vec3 v_tinted;   // mean biome-tinted face color before its tint, linear (0 when none)
flat out uint v_tintKind; // tint kind (BiomeTints), NO_TINT when the face has no tinted part
#ifdef PLANTS
flat out vec3 v_plantTint; // the plant's blended tint at its column, linear
#endif
flat out uint v_biome;    // biome slot of the quad (biome id + 1, 0 for unknown)
flat out uint v_tiles;    // atlas tiles of the face (see Colors)
flat out float v_light;   // face shade (vanilla's per-direction shade)
flat out ivec2 v_lightmap; // (block light, sky light) in front of the face: vanilla lightmap texel
flat out int v_level;
out vec3 v_voxel;          // section-local position in voxels, for ambient occlusion
flat out uint v_direction;
flat out uint v_slot;      // section slot (Occupancy), with v_occluders
flat out uint v_occluders;
flat out uint v_cutout;     // 1: the face's texture has holes (opaque pass cuts them out)
#ifdef VOXY
// Per material, the shader pack's block id (Voxy's customId, ADR-113).
layout(std430, binding = 9) readonly buffer PackIds {
    uint packIds[];
};
flat out uint v_customId;
#endif
flat out uint v_handoff;    // bit 0: the section overlaps the grid where the game may draw (hand-off test); bit 1: a preview section

// Directions 6 and 7: a plant's diagonals (vanilla's cross model, unshaded).
const float SHADE[8] = float[](0.6, 0.6, 0.5, 1.0, 0.8, 0.8, 1.0, 1.0);

vec3 toLinear(vec3 c) {
    return pow(c, vec3(2.2));
}

#ifdef PLANTS
#include "biome_tint.glsl"
#endif

void main() {
    LodCorner c = lodCorner(uint(gl_BaseInstance), uint(gl_VertexID));
    Section s = c.section;
    uvec2 q = c.quad;
    uvec3 voxel = c.voxel;
    uint direction = c.direction;
    uint material = c.material;
    bool plant = c.plant;
#ifdef VOXY
    v_customId = material < uint(packIds.length()) ? packIds[material] : 0u;
#endif
    v_voxel = c.occlusion;
    v_relative = c.relative;
    gl_Position = u_viewProj * vec4(v_relative, 1.0);
    // Keep the game's x/y/w, but map depth over our own range (far beyond
    // the vanilla far plane): standard perspective depth computed from w.
    float n = u_depthRange.x;
    float f = u_depthRange.y;
    gl_Position.z = gl_Position.w * (f + n) / (f - n) - 2.0 * f * n / (f - n);
#ifdef VOXY_TAA
    // As the pack jitters its own terrain: its TAA resolves the LODs too.
    gl_Position.xy += voxy_taaOffset() * gl_Position.w;
#endif

    v_texCoord = c.texCoord;

    uint face = material * COLOR_STRIDE + (plant ? 0u : direction) * 3u;
    uint tinted = colors[face + 1u];
    v_tiles = colors[face + 2u];
    v_cutout = tinted >> 31;
    v_direction = direction;
    // Only sections within the compiled-sections grid can give way to the game's terrain.
    ivec3 gridLo = u_gridMin * 16;
    ivec3 gridHi = (u_gridMin + ivec3(u_gridSize.x, u_gridSize.y, u_gridSize.x)) * 16;
    ivec3 sectionHi = s.originLevel.xyz + (32 << s.originLevel.w);
    v_handoff = (all(lessThan(s.originLevel.xyz, gridHi)) && all(greaterThan(sectionHi, gridLo)) ? 1u : 0u)
            | (s.info.w & 1u) << 1;
#ifndef DEPTH_ONLY
    vec4 untinted = unpackUnorm4x8(colors[face]);
    vec3 color = toLinear(untinted.rgb);
    // The tint itself is chosen per fragment: biome blending varies along the face.
    bool hasTint = (tinted & 0xFFFFFFu) != 0u;
    v_tinted = hasTint ? toLinear(unpackUnorm4x8(tinted).rgb) : vec3(0.0);
    v_tintKind = hasTint ? (tinted >> 24) & 0x7Fu : 0xFFFFFFFFu;
    v_biome = (q.y >> 12) & 0x1FFu;
#ifdef PLANTS
    // A plant is one block wide: its tint, blended at its column, holds over the
    // whole quad, so it is chosen once here (plants cover many pixels each frame).
    v_plantTint = hasTint ? toLinear(blendedTint(vec2(voxel.xz) + 0.5, uint(gl_BaseInstance), v_biome,
            v_tintKind, s.originLevel.w)) : vec3(0.0);
#endif
    v_flat = vec4(color, untinted.a);
    v_light = SHADE[direction];
    v_lightmap = ivec2(int((q.y >> 25) & 15u), int((q.y >> 21) & 15u));
    v_level = s.originLevel.w;
    v_slot = uint(gl_BaseInstance);
    v_occluders = plant ? 0u : s.info.z; // vanilla's cross model has no ambient occlusion
#endif
}
