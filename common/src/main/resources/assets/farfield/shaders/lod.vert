#version 460 core

// Vertex pulling of 8-byte LOD quads (io.lakio.farfield.core.mesh.Quad):
// one draw command per section, gl_BaseInstance selects the section,
// gl_VertexID / 4 the quad and gl_VertexID % 4 the corner (indexed draws:
// gl_VertexID is the shared index buffer's value plus the command's base
// vertex, 4 vertices and 2 triangles per quad).

layout(std430, binding = 0) readonly buffer Quads {
    uvec2 quads[];
};

struct Section {
    ivec4 originLevel; // origin in blocks (xyz), level (w)
    uvec4 info;        // x: first quad, y: quad count, z: 1 if its opaque voxels are in Occupancy, w: 1 for the preview
};

layout(std430, binding = 1) readonly buffer Sections {
    Section sections[];
};

// Per block-state id and face direction (MaterialColors): an untinted
// color (alpha: coverage), a biome-tinted color (alpha byte: tint kind,
// top bit: the texture has holes to cut out),
// both ABGR8 in sRGB, and the face's atlas tiles (untinted + 1 low 16 bits,
// tinted + 1 high 16 bits, 0 for none); then the height of the block's top
// in blocks, float bits (snow layers, slabs, carpets, fluids at vanilla's 8/9);
// then 8 boxes of a shaped block (ShapeBoxes: min xyz, max xyz in sixteenths,
// 5 bits each; 0 past its last box and for other blocks).
layout(std430, binding = 2) readonly buffer Colors {
    uint colors[];
};

const uint COLOR_STRIDE = 27u;
const uint HEIGHT_OFFSET = 18u;
const uint BOX_OFFSET = 19u;

// The depth pre-pass and the shaded pass are separate programs: they must
// compute the same depth to the bit for the shaded pass's GL_EQUAL test.
invariant gl_Position;

uniform mat4 u_viewProj;    // the game's projection * view rotation
uniform ivec3 u_cameraBlock;
uniform vec3 u_cameraFrac;
uniform vec2 u_depthRange;  // near, far of the LOD depth mapping
uniform bool u_translucent; // translucent pass (water, glass, ice)
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
flat out uint v_handoff;    // bit 0: the section overlaps the grid where the game may draw (hand-off test); bit 1: a preview section

// Directions 6 and 7: a plant's diagonals (vanilla's cross model, unshaded).
const float SHADE[8] = float[](0.6, 0.6, 0.5, 1.0, 0.8, 0.8, 1.0, 1.0);
// Inset of a plant's diagonals in its voxel, as vanilla's cross model (0.8 to 15.2 sixteenths).
const float CROSS_INSET = 0.05;

vec3 toLinear(vec3 c) {
    return pow(c, vec3(2.2));
}

#ifdef PLANTS
#include "biome_tint.glsl"
#endif

void main() {
    Section s = sections[gl_BaseInstance];
    uint corner = uint(gl_VertexID) & 3u;
    uvec2 q = quads[s.info.x + uint(gl_VertexID) / 4u];
    uint lo = q.x;
    uvec3 voxel = uvec3(lo & 31u, (lo >> 5) & 31u, (lo >> 10) & 31u);
    uint sizeU = ((lo >> 15) & 31u) + 1u;
    uint sizeV = ((lo >> 20) & 31u) + 1u;
    uint direction = (lo >> 25) & 7u;
    uint material = (lo >> 28) | ((q.y & 0xFFFu) << 4);

    bool plant = direction >= 6u;
    uint axis = plant ? 0u : direction >> 1;
    uint uAxis = (axis + 1u) % 3u;
    uint vAxis = (axis + 2u) % 3u;
    vec3 p = vec3(voxel);
    p[axis] += float(direction & 1u);
    // (U, V) corners wind counter-clockwise seen from +axis (U x V = +axis);
    // faces toward -axis swap them so every face winds counter-clockwise from
    // outside and back faces can be culled.
    bool negative = (direction & 1u) == 0u;
    uint cu = negative ? corner >> 1 : corner & 1u;
    uint cv = negative ? corner & 1u : corner >> 1;
    p[uAxis] += float(cu * sizeU);
    p[vAxis] += float(cv * sizeV);

    float scale = float(1 << s.originLevel.w);
    // Shaped blocks on the finest level (stairs, fences, walls, panes): the
    // quad is one face of the box its fill bits select. Other quads select
    // box 7 there, which is 0 for blocks that are not shaped.
    uint box = s.originLevel.w == 0 ? colors[material * COLOR_STRIDE + BOX_OFFSET + (q.y >> 29)] : 0u;
    // Voxels lower than their cell: on the finest level, partial blocks take
    // their block's height (snow layers, slabs, carpets); on coarser levels,
    // every voxel takes its fill in eighths, so the sea and snow stand where
    // they are instead of at the top of the coarse voxel.
    // Ambient occlusion reads the voxel grid at the face's unlowered position.
    v_voxel = p;
    bool top = !plant && (direction == 3u
            || (axis == 0u && cu == 1u)
            || (axis == 2u && cv == 1u));
    vec2 crossTex = vec2(0.0);
    if (plant) {
        // A vertical quad along one diagonal of the voxel (finest level only),
        // wound toward the camera so back-face culling keeps it from both sides.
        vec2 from = direction == 6u ? vec2(CROSS_INSET) : vec2(1.0 - CROSS_INSET, CROSS_INSET);
        vec2 to = direction == 6u ? vec2(1.0 - CROSS_INSET) : vec2(CROSS_INSET, 1.0 - CROSS_INSET);
        vec2 center = vec2(voxel.xz) + (from + to) * 0.5;
        vec2 toCamera = vec2(u_cameraBlock.xz - s.originLevel.xz) + u_cameraFrac.xz - center;
        // Counter-clockwise from the side of (-dz, dx) with U along the diagonal and V up.
        vec2 d = to - from;
        float t = float(corner & 1u);
        if (dot(vec2(-d.y, d.x), toCamera) < 0.0) {
            t = 1.0 - t;
        }
        float h = float(corner >> 1);
        vec2 xz = mix(from, to, t);
        p = vec3(voxel) + vec3(xz.x, h, xz.y);
        v_voxel = p;
        // The plant's sprite lies on face 0 (MaterialColors): columns up, rows along the diagonal.
        crossTex = vec2(h, t);
    } else if (box != 0u) {
        vec3 lo = vec3(uvec3(box, box >> 5, box >> 10) & 31u) / 16.0;
        vec3 hi = vec3(uvec3(box >> 15, box >> 20, box >> 25) & 31u) / 16.0;
        p = vec3(voxel);
        p[axis] += negative ? lo[axis] : hi[axis];
        p[uAxis] += cu == 1u ? hi[uAxis] : lo[uAxis];
        p[vAxis] += cv == 1u ? hi[vAxis] : lo[vAxis];
        // Ambient occlusion as for the voxel's own face on that side.
        v_voxel = p;
        v_voxel[axis] = float(voxel[axis]) + float(direction & 1u);
    } else if (top) {
        if (s.originLevel.w == 0) {
            float height = uintBitsToFloat(colors[material * COLOR_STRIDE + HEIGHT_OFFSET]);
            if (height < 1.0) {
                p.y -= 1.0 - height;
            }
        } else {
            uint fill = (q.y >> 29) + 1u;
            if (fill < 8u) {
                p.y -= float(8u - fill) / 8.0;
            }
            // Fluids also lose the last ninth of a block, in blocks, as on level 0:
            // the sea surface lies at the same height on every level.
            if (u_translucent) {
                float height = uintBitsToFloat(colors[material * COLOR_STRIDE + HEIGHT_OFFSET]);
                if (height < 1.0) {
                    p.y -= (1.0 - height) / scale;
                }
            }
        }
    }
    v_relative = vec3(s.originLevel.xyz - u_cameraBlock) + p * scale - u_cameraFrac;
    gl_Position = u_viewProj * vec4(v_relative, 1.0);
    // Keep the game's x/y/w, but map depth over our own range (far beyond
    // the vanilla far plane): standard perspective depth computed from w.
    float n = u_depthRange.x;
    float f = u_depthRange.y;
    gl_Position.z = gl_Position.w * (f + n) / (f - n) - 2.0 * f * n / (f - n);

    // Section-local block coordinates: the section origin is a multiple of
    // its size, so their fractions are the world's.
    v_texCoord = plant ? crossTex : vec2(p[uAxis], p[vAxis]) * scale;

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
