#version 460 core

in vec4 v_color;
in vec3 v_relative;

uniform float u_vanillaRadius; // vanilla terrain is drawn within this horizontal distance
uniform vec4 u_fogColor;
uniform vec2 u_fog;            // start, end

out vec4 fragColor;

void main() {
    float horizontal = length(v_relative.xz);
    if (horizontal < u_vanillaRadius) {
        discard;
    }
    float fog = smoothstep(u_fog.x, u_fog.y, length(v_relative));
    fragColor = vec4(mix(v_color.rgb, u_fogColor.rgb, fog), 1.0);
}
