#version 460 core

// Vertex pulling of 8-byte LOD quads (io.lakio.farfield.core.mesh.Quad):
// one draw command per section, gl_BaseInstance selects the section,
// gl_VertexID / 6 the quad and gl_VertexID % 6 the corner.

layout(std430, binding = 0) readonly buffer Quads {
    uvec2 quads[];
};

struct Section {
    ivec4 originLevel; // origin in blocks (xyz), level (w)
    uvec4 info;        // x: first quad
};

layout(std430, binding = 1) readonly buffer Sections {
    Section sections[];
};

// Per block-state id and face direction (MaterialColors): an untinted
// color (alpha: coverage), a biome-tinted color (alpha byte: tint kind),
// both ABGR8 in sRGB, and the face's atlas tiles (untinted + 1 low 16 bits,
// tinted + 1 high 16 bits, 0 for none).
layout(std430, binding = 2) readonly buffer Colors {
    uint colors[];
};

// Per biome slot (biome id + 1, 0 for unknown) and tint kind (BiomeTints).
layout(std430, binding = 3) readonly buffer BiomeTints {
    uint tints[];
};

const uint COLOR_STRIDE = 18u;
const uint TINT_KINDS = 8u;

uniform mat4 u_viewProj;    // the game's projection * view rotation
uniform ivec3 u_cameraBlock;
uniform vec3 u_cameraFrac;
uniform vec2 u_depthRange;  // near, far of the LOD depth mapping
uniform float u_brightness; // sky brightness (day/night, weather), as vanilla sky light

out vec3 v_relative;
out vec2 v_texCoord;      // block units along the face's U and V axes: one tile per block
flat out vec4 v_flat;     // mean face color, linear, before light; alpha: coverage
flat out vec3 v_tint;     // biome tint of the tinted part, linear (0 when none)
flat out uint v_tiles;    // atlas tiles of the face (see Colors)
flat out float v_light;   // face shade times sky brightness
flat out int v_level;

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
    uint material = (lo >> 28) | ((q.y & 0xFFFFu) << 4);

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
    vec3 tint = vec3(0.0);
    if ((tinted & 0xFFFFFFu) != 0u) {
        uint biomeSlot = (q.y >> 16) & 0xFFFu;
        tint = toLinear(unpackUnorm4x8(tints[biomeSlot * TINT_KINDS + (tinted >> 24)]).rgb);
        color += toLinear(unpackUnorm4x8(tinted).rgb) * tint;
    }
    v_flat = vec4(color, untinted.a);
    v_tint = tint;
    v_tiles = colors[face + 2u];
    v_light = SHADE[direction] * u_brightness;
    v_level = s.originLevel.w;
}
