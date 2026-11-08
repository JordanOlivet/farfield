// The geometry of one corner of a LOD quad, shared by lod.vert and the
// shader pack bridge of the DH contract (dh_bridge.vert, ADR-119): vertex
// pulling of 8-byte quads (io.lakio.farfield.core.mesh.Quad), one draw
// command per section, gl_BaseInstance selects the section, gl_VertexID / 4
// the quad and gl_VertexID % 4 the corner (indexed draws: gl_VertexID is the
// shared index buffer's value plus the command's base vertex, 4 vertices and
// 2 triangles per quad).

layout(std430, binding = 0) readonly buffer Quads {
    uvec2 quads[];
};

struct Section {
    ivec4 originLevel; // origin in blocks (xyz), level (w)
    uvec4 info;        // x: first quad, y: quad count, z: 1 if its opaque voxels are in Occupancy, w: 1 for the preview
};

layout(std430, binding = 1) readonly buffer Sections {
    Section sections[];
};

// Per block-state id and face direction (MaterialColors): an untinted
// color (alpha: coverage), a biome-tinted color (alpha byte: tint kind,
// top bit: the texture has holes to cut out),
// both ABGR8 in sRGB, and the face's atlas tiles (untinted + 1 low 16 bits,
// tinted + 1 high 16 bits, 0 for none); then the height of the block's top
// in blocks, float bits (snow layers, slabs, carpets, fluids at vanilla's 8/9);
// then 8 boxes of a shaped block (ShapeBoxes: min xyz, max xyz in sixteenths,
// 5 bits each; 0 past its last box and for other blocks).
layout(std430, binding = 2) readonly buffer Colors {
    uint colors[];
};

const uint COLOR_STRIDE = 27u;
const uint HEIGHT_OFFSET = 18u;
const uint BOX_OFFSET = 19u;

uniform ivec3 u_cameraBlock;
uniform vec3 u_cameraFrac;
uniform bool u_translucent; // translucent pass (water, glass, ice)

// Inset of a plant's diagonals in its voxel, as vanilla's cross model (0.8 to 15.2 sixteenths).
const float CROSS_INSET = 0.05;

struct LodCorner {
    Section section;
    uvec2 quad;
    uvec3 voxel;      // the quad's first voxel in the section
    uint direction;   // 0 to 5: -X +X -Y +Y -Z +Z; 6 and 7: a plant's diagonals
    uint material;
    bool plant;
    vec3 position;    // section-local, in voxels
    vec3 occlusion;   // section-local, in voxels: where ambient occlusion reads the voxel grid
    vec3 relative;    // in blocks, from the camera
    vec2 texCoord;    // block units along the face's U and V axes: one tile per block
};

LodCorner lodCorner(uint sectionIndex, uint vertexId) {
    LodCorner c;
    Section s = sections[sectionIndex];
    c.section = s;
    uint corner = vertexId & 3u;
    uvec2 q = quads[s.info.x + vertexId / 4u];
    c.quad = q;
    uint lo = q.x;
    uvec3 voxel = uvec3(lo & 31u, (lo >> 5) & 31u, (lo >> 10) & 31u);
    c.voxel = voxel;
    uint sizeU = ((lo >> 15) & 31u) + 1u;
    uint sizeV = ((lo >> 20) & 31u) + 1u;
    uint direction = (lo >> 25) & 7u;
    c.direction = direction;
    uint material = (lo >> 28) | ((q.y & 0xFFFu) << 4);
    c.material = material;

    bool plant = direction >= 6u;
    c.plant = plant;
    uint axis = plant ? 0u : direction >> 1;
    uint uAxis = (axis + 1u) % 3u;
    uint vAxis = (axis + 2u) % 3u;
    vec3 p = vec3(voxel);
    p[axis] += float(direction & 1u);
    // (U, V) corners wind counter-clockwise seen from +axis (U x V = +axis);
    // faces toward -axis swap them so every face winds counter-clockwise from
    // outside and back faces can be culled.
    bool negative = (direction & 1u) == 0u;
    uint cu = negative ? corner >> 1 : corner & 1u;
    uint cv = negative ? corner & 1u : corner >> 1;
    p[uAxis] += float(cu * sizeU);
    p[vAxis] += float(cv * sizeV);

    float scale = float(1 << s.originLevel.w);
    // Shaped blocks on the finest level (stairs, fences, walls, panes): the
    // quad is one face of the box its fill bits select. Other quads select
    // box 7 there, which is 0 for blocks that are not shaped.
    uint box = s.originLevel.w == 0 ? colors[material * COLOR_STRIDE + BOX_OFFSET + (q.y >> 29)] : 0u;
    // Voxels lower than their cell: on the finest level, partial blocks take
    // their block's height (snow layers, slabs, carpets); on coarser levels,
    // every voxel takes its fill in eighths, so the sea and snow stand where
    // they are instead of at the top of the coarse voxel.
    // Ambient occlusion reads the voxel grid at the face's unlowered position.
    c.occlusion = p;
    bool top = !plant && (direction == 3u
            || (axis == 0u && cu == 1u)
            || (axis == 2u && cv == 1u));
    vec2 crossTex = vec2(0.0);
    if (plant) {
        // A vertical quad along one diagonal of the voxel (finest level only),
        // wound toward the camera so back-face culling keeps it from both sides.
        vec2 from = direction == 6u ? vec2(CROSS_INSET) : vec2(1.0 - CROSS_INSET, CROSS_INSET);
        vec2 to = direction == 6u ? vec2(1.0 - CROSS_INSET) : vec2(CROSS_INSET, 1.0 - CROSS_INSET);
        vec2 center = vec2(voxel.xz) + (from + to) * 0.5;
        vec2 toCamera = vec2(u_cameraBlock.xz - s.originLevel.xz) + u_cameraFrac.xz - center;
        // Counter-clockwise from the side of (-dz, dx) with U along the diagonal and V up.
        vec2 d = to - from;
        float t = float(corner & 1u);
        if (dot(vec2(-d.y, d.x), toCamera) < 0.0) {
            t = 1.0 - t;
        }
        float h = float(corner >> 1);
        vec2 xz = mix(from, to, t);
        p = vec3(voxel) + vec3(xz.x, h, xz.y);
        c.occlusion = p;
        // The plant's sprite lies on face 0 (MaterialColors): columns up, rows along the diagonal.
        crossTex = vec2(h, t);
    } else if (box != 0u) {
        vec3 lo = vec3(uvec3(box, box >> 5, box >> 10) & 31u) / 16.0;
        vec3 hi = vec3(uvec3(box >> 15, box >> 20, box >> 25) & 31u) / 16.0;
        p = vec3(voxel);
        p[axis] += negative ? lo[axis] : hi[axis];
        p[uAxis] += cu == 1u ? hi[uAxis] : lo[uAxis];
        p[vAxis] += cv == 1u ? hi[vAxis] : lo[vAxis];
        // Ambient occlusion as for the voxel's own face on that side.
        c.occlusion = p;
        c.occlusion[axis] = float(voxel[axis]) + float(direction & 1u);
    } else if (top) {
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
    c.position = p;
    c.relative = vec3(s.originLevel.xyz - u_cameraBlock) + p * scale - u_cameraFrac;
    // Section-local block coordinates: the section origin is a multiple of
    // its size, so their fractions are the world's.
    c.texCoord = plant ? crossTex : vec2(p[uAxis], p[vAxis]) * scale;
    return c;
}
