  BiomeBlendTest > interpolationStaysCloseToThePerFragmentBlend() FAILED
    org.opentest4j.AssertionFailedError: worst 14.776469 of 255 ==> expected: <true> but was: <false>
...
    BiomeBlend interpolation over a border: worst 14.776469 of 255, 6 % of the step between the biomes
BUILD SUCCESSFUL in 10s
...
// Per section slot: per 4x4-voxel column and tint kind, the other columns'
// tints blended over the window around the column's centre, as blendedTint
// below (BiomeBlend, on the CPU when a section or a neighbor arrives): ABGR8
// premultiplied, alpha the share of the window they cover. Then the radius
// + 1 they were made with, 0 when the slot holds none.