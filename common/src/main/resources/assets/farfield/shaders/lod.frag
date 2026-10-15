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

uniform float u_vanillaRadius; // vanilla terrain is drawn within this horizontal and vertical distance
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

bool occluder(ivec3 v) {
    if (any(lessThan(v, ivec3(0))) || any(greaterThan(v, ivec3(31)))) {
        return false; // outside the section: counted as open
    }
    return ((occupancy[v_slot * 1024u + uint(v.y * 32 + v.z)] >> uint(v.x)) & 1u) != 0u;
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

// One texel of a tile, the mip level chosen from the unwrapped coordinates
// (fract() alone would jump to the smallest mip at every block edge).
vec4 tile(uint index, vec2 f, vec2 gx, vec2 gy) {
    vec2 origin = vec2(float(index % TILES_PER_ROW), float(index / TILES_PER_ROW)) * TILE;
    vec2 uv = (origin + f * TILE) / u_atlasSize;
    return textureGrad(u_atlas, uv, gx * TILE / u_atlasSize, gy * TILE / u_atlasSize);
}

void main() {
    // Sodium draws a section only within the render distance horizontally
    // and vertically: far below or above the camera, LODs must show.
    float horizontal = length(v_relative.xz);
    if (horizontal < u_vanillaRadius && abs(v_relative.y) < u_vanillaRadius) {
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
    color = pow(min(color, vec3(1.0)), vec3(1.0 / 2.2)) * (v_light * ao) * texelFetch(u_lightmap, v_lightmap, 0).rgb;
    if (u_debugLevels) {
        color = mix(color, LEVEL_COLORS[min(v_level, 4)], 0.65);
    }
    float fog = smoothstep(u_fog.x, u_fog.y, distance);
    fragColor = vec4(mix(color, u_fogColor.rgb, fog), u_translucent ? alpha : 1.0);
}
