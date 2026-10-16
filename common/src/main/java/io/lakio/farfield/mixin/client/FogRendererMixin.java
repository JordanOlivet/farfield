package io.lakio.farfield.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.lod.RenderSettings;
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
 * start, leaving a ring) moves out to the LOD fog. Thick dimension fog (the
 * Nether) moves out too only when the player chose it; otherwise, and in
 * water, lava or powder snow, the LODs take the game's fog (ADR-033).
 * Blindness and fog a mod already changed are left alone. Vanilla's normal
 * branch ends the fog exactly at the far plane, its thick branch at
 * {@code min(far, 192) / 2}. Optional: without it the seam keeps its fog ring.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {

    @Inject(method = "setupFog", at = @At("TAIL"), require = 0)
    private static void farfield$lodFog(Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance,
                                        boolean shouldCreateFog, float partialTick, CallbackInfo ci) {
        if (fogMode != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        boolean fluid = camera.getFluidInCamera() != FogType.NONE;
        boolean thick = !fluid && shouldCreateFog
                && RenderSystem.getShaderFogStart() == farPlaneDistance * 0.05F
                && RenderSystem.getShaderFogEnd() == Math.min(farPlaneDistance, 192.0F) * 0.5F;
        boolean normal = !fluid && !shouldCreateFog && RenderSystem.getShaderFogEnd() == farPlaneDistance;
        boolean pushOut = normal || (thick && RenderSettings.extendDimensionFog());
        if (pushOut && LodRenderer.drawsBeyondVanilla()) {
            RenderSystem.setShaderFogStart(LodRenderer.fogStart());
            RenderSystem.setShaderFogEnd(LodRenderer.fogEnd());
        }
        LodRenderer.setFollowVanillaFog(fluid || (thick && !pushOut));
    }
}
