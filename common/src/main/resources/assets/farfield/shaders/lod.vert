#version 460 core

// Vertex pulling of 8-byte LOD quads (io.lakio.farfield.core.mesh.Quad):
// one draw command per section, gl_BaseInstance selects the section,
// gl_VertexID / 6 the quad and gl_VertexID % 6 the corner.

layout(std430, binding = 0) readonly buffer Quads {
    uvec2 quads[];
};

struct Section {
    ivec4 originLevel; // origin in blocks (xyz), level (w)
    uvec4 info;        // x: first quad, y: quad count, z: 1 if its opaque voxels are in Occupancy
};

layout(std430, binding = 1) readonly buffer Sections {
    Section sections[];
};

// Per block-state id and face direction (MaterialColors): an untinted
// color (alpha: coverage), a biome-tinted color (alpha byte: tint kind),
// both ABGR8 in sRGB, and the face's atlas tiles (untinted + 1 low 16 bits,
// tinted + 1 high 16 bits, 0 for none); then the height of the block's top
// in blocks, float bits (snow layers, slabs, carpets, fluids at vanilla's 8/9).
layout(std430, binding = 2) readonly buffer Colors {
    uint colors[];
};

const uint COLOR_STRIDE = 19u;
const uint HEIGHT_OFFSET = 18u;

uniform mat4 u_viewProj;    // the game's projection * view rotation
uniform ivec3 u_cameraBlock;
uniform vec3 u_cameraFrac;
uniform vec2 u_depthRange;  // near, far of the LOD depth mapping
uniform bool u_translucent; // translucent pass (water, glass, ice)

out vec3 v_relative;
out vec2 v_texCoord;      // block units along the face's U and V axes: one tile per block
flat out vec4 v_flat;     // mean untinted face color, linear, before light; alpha: coverage
flat out vec3 v_tinted;   // mean biome-tinted face color before its tint, linear (0 when none)
flat out uint v_tintKind; // tint kind (BiomeTints), NO_TINT when the face has no tinted part
flat out uint v_biome;    // biome slot of the quad (biome id + 1, 0 for unknown)
flat out uint v_tiles;    // atlas tiles of the face (see Colors)
flat out float v_light;   // face shade (vanilla's per-direction shade)
flat out ivec2 v_lightmap; // (block light, sky light) in front of the face: vanilla lightmap texel
flat out int v_level;
out vec3 v_voxel;          // section-local position in voxels, for ambient occlusion
flat out uint v_direction;
flat out uint v_slot;      // section slot (Occupancy), with v_occluders
flat out uint v_occluders;

const uint CORNER_U[6] = uint[](0u, 1u, 1u, 0u, 1u, 0u);
const uint CORNER_V[6] = uint[](0u, 0u, 1u, 0u, 1u, 1u);
const float SHADE[6] = float[](0.6, 0.6, 0.5, 1.0, 0.8, 0.8);

vec3 toLinear(vec3 c) {
    return pow(c, vec3(2.2));
}

void main() {
    Section s = sections[gl_BaseInstance];
    uint corner = uint(gl_VertexID) % 6u;
    uvec2 q = quads[s.info.x + uint(gl_VertexID) / 6u];
    uint lo = q.x;
    uvec3 voxel = uvec3(lo & 31u, (lo >> 5) & 31u, (lo >> 10) & 31u);
    uint sizeU = ((lo >> 15) & 31u) + 1u;
    uint sizeV = ((lo >> 20) & 31u) + 1u;
    uint direction = (lo >> 25) & 7u;
    uint material = (lo >> 28) | ((q.y & 0xFFFu) << 4);

    uint axis = direction >> 1;
    uint uAxis = (axis + 1u) % 3u;
    uint vAxis = (axis + 2u) % 3u;
    vec3 p = vec3(voxel);
    p[axis] += float(direction & 1u);
    // (U, V) corners wind counter-clockwise seen from +axis (U x V = +axis);
    // faces toward -axis swap them so every face winds counter-clockwise from
    // outside and back faces can be culled.
    bool negative = (direction & 1u) == 0u;
    uint cu = negative ? CORNER_V[corner] : CORNER_U[corner];
    uint cv = negative ? CORNER_U[corner] : CORNER_V[corner];
    p[uAxis] += float(cu * sizeU);
    p[vAxis] += float(cv * sizeV);

    float scale = float(1 << s.originLevel.w);
    // Voxels lower than their cell: on the finest level, partial blocks take
    // their block's height (snow layers, slabs, carpets); on coarser levels,
    // every voxel takes its fill in eighths, so the sea and snow stand where
    // they are instead of at the top of the coarse voxel.
    // Ambient occlusion reads the voxel grid at the face's unlowered position.
    v_voxel = p;
    bool top = direction == 3u
            || (axis == 0u && cu == 1u)
            || (axis == 2u && cv == 1u);
    if (top) {
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
    v_texCoord = vec2(p[uAxis], p[vAxis]) * scale;

    uint face = material * COLOR_STRIDE + direction * 3u;
    vec4 untinted = unpackUnorm4x8(colors[face]);
    vec3 color = toLinear(untinted.rgb);
    uint tinted = colors[face + 1u];
    // The tint itself is chosen per fragment: biome blending varies along the face.
    bool hasTint = (tinted & 0xFFFFFFu) != 0u;
    v_tinted = hasTint ? toLinear(unpackUnorm4x8(tinted).rgb) : vec3(0.0);
    v_tintKind = hasTint ? tinted >> 24 : 0xFFFFFFFFu;
    v_biome = (q.y >> 12) & 0x1FFu;
    v_flat = vec4(color, untinted.a);
    v_tiles = colors[face + 2u];
    v_light = SHADE[direction];
    v_lightmap = ivec2(int((q.y >> 25) & 15u), int((q.y >> 21) & 15u));
    v_level = s.originLevel.w;
    v_direction = direction;
    v_slot = uint(gl_BaseInstance);
    v_occluders = s.info.z;
}
