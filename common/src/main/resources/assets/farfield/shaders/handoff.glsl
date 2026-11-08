// Where the LODs give way to the game's terrain and to the real chunks
// that took over from the preview, shared by lod.frag and the shader pack
// bridge of the DH contract (dh_bridge.frag, ADR-119). Declare
// u_cameraBlock and u_cameraFrac before including it.

uniform int u_renderDistance;  // the game's render distance in chunks
uniform bool u_sodiumRule;     // Sodium (or a fork) draws the terrain, else vanilla
uniform ivec3 u_gridMin;       // section coordinates of the compiled-sections grid's corner
uniform ivec2 u_gridSize;      // its width (x and z) and height, in sections

// Sections the terrain renderer has compiled: bit ((z * width + x) * height + y).
layout(std430, binding = 5) readonly buffer DrawnSections {
    uint drawnSections[];
};
// Chunk columns the real terrain took over from the preview this frame, before
// the preview's own meshes left them (LodRenderer.updateHandOver): bit
// (z * 256 + x) of a 256-chunk grid whose corner is u_hiddenMin.
layout(std430, binding = 8) readonly buffer PreviewHidden {
    uint previewHidden[];
};
uniform ivec2 u_hiddenMin;
const int HIDDEN_SIDE = 256;

bool previewHiddenAt(ivec3 block) {
    ivec2 g = (block.xz >> 4) - u_hiddenMin;
    if (any(lessThan(g, ivec2(0))) || any(greaterThanEqual(g, ivec2(HIDDEN_SIDE)))) {
        return false;
    }
    uint bit = uint(g.y * HIDDEN_SIDE + g.x);
    return ((previewHidden[bit >> 5] >> (bit & 31u)) & 1u) != 0u;
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
    // Vanilla's section graph (SectionOcclusionGraph.getRelativeFrom) never
    // reaches a section more than the render distance above or below the
    // camera's section: compiled, but not drawn (holes under a high camera).
    if (abs(section.y - (u_cameraBlock.y >> 4)) > u_renderDistance) {
        return false;
    }
    // Vanilla's ChunkTrackingView.isInViewDistance, around the camera's chunk.
    ivec2 c = abs(section.xz - (u_cameraBlock.xz >> 4)) - 1;
    c = max(c, ivec2(0));
    int far = max(c.x, c.y);
    int near = min(c.x, c.y);
    return near * near + far * far < u_renderDistance * u_renderDistance;
}

