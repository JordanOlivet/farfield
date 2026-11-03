package io.lakio.farfield.mixin.iris;

import io.lakio.farfield.client.render.iris.IrisLod;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The LOD matrices of the Voxy contract, for every Iris program (ADR-113). */
@Mixin(value = CommonUniforms.class, remap = false)
public abstract class CommonUniformsMixin {

    @Inject(method = "generalCommonUniforms", at = @At("TAIL"))
    private static void farfield$voxyUniforms(UniformHolder uniforms, FrameUpdateNotifier updateNotifier,
                                              PackDirectives directives, CallbackInfo ci) {
        IrisLod.addUniforms(uniforms);
    }
}
