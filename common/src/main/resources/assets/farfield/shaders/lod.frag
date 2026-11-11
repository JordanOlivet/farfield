#version 460 core

// Variants (LodRenderer): DEPTH_ONLY, the opaque depth pre-pass (only the
// discards, no shading); DEPTH_ONLY with SOLID, the pre-pass of sections
// that cannot discard (away from the game's terrain, no face with holes):
// without any discard in the shader, the GPU tests and writes depth before
// it, which a discard anywhere in the shader prevents (measured: 456 against
// 780 us); EARLY_Z, the shaded passes after it: the depth test
// runs before the shader, so hidden fragments are never shaded. The opaque
// pass then draws with GL_EQUAL against the pre-pass depth, which already
// holds the hand-off and cutout holes; the translucent pass writes no depth,
// so its discards stay valid with early tests. Neither: one pass, as before.
// PLANTS: the plants' pass, nothing else (one pass, no pre-pass).
// VOXY: under a shader pack (ADR-113), the shaded passes hand the fragment to
// the pack's voxy_emitFragment, linked from a second unit, instead of
// lighting and fogging it here.
#if defined(EARLY_Z) || defined(SOLID)
layout(early_fragment_tests) in;
#endif

in vec3 v_relative;
in vec2 v_texCoord;
flat in vec4 v_flat;
flat in vec3 v_tinted;
flat in uint v_tintKind;
#ifdef PLANTS
flat in vec3 v_plantTint;  // the plant's blended tint, linear (lod.vert)
#endif
flat in uint v_biome;
flat in uint v_tiles;
flat in float v_light;
flat in ivec2 v_lightmap;
in vec3 v_voxel;
flat in uint v_direction;
flat in uint v_slot;
flat in uint v_occluders;
flat in uint v_cutout;
flat in uint v_handoff;

// Opaque voxels per section slot: 1024 ints, index y * 32 + z, bit x.
layout(std430, binding = 4) readonly buffer Occupancy {
    uint occupancy[];
};
flat in int v_level;

uniform ivec3 u_cameraBlock;
uniform vec3 u_cameraFrac;
#include "handoff.glsl"

uniform vec4 u_fogColor;
uniform vec2 u_fog;            // start, end
uniform bool u_fogCylinder;    // vanilla's cylinder fog shape (max of horizontal and vertical distance)
uniform bool u_translucent;    // translucent pass: blend with the face's coverage
uniform bool u_debugLevels;    // tint faces by LOD level (/farfield debug levels)
uniform bool u_ambientOcclusion;  // smooth-lighting occlusion on (/farfield debug ao)
uniform sampler2D u_atlas;     // FaceTextures: 16x16 sRGB tiles, 128 per row, 5 mip levels
uniform vec2 u_atlasSize;      // in pixels
uniform float u_textureEnd;    // textures fade into the mean color up to this distance; 0: off
uniform sampler2D u_lightmap;  // vanilla's lightmap: x block light, y sky light (day, gamma, effects)

const uint NO_TINT = 0xFFFFFFFFu;

#ifdef VOXY
flat in uint v_customId;
#include "voxy_parameters.glsl"
void voxy_emitFragment(VoxyFragmentParameters parameters);
#ifdef VOXY_TRANSLUCENT
// The game's depth at its translucent stage: its opaque terrain, nearer than
// any LOD; the translucent LODs give way to it, as the pack's composites do.
uniform sampler2D u_gameDepth;
#endif

// The Voxy contract's faces: axis face >> 1 (0 Y, 1 Z, 2 X), sign face & 1;
// ours: axis direction >> 1 (0 X, 1 Y, 2 Z), the same sign bit.
uint voxyFace(uint direction) {
    if (direction >= 6u) {
        return 1u; // plants' diagonals: up, as their light
    }
    uint axis = direction >> 1;
    uint voxyAxis = axis == 0u ? 2u : axis - 1u;
    return (voxyAxis << 1) | (direction & 1u);
}
#else
out vec4 fragColor;
#endif

#include "biome_tint.glsl"

const uint TILES_PER_ROW = 128u;
const float TILE = 16.0;
// Level 0 to 7: red, orange, yellow, green, blue, then the far preview's (E.3b) purple, pink, white.
const vec3 LEVEL_COLORS[8] = vec3[](vec3(1.0, 0.15, 0.1), vec3(1.0, 0.55, 0.0), vec3(1.0, 0.95, 0.1),
                                    vec3(0.2, 0.85, 0.2), vec3(0.2, 0.45, 1.0), vec3(0.6, 0.25, 0.9),
                                    vec3(1.0, 0.4, 0.75), vec3(1.0, 1.0, 1.0));


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

vec3 toLinear(vec3 c) {
    return pow(c, vec3(2.2));
}

// One texel of a tile, the mip level chosen from the unwrapped coordinates
// (fract() alone would jump to the smallest mip at every block edge).
vec4 tile(uint index, vec2 f, vec2 gx, vec2 gy) {
    vec2 origin = vec2(float(index % TILES_PER_ROW), float(index / TILES_PER_ROW)) * TILE;
    vec2 uv = (origin + f * TILE) / u_atlasSize;
    return textureGrad(u_atlas, uv, gx * TILE / u_atlasSize, gy * TILE / u_atlasSize);
}

void main() {
#ifdef EARLY_Z
    // Opaque faces passed the pre-pass's discards already (GL_EQUAL).
    bool prepassed = !u_translucent;
#else
    bool prepassed = false;
#endif
    // The block this face belongs to: a hair inside its voxel, so faces on a
    // section border test the section of their own voxel.
    vec3 normal = vec3(0.0);
    // Plants' diagonals (directions 6 and 7) lie inside their voxel: no offset.
#ifdef PLANTS
    const bool plant = true;
#else
    bool plant = v_direction >= 6u;
#endif
    if (!plant) {
        normal[v_direction >> 1] = (v_direction & 1u) != 0u ? 1.0 : -1.0;
    }
    vec3 inside = v_relative + u_cameraFrac - normal * 0.01;
#ifdef VOXY_TRANSLUCENT
    if (texelFetch(u_gameDepth, ivec2(gl_FragCoord.xy), 0).r < 1.0) {
        discard;
    }
#endif
#ifndef SOLID
    if (!prepassed && v_handoff != 0u) {
        ivec3 block = u_cameraBlock + ivec3(floor(inside));
        if ((v_handoff & 1u) != 0u && vanillaDraws(block) || (v_handoff & 2u) != 0u && previewHiddenAt(block)) {
            discard;
        }
    }
#endif
    float distance = length(v_relative);
#ifdef PLANTS
    // Holes first, before the tint and the light: most of a plant's sprite is empty.
    vec2 pf = fract(v_texCoord);
    vec2 pgx = dFdx(v_texCoord);
    vec2 pgy = dFdy(v_texCoord);
    if (v_tiles == 0u || distance >= u_textureEnd || tile((v_tiles & 0xFFFFu) - 1u, pf, pgx, pgy).a < 0.5) {
        discard;
    }
#endif
#ifdef DEPTH_ONLY
#ifndef SOLID
    // The cutout holes of textured faces, as the shaded opaque pass below;
    // only faces whose texture has holes read it.
    vec2 f = fract(v_texCoord);
    vec2 gx = dFdx(v_texCoord);
    vec2 gy = dFdy(v_texCoord);
    if (v_cutout != 0u && v_tiles != 0u && distance < u_textureEnd) {
        float fade = plant ? 0.0 : smoothstep(u_textureEnd * 0.75, u_textureEnd, distance);
        if (mix(tile((v_tiles & 0xFFFFu) - 1u, f, gx, gy).a, 1.0, fade) < 0.5) {
            discard;
        }
    }
    if (plant && (v_tiles == 0u || distance >= u_textureEnd)) {
        discard; // a plant without its texture would be a solid rectangle
    }
#endif
#ifndef VOXY
    fragColor = vec4(0.0);
#endif
#else
    vec3 tint = vec3(0.0);
    vec3 color = v_flat.rgb;
    // The untinted and tinted parts apart, for a pack that applies the tint itself.
    vec3 untintedPart = v_flat.rgb;
    vec3 tintedPart = v_tinted;
    if (v_tintKind != NO_TINT) {
        // The column this face belongs to: a hair inside its voxel, as above.
#ifdef PLANTS
        tint = v_plantTint; // one block wide: the same over the whole quad (lod.vert)
#else
        vec3 voxel = v_voxel - normal * 0.01;
        tint = toLinear(blendedTint(voxel.xz, v_slot, v_biome, v_tintKind, v_level));
#endif
        color += v_tinted * tint;
    }
    float alpha = v_flat.a;
    // Derivatives outside the branch: they are undefined in divergent control flow.
    vec2 f = fract(v_texCoord);
    vec2 gx = dFdx(v_texCoord);
    vec2 gy = dFdy(v_texCoord);
    if (v_tiles != 0u && distance < u_textureEnd) {
        vec4 base = tile((v_tiles & 0xFFFFu) - 1u, f, gx, gy);
        vec3 textured = base.rgb;
        vec3 overlay = vec3(0.0);
        if ((v_tiles >> 16) != 0u) {
            overlay = tile((v_tiles >> 16) - 1u, f, gx, gy).rgb;
            textured += overlay * tint;
        }
        // Plants keep their holes up to the end of the textures, where they stop.
        float fade = plant ? 0.0 : smoothstep(u_textureEnd * 0.75, u_textureEnd, distance);
        // Opaque pass: the holes of textured faces stay open (glass panes, iron bars),
        // as vanilla's cutout; untextured faces stay whole.
        if (!u_translucent && !prepassed && v_cutout != 0u && mix(base.a, 1.0, fade) < 0.5) {
            discard;
        }
        color = mix(textured, color, fade);
        untintedPart = mix(base.rgb, untintedPart, fade);
        tintedPart = mix(overlay, tintedPart, fade);
        alpha = mix(base.a, alpha, fade);
    }
    if (plant && (v_tiles == 0u || distance >= u_textureEnd)) {
        discard;
    }
    // As vanilla's terrain: texture times shade times lightmap, in sRGB.
    float ao = (u_ambientOcclusion && v_occluders != 0u && !u_translucent) ? ambientOcclusion() : 1.0;
#ifdef VOXY
    // The pack lights and fogs it: the texture with its tint, the occlusion
    // baked in as vanilla's vertex colors carry it, and the light levels.
    VoxyFragmentParameters parameters;
    if (v_tintKind != NO_TINT && max(untintedPart.r, max(untintedPart.g, untintedPart.b)) < 1.0 / 255.0) {
        // All of the face is tinted (water, leaves, grass tops): the sample and
        // the biome tint apart, as vanilla's texture and vertex color.
        parameters.sampledColour = vec4(pow(min(tintedPart, vec3(1.0)), vec3(1.0 / 2.2)) * ao, alpha);
        parameters.tinting = vec4(pow(tint, vec3(1.0 / 2.2)), 1.0);
    } else {
        parameters.sampledColour = vec4(pow(min(color, vec3(1.0)), vec3(1.0 / 2.2)) * ao, alpha);
        parameters.tinting = vec4(1.0);
    }
    parameters.uv = v_texCoord;
    parameters.lightMap = (vec2(v_lightmap) + 0.5) / 16.0;
    parameters.face = voxyFace(v_direction);
    parameters.customId = v_customId;
    voxy_emitFragment(parameters);
#else
    // Vanilla samples its lightmap filtered at level / 16 (minecraft_sample_lightmap),
    // between two texels: full light reads halfway between levels 14 and 15.
    vec2 lightUv = clamp(vec2(v_lightmap) / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    color = pow(min(color, vec3(1.0)), vec3(1.0 / 2.2)) * (v_light * ao) * texture(u_lightmap, lightUv).rgb;
    if (u_debugLevels) {
        color = mix(color, LEVEL_COLORS[min(v_level, 7)], 0.65);
    }
    // As vanilla's fog_distance: sphere, or cylinder.
    float fogDistance = u_fogCylinder ? max(length(v_relative.xz), abs(v_relative.y)) : distance;
    float fog = smoothstep(u_fog.x, u_fog.y, fogDistance);
    fragColor = vec4(mix(color, u_fogColor.rgb, fog), u_translucent ? alpha : 1.0);
#endif
#endif
}
