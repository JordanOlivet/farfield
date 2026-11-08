#version 460 compatibility

// The shader pack bridge of the DH contract (ADR-119): linked beside the
// pack's dh_terrain or dh_water vertex shader as Iris transforms it, whose
// own _vert_init (DH's vertex attributes) is cut out. This one fills the
// same globals from the LOD quads, so the pack's shader runs on them as on
// Distant Horizons' vertices: positions from the camera (modelOffset 0),
// a color without texture, the normal, the light levels and DH's material
// class.

#include "lod_geometry.glsl"
#include "biome_tint.glsl"

// Per material, the pack contract's block id: here DH's material class (DhMaterials).
layout(std430, binding = 9) readonly buffer PackIds {
    uint packIds[];
};

// Iris's globals of the pack's unit (DHTerrainTransformer), shared at link time.
vec3 _vert_position;
vec2 _vert_tex_light_coord;
int dhMaterialId;
vec4 _vert_color;
vec3 _vert_normal;

out vec3 farfield_relative;
flat out uint farfield_handoff;
flat out uint farfield_direction;

// Our directions: -X +X -Y +Y -Z +Z, then a plant's two diagonals (lit from above).
const vec3 FARFIELD_NORMALS[8] = vec3[](vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0), vec3(0.0, -1.0, 0.0),
        vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 1.0, 0.0));

vec3 farfield_toLinear(vec3 c) {
    return pow(c, vec3(2.2));
}

void _vert_init() {
    LodCorner c = lodCorner(uint(gl_BaseInstance), uint(gl_VertexID));
    Section s = c.section;
    vec3 normal = FARFIELD_NORMALS[c.direction];
    _vert_position = c.relative;
    _vert_normal = normal;
    // DH's light coordinate: (block, sky), (level + 0.5) / 16.
    _vert_tex_light_coord = (vec2(float((c.quad.y >> 25) & 15u), float((c.quad.y >> 21) & 15u)) + 0.5) / 16.0;
    uint face = c.material * COLOR_STRIDE + (c.plant ? 0u : c.direction) * 3u;
    vec4 untinted = unpackUnorm4x8(colors[face]);
    uint tinted = colors[face + 1u];
    vec3 color = farfield_toLinear(untinted.rgb);
    if ((tinted & 0xFFFFFFu) != 0u) {
        // The tint blended at the corner, a hair inside its voxel: the pack's
        // own fragment shader cannot blend it per pixel as lod.frag does.
        uint biome = (c.quad.y >> 12) & 0x1FFu;
        uint kind = (tinted >> 24) & 0x7Fu;
        vec3 tint = farfield_toLinear(blendedTint(c.occlusion.xz - normal.xz * 0.01, uint(gl_BaseInstance), biome, kind,
                s.originLevel.w));
        color += farfield_toLinear(unpackUnorm4x8(tinted).rgb) * tint;
    }
    _vert_color = vec4(pow(min(color, vec3(1.0)), vec3(1.0 / 2.2)), untinted.a);
    dhMaterialId = int(c.material < uint(packIds.length()) ? packIds[c.material] : 0u);

    farfield_relative = c.relative;
    farfield_direction = c.direction;
    // DH's model: the LODs are drawn under the game's terrain too, and the
    // pack fades its terrain into them near the render distance (a dither,
    // Complementary's DH_BLENDING); giving way there left holes. Only the
    // preview still gives way to the real chunks that took over from it.
    farfield_handoff = (s.info.w & 1u) << 1;
}
