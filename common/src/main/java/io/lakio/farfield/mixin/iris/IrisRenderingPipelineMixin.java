package io.lakio.farfield.mixin.iris;

import io.lakio.farfield.client.render.LodRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The translucent LODs under a shader pack where Voxy draws them (ADR-113):
 * after the game's opaque terrain and entities, before the pack's deferred
 * passes, which read them (BSL composites colortex16 in deferred1). Iris
 * runs those passes at the start of {@code beginTranslucents}.
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class IrisRenderingPipelineMixin {

    @Inject(method = "beginTranslucents", at = @At("HEAD"))
    private void farfield$translucentLods(CallbackInfo ci) {
        LodRenderer.renderTranslucent(true);
    }
}
