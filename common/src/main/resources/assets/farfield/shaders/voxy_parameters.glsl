// The fragment parameters of the Voxy contract (ADR-113), as the shader
// packs read them (docs/analysis/shader-lod-contracts.md): declared the same
// way in lod.frag and in the pack's unit linked beside it.
struct VoxyFragmentParameters {
    vec4 sampledColour; // the block texture's sample, sRGB
    vec4 tinting;       // biome tint, multiplied by the packs
    vec2 uv;            // texture coordinate
    vec2 lightMap;      // block, sky: (level + 0.5) / 16
    uint face;          // axis face >> 1 (0 Y, 1 Z, 2 X), sign face & 1 (1 positive)
    uint customId;      // the block's id in the pack's block.properties
};
