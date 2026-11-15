package io.lakio.farfield.mixin.sodium;

import io.lakio.farfield.client.render.LodRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the LOD renderer when a Sodium section may change its built state:
 * Sodium sets it only in {@code setInfo} (a build's result, an empty
 * section, a removal) and clears it in {@code delete} (read on Sodium
 * 0.8.13). The compiled-sections grid then tests only the columns told so,
 * instead of every section of its outer ring each frame. Optional: without
 * it, the grid tests as before ({@code LodRenderer.sodiumSectionChanged}
 * never called).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", remap = false)
public abstract class RenderSectionMixin {

    @Shadow
    @Final
    private int chunkX;

    @Shadow
    @Final
    private int chunkZ;

    @Inject(method = "setInfo", at = @At("HEAD"), require = 0)
    private void farfield$infoChanged(CallbackInfoReturnable<Boolean> cir) {
        LodRenderer.sodiumSectionChanged(chunkX, chunkZ);
    }

    @Inject(method = "delete", at = @At("HEAD"), require = 0)
    private void farfield$deleted(CallbackInfo ci) {
        LodRenderer.sodiumSectionChanged(chunkX, chunkZ);
    }
}
