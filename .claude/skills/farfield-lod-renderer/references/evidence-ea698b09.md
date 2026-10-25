A plant without collision standing in its fluid (`BushBlock` or `GrowingPlantBlock` with a fluid state: kelp, seagrass, a waterlogged propagule) packs as `MaterialTable.packSubmerged()`: a translucent fluid, as before, with a flag.

ADR-064: `minecraft:kelp_plant` (the kelp's body) a translucent non-fluid: its outline is a full block, so the full-block test took it before the waterlogged one