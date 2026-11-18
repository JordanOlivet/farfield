package io.lakio.farfield.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.compat.GameFog;
import io.lakio.farfield.core.config.RenderConfig;
import io.lakio.farfield.lod.RenderSettings;
import java.nio.ByteBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hand-off with vanilla terrain (ADR-011), Minecraft 1.21.11 (ADR-165): the
 * fog is two fogs, the environment's (spherical: 0 to 1024 blocks in the
 * Overworld, 768 in heavy rain, 10 to 96 in the Nether) and the render
 * distance's (cylindrical, the last tenth before the far plane). While LODs
 * are drawn both move out to the player's fog distance (ADR-034); a thick
 * environment fog (the Nether, the boss fog) moves out to its own distance,
 * unless set to vanilla, and then, in water, lava, powder snow, blindness
 * or darkness, the LODs take the game's fog (ADR-033). The fog the LODs read
 * goes to {@link GameFog}. Optional: without it the seam keeps its fog ring.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {

    /** An environment fog ending closer than this is thick (the Nether's ends at 96, rain's at 768 or more). */
    private static final float THICK_END = 512f;

    @WrapOperation(method = "setupFog", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"),
            require = 0)
    private void farfield$lodFog(FogRenderer renderer, ByteBuffer buffer, int offset, Vector4f color,
                                 float environmentalStart, float environmentalEnd, float renderDistanceStart,
                                 float renderDistanceEnd, float skyEnd, float cloudEnd, Operation<Void> original) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        boolean fluid = camera.getFluidInCamera() != FogType.NONE;
        boolean effect = camera.entity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
        float farPlane = renderDistanceEnd;
        boolean thick = !fluid && !effect && environmentalEnd < THICK_END;
        boolean normal = !fluid && !effect && !thick;
        int distance = thick ? RenderSettings.thickFogDistance() : RenderSettings.fogDistance();
        boolean pushOut = (normal || (thick && distance != RenderConfig.FOG_VANILLA))
                && LodRenderer.drawsBeyondVanilla();
        if (pushOut) {
            LodRenderer.pushFog(distance, farPlane, normal);
            environmentalStart = LodRenderer.pushedFogStart();
            environmentalEnd = LodRenderer.pushedFogEnd();
            renderDistanceStart = environmentalStart;
            renderDistanceEnd = environmentalEnd;
        }
        LodRenderer.setFogState(pushOut, fluid || effect || (thick && !pushOut), thick);
        GameFog.set(environmentalStart, environmentalEnd, color.x(), color.y(), color.z(), color.w());
        original.call(renderer, buffer, offset, color, environmentalStart, environmentalEnd, renderDistanceStart,
                renderDistanceEnd, skyEnd, cloudEnd);
    }
}
