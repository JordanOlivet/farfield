- **Decision**: in the mask, a real chunk touching a preview chunk
  (diagonals included; across a tile's edge, the neighbor tile's mask in
  use decides, none yet counts as preview) takes the lowest solid column
  top over the chunk, read at the finest level that holds it, 0 to 3
  (`RealGround.lowest`). Fluids are not ground. The preview's wall beside
  it reaches the pit; where the real ground is higher it hides the wall.
  Chunks away from the preview keep the level-4 value: no preview column
  stands beside them.