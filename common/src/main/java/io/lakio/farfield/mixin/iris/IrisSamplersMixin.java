package io.lakio.farfield.mixin.iris;

import com.google.common.collect.ImmutableSet;
import io.lakio.farfield.client.render.iris.IrisLod;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/** The LOD depth textures of the Voxy contract, beside Iris's DH ones (ADR-113). */
@Mixin(value = IrisSamplers.class, remap = false)
public abstract class IrisSamplersMixin {

    @Inject(method = "addRenderTargetSamplers", at = @At("TAIL"))
    private static void farfield$voxyDepth(SamplerHolder samplers, Supplier<ImmutableSet<Integer>> flipped,
                                           RenderTargets renderTargets, boolean isFullscreenPass,
                                           WorldRenderingPipeline pipeline, CallbackInfo ci) {
        samplers.addDynamicSampler(TextureType.TEXTURE_2D, IrisLod::opaqueDepthTexture, null, "vxDepthTexOpaque");
        samplers.addDynamicSampler(TextureType.TEXTURE_2D, IrisLod::translucentDepthTexture, null, "vxDepthTexTrans");
        // The render targets past Iris's own that a Voxy contract writes (Photon's colortex16).
        for (int i = Math.max(IrisLod.FIRST_EXTRA, renderTargets.getRenderTargetCount());
             i < IrisLod.FIRST_EXTRA + IrisLod.EXTRA_COUNT; i++) {
            int index = i;
            samplers.addDynamicSampler(TextureType.TEXTURE_2D, () -> IrisLod.extraTarget(index), null, "colortex" + index);
        }
    }
}
