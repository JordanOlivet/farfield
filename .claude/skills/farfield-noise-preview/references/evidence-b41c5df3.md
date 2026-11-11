- **Decision**: the generator's own rule objects, evaluated on the one
  column a cell keeps (`SurfaceColumns`): one `SurfaceRules.Context` and
  one rule tree per tile, the generator's `surfaceRule()` (TerraBlender's
  namespaced rules included); per cell, the context's column and block
  updates as `buildSurface` makes them