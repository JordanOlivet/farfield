#version 460 core

in vec4 v_color;
in vec3 v_relative;

uniform float u_vanillaRadius; // vanilla terrain is drawn within this horizontal and vertical distance
uniform vec4 u_fogColor;
uniform vec2 u_fog;            // start, end
uniform bool u_translucent;    // translucent pass: blend with the face's coverage

out vec4 fragColor;

void main() {
    // Sodium draws a section only within the render distance horizontally
    // and vertically: far below or above the camera, LODs must show.
    float horizontal = length(v_relative.xz);
    if (horizontal < u_vanillaRadius && abs(v_relative.y) < u_vanillaRadius) {
        discard;
    }
    float fog = smoothstep(u_fog.x, u_fog.y, length(v_relative));
    fragColor = vec4(mix(v_color.rgb, u_fogColor.rgb, fog), u_translucent ? v_color.a : 1.0);
}
