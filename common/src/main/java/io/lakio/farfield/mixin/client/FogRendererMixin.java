package io.lakio.farfield.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.lakio.farfield.client.render.LodRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hand-off with vanilla terrain (ADR-011): while LODs are drawn, the normal
 * terrain fog (which fades near terrain into the sky just before the LODs
 * start, leaving a ring) moves out to the LOD fog. Water, lava, powder snow,
 * blindness, thick dimension fog, and fog a mod already changed are left
 * alone: vanilla's normal branch ends the fog exactly at the far plane.
 * Optional: without it the seam keeps its fog ring.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {

    @Inject(method = "setupFog", at = @At("TAIL"), require = 0)
    private static void farfield$lodFog(Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance,
                                        boolean shouldCreateFog, float partialTick, CallbackInfo ci) {
        if (fogMode == FogRenderer.FogMode.FOG_TERRAIN && !shouldCreateFog
                && camera.getFluidInCamera() == FogType.NONE
                && RenderSystem.getShaderFogEnd() == farPlaneDistance
                && LodRenderer.drawsBeyondVanilla()) {
            RenderSystem.setShaderFogStart(LodRenderer.fogStart());
            RenderSystem.setShaderFogEnd(LodRenderer.fogEnd());
        }
    }
}
