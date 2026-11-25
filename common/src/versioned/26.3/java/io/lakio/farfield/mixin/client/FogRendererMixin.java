package io.lakio.farfield.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.compat.GameFog;
import io.lakio.farfield.core.config.RenderConfig;
import io.lakio.farfield.lod.RenderSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hand-off with vanilla terrain (ADR-011), Minecraft 26.1 as 1.21.11 (ADR-165, ADR-171): the
 * fog is two fogs, the environment's (spherical: 0 to 1024 blocks in the
 * Overworld, 768 in heavy rain, 10 to 96 in the Nether) and the render
 * distance's (cylindrical, the last tenth before the far plane). While LODs
 * are drawn both move out to the player's fog distance (ADR-034); a thick
 * environment fog (the Nether) moves out to its own distance, unless set to
 * vanilla, and then, in water, lava, powder snow, blindness or darkness, the
 * LODs take the game's fog (ADR-033). A boss bar's fog, as thick, is not
 * created while LODs are drawn ({@code BossHealthOverlayMixin}, ADR-179). The
 * fog the LODs read goes to {@link GameFog}.
 *
 * <p>The frame's {@link FogData} itself is changed, once the game has filled
 * it, not the values it uploads: Sodium reads that object for its own terrain
 * shader, and with the uploaded values alone its terrain kept vanilla's fog
 * (ADR-170). Optional: without it the seam keeps its fog ring.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {

    /** An environment fog ending closer than this is thick (the Nether's ends at 96, rain's at 768 or more). */
    private static final float THICK_END = 512f;

    @Inject(method = "setupFog", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER), require = 0)
    private void farfield$lodFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darkenWorldAmount,
                                 ClientLevel level, CallbackInfoReturnable<FogData> cir, @Local FogData data) {
        // Since 26.1 the fog's color is part of its data (ADR-171).
        Vector4f color = data.color;
        boolean fluid = camera.getFluidInCamera() != FogType.NONE;
        boolean effect = camera.entity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
        float farPlane = data.renderDistanceEnd;
        boolean thick = !fluid && !effect && data.environmentalEnd < THICK_END;
        boolean normal = !fluid && !effect && !thick;
        int distance = thick ? RenderSettings.thickFogDistance() : RenderSettings.fogDistance();
        boolean pushOut = (normal || (thick && distance != RenderConfig.FOG_VANILLA))
                && LodRenderer.drawsBeyondVanilla();
        if (pushOut) {
            LodRenderer.pushFog(distance, farPlane, normal);
            data.environmentalStart = LodRenderer.pushedFogStart();
            data.environmentalEnd = LodRenderer.pushedFogEnd();
            data.renderDistanceStart = data.environmentalStart;
            data.renderDistanceEnd = data.environmentalEnd;
        }
        LodRenderer.setFogState(pushOut, fluid || effect || (thick && !pushOut), thick);
        GameFog.set(data.environmentalStart, data.environmentalEnd, color.x(), color.y(), color.z(), color.w());
    }
}
