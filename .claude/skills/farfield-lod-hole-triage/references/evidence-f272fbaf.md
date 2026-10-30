- **Context**: the user's Fabric pack, a single-player world from an old
  map: the sky through the ground in many places, "notamment sur des
  grottes". The store was whole (`storeHoles`: 15,063 full chunks, 0
  without voxels; level 0 matched the region files column by column
  around the hole). The mask of a real chunk holds one ground per chunk,
  read from level 4: the top of its one voxel's fill, near the chunk's
  highest columns (80 for a chunk whose columns top at 55 to 84). A
  preview column beside it goes down only to that ground, and is hollow
  under its skin; the view passed over a real pit at 56, at the edge of
  the real terrain, into that hollow, and met no face.
- **Decision**: in the mask, a real chunk touching a preview chunk
  (diagonals included; across a tile's edge, the neighbor tile's mask in
  use decides, none yet counts as preview) takes the lowest solid column
  top over the chunk, read at the finest level that holds it, 0 to 3
  (`RealGround.lowest`).