#version 460 core

in vec3 v_relative;
in vec2 v_texCoord;
flat in vec4 v_flat;
flat in vec3 v_tint;
flat in uint v_tiles;
flat in float v_light;
flat in ivec2 v_lightmap;
in vec3 v_voxel;
flat in uint v_direction;
flat in uint v_slot;
flat in uint v_occluders;

// Opaque voxels per section slot: 1024 ints, index y * 32 + z, bit x.
layout(std430, binding = 4) readonly buffer Occupancy {
    uint occupancy[];
};
flat in int v_level;

uniform ivec3 u_cameraBlock;
uniform vec3 u_cameraFrac;
uniform int u_renderDistance;  // the game's render distance in chunks
uniform bool u_sodiumRule;     // Sodium (or a fork) draws the terrain, else vanilla
uniform ivec3 u_gridMin;       // section coordinates of the compiled-sections grid's corner
uniform ivec2 u_gridSize;      // its width (x and z) and height, in sections

// Sections the terrain renderer has compiled: bit ((z * width + x) * height + y).
layout(std430, binding = 5) readonly buffer DrawnSections {
    uint drawnSections[];
};
uniform vec4 u_fogColor;
uniform vec2 u_fog;            // start, end
uniform bool u_translucent;    // translucent pass: blend with the face's coverage
uniform bool u_debugLevels;    // tint faces by LOD level (/farfield debug levels)
uniform sampler2D u_atlas;     // FaceTextures: 16x16 sRGB tiles, 128 per row, 5 mip levels
uniform vec2 u_atlasSize;      // in pixels
uniform float u_textureEnd;    // textures fade into the mean color up to this distance; 0: off
uniform sampler2D u_lightmap;  // vanilla's lightmap: x block light, y sky light (day, gamma, effects)

out vec4 fragColor;

const uint TILES_PER_ROW = 128u;
const float TILE = 16.0;
// Level 0 to 4: red, orange, yellow, green, blue.
const vec3 LEVEL_COLORS[5] = vec3[](vec3(1.0, 0.15, 0.1), vec3(1.0, 0.55, 0.0), vec3(1.0, 0.95, 0.1),
                                    vec3(0.2, 0.85, 0.2), vec3(0.2, 0.45, 1.0));

// Slots of each section's 26 same-level neighbors: 27 per slot,
// (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1), slot + 1 or 0 when not resident.
layout(std430, binding = 6) readonly buffer NeighborSlots {
    uint neighborSlots[];
};

bool occluder(ivec3 v) {
    uint slot = v_slot;
    ivec3 o = v >> 5; // -1, 0 or 1 per axis
    if (o != ivec3(0)) {
        uint n = neighborSlots[v_slot * 27u + uint((o.x + 1) * 9 + (o.y + 1) * 3 + (o.z + 1))];
        if (n == 0u) {
            return false; // neighbor not drawn at this level: counted as open
        }
        slot = n - 1u;
        v -= o * 32;
    }
    return ((occupancy[slot * 1024u + uint(v.y * 32 + v.z)] >> uint(v.x)) & 1u) != 0u;
}

// Vanilla's smooth-lighting ambient occlusion: per corner of the voxel face,
// (front + side1 + side2 + corner) / 4 with 0.2 for an opaque voxel and 1.0
// otherwise (the corner counts as opaque when both sides are), interpolated
// over the face.
float ambientOcclusion() {
    uint axis = v_direction >> 1;
    uint uAxis = (axis + 1u) % 3u;
    uint vAxis = (axis + 2u) % 3u;
    bool positive = (v_direction & 1u) != 0u;
    vec3 p = v_voxel;
    // The face lies on the plane between two voxel layers: the one in front.
    ivec3 front;
    front[axis] = positive ? int(round(p[axis])) : int(round(p[axis])) - 1;
    front[uAxis] = int(floor(p[uAxis]));
    front[vAxis] = int(floor(p[vAxis]));
    vec2 f = vec2(fract(p[uAxis]), fract(p[vAxis]));
    ivec3 du = ivec3(0);
    ivec3 dv = ivec3(0);
    du[uAxis] = 1;
    dv[vAxis] = 1;
    // Ring around the front voxel: -u, +u, -v, +v, then the four corners.
    float b0 = occluder(front - du) ? 0.2 : 1.0;
    float b1 = occluder(front + du) ? 0.2 : 1.0;
    float b2 = occluder(front - dv) ? 0.2 : 1.0;
    float b3 = occluder(front + dv) ? 0.2 : 1.0;
    float c00 = (b0 < 1.0 && b2 < 1.0) ? 0.2 : (occluder(front - du - dv) ? 0.2 : 1.0);
    float c10 = (b1 < 1.0 && b2 < 1.0) ? 0.2 : (occluder(front + du - dv) ? 0.2 : 1.0);
    float c01 = (b0 < 1.0 && b3 < 1.0) ? 0.2 : (occluder(front - du + dv) ? 0.2 : 1.0);
    float c11 = (b1 < 1.0 && b3 < 1.0) ? 0.2 : (occluder(front + du + dv) ? 0.2 : 1.0);
    float a00 = (1.0 + b0 + b2 + c00) * 0.25;
    float a10 = (1.0 + b1 + b2 + c10) * 0.25;
    float a01 = (1.0 + b0 + b3 + c01) * 0.25;
    float a11 = (1.0 + b1 + b3 + c11) * 0.25;
    return mix(mix(a00, a10, f.x), mix(a01, a11, f.x), f.y);
}

// Whether the game draws the chunk section holding a world block, with the
// same rule as the terrain renderer, so LOD and vanilla neither overlap
// (translucent surfaces blended twice) nor leave a gap.
bool vanillaDraws(ivec3 block) {
    ivec3 section = block >> 4;
    ivec3 g = section - u_gridMin;
    if (any(lessThan(g, ivec3(0))) || g.x >= u_gridSize.x || g.z >= u_gridSize.x || g.y >= u_gridSize.y) {
        return false;
    }
    uint bit = uint((g.z * u_gridSize.x + g.x) * u_gridSize.y + g.y);
    if (((drawnSections[bit >> 5] >> (bit & 31u)) & 1u) == 0u) {
        return false; // not compiled yet: the game draws nothing there
    }
    if (u_sodiumRule) {
        // Sodium's OcclusionCuller: nearest point of the section grown by one
        // block, within the render distance horizontally and vertically.
        float d = float(u_renderDistance * 16);
        ivec3 o = section * 16 - u_cameraBlock;
        vec3 n;
        for (int i = 0; i < 3; i++) {
            int lo = o[i] - 1;
            int hi = o[i] + 17;
            n[i] = float(lo > 0 ? lo : (hi < 0 ? hi : 0)) - u_cameraFrac[i];
        }
        return n.x * n.x + n.z * n.z < d * d && abs(n.y) < d;
    }
    // Vanilla's ChunkTrackingView.isInViewDistance, around the camera's chunk.
    ivec2 c = abs(section.xz - (u_cameraBlock.xz >> 4)) - 1;
    c = max(c, ivec2(0));
    int far = max(c.x, c.y);
    int near = min(c.x, c.y);
    return near * near + far * far < u_renderDistance * u_renderDistance;
}

// One texel of a tile, the mip level chosen from the unwrapped coordinates
// (fract() alone would jump to the smallest mip at every block edge).
vec4 tile(uint index, vec2 f, vec2 gx, vec2 gy) {
    vec2 origin = vec2(float(index % TILES_PER_ROW), float(index / TILES_PER_ROW)) * TILE;
    vec2 uv = (origin + f * TILE) / u_atlasSize;
    return textureGrad(u_atlas, uv, gx * TILE / u_atlasSize, gy * TILE / u_atlasSize);
}

void main() {
    // The block this face belongs to: a hair inside its voxel, so faces on a
    // section border test the section of their own voxel.
    vec3 normal = vec3(0.0);
    normal[v_direction >> 1] = (v_direction & 1u) != 0u ? 1.0 : -1.0;
    vec3 inside = v_relative + u_cameraFrac - normal * 0.01;
    if (vanillaDraws(u_cameraBlock + ivec3(floor(inside)))) {
        discard;
    }
    float distance = length(v_relative);
    vec3 color = v_flat.rgb;
    float alpha = v_flat.a;
    // Derivatives outside the branch: they are undefined in divergent control flow.
    vec2 f = fract(v_texCoord);
    vec2 gx = dFdx(v_texCoord);
    vec2 gy = dFdy(v_texCoord);
    if (v_tiles != 0u && distance < u_textureEnd) {
        vec4 base = tile((v_tiles & 0xFFFFu) - 1u, f, gx, gy);
        vec3 textured = base.rgb;
        if ((v_tiles >> 16) != 0u) {
            textured += tile((v_tiles >> 16) - 1u, f, gx, gy).rgb * v_tint;
        }
        float fade = smoothstep(u_textureEnd * 0.75, u_textureEnd, distance);
        color = mix(textured, color, fade);
        alpha = mix(base.a, alpha, fade);
    }
    // As vanilla's terrain: texture times shade times lightmap, in sRGB.
    float ao = (v_occluders != 0u && !u_translucent) ? ambientOcclusion() : 1.0;
    // Vanilla samples its lightmap filtered at level / 16 (minecraft_sample_lightmap),
    // between two texels: full light reads halfway between levels 14 and 15.
    vec2 lightUv = clamp(vec2(v_lightmap) / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    color = pow(min(color, vec3(1.0)), vec3(1.0 / 2.2)) * (v_light * ao) * texture(u_lightmap, lightUv).rgb;
    if (u_debugLevels) {
        color = mix(color, LEVEL_COLORS[min(v_level, 4)], 0.65);
    }
    float fog = smoothstep(u_fog.x, u_fog.y, distance);
    fragColor = vec4(mix(color, u_fogColor.rgb, fog), u_translucent ? alpha : 1.0);
}
