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

layout(std430, binding = 2) readonly buffer Colors {
    uint colors[]; // ABGR8 per block-state id
};

uniform mat4 u_viewProj;    // the game's projection * view rotation
uniform ivec3 u_cameraBlock;
uniform vec3 u_cameraFrac;
uniform vec2 u_depthRange;  // near, far of the LOD depth mapping
uniform float u_brightness; // sky brightness (day/night, weather), as vanilla sky light

out vec4 v_color;
out vec3 v_relative;

const uint CORNER_U[6] = uint[](0u, 1u, 1u, 0u, 1u, 0u);
const uint CORNER_V[6] = uint[](0u, 0u, 1u, 0u, 1u, 1u);
const float SHADE[6] = float[](0.6, 0.6, 0.5, 1.0, 0.8, 0.8);

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
    p[uAxis] += float(CORNER_U[corner] * sizeU);
    p[vAxis] += float(CORNER_V[corner] * sizeV);

    float scale = float(1 << s.originLevel.w);
    v_relative = vec3(s.originLevel.xyz - u_cameraBlock) + p * scale - u_cameraFrac;
    gl_Position = u_viewProj * vec4(v_relative, 1.0);
    // Keep the game's x/y/w, but map depth over our own range (far beyond
    // the vanilla far plane): standard perspective depth computed from w.
    float n = u_depthRange.x;
    float f = u_depthRange.y;
    gl_Position.z = gl_Position.w * (f + n) / (f - n) - 2.0 * f * n / (f - n);

    vec4 color = unpackUnorm4x8(colors[material]);
    v_color = vec4(color.rgb * SHADE[direction] * u_brightness, 1.0);
}
