# Vanilla package moves, 26.1 to 26.3

Checked against the 26.3 source tree (2026-11-19). The 26.1 source is the old side.

- `com.mojang.blaze3d.opengl.*` (GlStateManager, GlTexture, GlProgram, DirectStateAccess, GlCommandEncoder) -> `com.mojang.renderpearl.backend.opengl.*`
- `com.mojang.blaze3d.systems.GpuDevice` -> `com.mojang.renderpearl.api.device.GpuDevice`
- `com.mojang.blaze3d.systems.GpuDeviceBackend` -> `com.mojang.renderpearl.backend.api.GpuDeviceBackend`
- `GpuTexture` -> `com.mojang.renderpearl.api.textures.GpuTexture`; `GpuBufferSlice` -> `com.mojang.renderpearl.api.buffers.GpuBufferSlice`
- Unchanged: `com.mojang.blaze3d.systems.RenderSystem`, `com.mojang.blaze3d.pipeline.RenderTarget`
- `net.minecraft.world.level.levelgen.DensityFunction` and `DensityFunctions` -> `net.minecraft.world.level.levelgen.densityfunction.*`
- `levelgen.SurfaceRules` and `levelgen.SurfaceSystem` no longer exist; surface rules are in `levelgen.material` (`MaterialRules`, `MaterialSystem`, `MaterialRuleContext`, `condition/*`).
- `net.minecraft.util.KeyDispatchDataCodec`: not found by name in the 26.3 tree; search its users' call sites for the replacement before guessing.
