#version 460 core

in vec3 v_relative;
in vec2 v_texCoord;
flat in vec4 v_flat;
flat in vec3 v_tint;
flat in uint v_tiles;
flat in float v_light;
flat in ivec2 v_lightmap;
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
    color = pow(min(color, vec3(1.0)), vec3(1.0 / 2.2)) * v_light * texelFetch(u_lightmap, v_lightmap, 0).rgb;
    if (u_debugLevels) {
        color = mix(color, LEVEL_COLORS[min(v_level, 4)], 0.65);
    }
    float fog = smoothstep(u_fog.x, u_fog.y, distance);
    fragColor = vec4(mix(color, u_fogColor.rgb, fog), u_translucent ? alpha : 1.0);
}
