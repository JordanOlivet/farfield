- **Fog**: Sodium 0.8 reads the frame's `FogData` for its own terrain
  shader; the 1.21.11 fog mixin changed only the values uploaded, so the
  game's terrain kept vanilla's fog under Sodium. The mixin now changes
  `FogData` once the game has filled it (after `renderDistanceEnd` is set).