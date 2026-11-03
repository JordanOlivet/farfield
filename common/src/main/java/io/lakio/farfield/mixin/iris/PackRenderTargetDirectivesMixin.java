package io.lakio.farfield.mixin.iris;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Render targets colortex16 to colortex19 (ADR-113): packs written for Voxy
 * use them under {@code VOXY} (Photon's far water data in colortex16,
 * Complementary's composites write colortex19), and Iris stops at 15,
 * failing the whole pipeline ("only 16 render targets are supported").
 * Iris creates a target only when a pack uses it.
 */
@Mixin(value = PackRenderTargetDirectives.class, remap = false)
public abstract class PackRenderTargetDirectivesMixin {

    /** The render targets Voxy's packs may use. */
    private static final int FARFIELD$TARGETS = 20;

    @Shadow
    @Final
    @Mutable
    public static Set<Integer> BASELINE_SUPPORTED_RENDER_TARGETS;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void farfield$voxyTargets(CallbackInfo ci) {
        ImmutableSet.Builder<Integer> targets = ImmutableSet.builder();
        targets.addAll(BASELINE_SUPPORTED_RENDER_TARGETS);
        for (int i = 0; i < FARFIELD$TARGETS; i++) {
            targets.add(i);
        }
        BASELINE_SUPPORTED_RENDER_TARGETS = targets.build();
    }
}
