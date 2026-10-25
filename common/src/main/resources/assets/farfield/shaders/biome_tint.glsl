// Biome tints shared by lod.vert and lod.frag (GlProgram inlines #include
// lines): plants take theirs once per vertex, other faces per fragment.

uniform int u_biomeBlend;      // vanilla's Biome Blend option: 0 off, else the radius in blocks

// Per biome slot (biome id + 1, 0 for unknown) and tint kind: ABGR8 sRGB (BiomeTints).
layout(std430, binding = 3) readonly buffer BiomeTints {
    uint tints[];
};
const uint TINT_KINDS = 8u;

// Surface biome per 4x4-voxel column and section slot (SurfaceBiomes): 64
// 16-bit entries, index cellZ * 8 + cellX, biome slot + 1 or 0 when unknown.
layout(std430, binding = 7) readonly buffer BiomeColumns {
    uint biomeColumns[];
};

// Slots of each section's 26 same-level neighbors: 27 per slot,
// (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1), slot + 1 or 0 when not resident.
layout(std430, binding = 6) readonly buffer NeighborSlots {
    uint neighborSlots[];
};

// Surface biome slot + 1 of a column, in cells from the section's corner
// (-8..15: this section or a same-level neighbor), 0 when unknown.
uint columnBiome(uint home, ivec2 cell) {
    uint slot = home;
    ivec2 o = cell >> 3; // -1, 0 or 1 per axis
    if (o != ivec2(0)) {
        uint n = neighborSlots[home * 27u + uint((o.x + 1) * 9 + 3 + (o.y + 1))];
        if (n == 0u) {
            return 0u;
        }
        slot = n - 1u;
        cell -= o * 8;
    }
    uint i = uint(cell.y * 8 + cell.x);
    return (biomeColumns[slot * 32u + (i >> 1)] >> ((i & 1u) * 16u)) & 0xFFFFu;
}

vec3 biomeTint(uint slot, uint kind) {
    return unpackUnorm4x8(tints[slot * TINT_KINDS + kind]).rgb;
}

// The biome tint at a section-local voxel position, sRGB: as vanilla's
// biome blending, the mean tint over a square of 2r + 1 blocks around it
// (r = u_biomeBlend), here with each block taking its column's surface biome
// and the window sliding continuously instead of block by block. Unknown
// columns, and the fragment's own column, take the quad's biome.
vec3 blendedTint(vec2 p, uint home, uint biome, uint kind, int level) {
    if (u_biomeBlend <= 0) {
        return biomeTint(biome, kind);
    }
    float h = (float(u_biomeBlend) + 0.5) / float(1 << level); // half window, in voxels
    vec2 lo = p - h;
    vec2 hi = p + h;
    ivec2 c0 = ivec2(floor(lo / 4.0));
    ivec2 c1 = ivec2(floor(hi / 4.0));
    ivec2 own = ivec2(floor(p / 4.0));
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int cz = c0.y; cz <= c1.y; cz++) {
        float wz = min(hi.y, float(cz * 4 + 4)) - max(lo.y, float(cz * 4));
        for (int cx = c0.x; cx <= c1.x; cx++) {
            float w = wz * (min(hi.x, float(cx * 4 + 4)) - max(lo.x, float(cx * 4)));
            if (w <= 0.0) {
                continue;
            }
            uint b = ivec2(cx, cz) == own ? 0u : columnBiome(home, ivec2(cx, cz));
            sum += biomeTint(b == 0u ? biome : b - 1u, kind) * w;
            total += w;
        }
    }
    return total > 0.0 ? sum / total : biomeTint(biome, kind);
}
