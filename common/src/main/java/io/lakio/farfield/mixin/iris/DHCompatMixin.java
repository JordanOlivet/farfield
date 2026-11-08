package io.lakio.farfield.mixin.iris;

import io.lakio.farfield.client.render.iris.IrisLod;
import net.irisshaders.iris.compat.dh.DHCompat;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The composite side of the DH contract (ADR-119) without Distant Horizons:
 * Iris reads {@code dhDepthTex}, {@code dhNearPlane}, {@code dhFarPlane},
 * {@code dhRenderDistance} and {@code dhProjection} from {@link DHCompat},
 * which answers placeholders when DH is absent. Under a pack whose DH
 * contract Farfield follows, they are the LODs' own.
 */
@Mixin(value = DHCompat.class, remap = false)
public abstract class DHCompatMixin {

    @Inject(method = "getNearPlane", at = @At("HEAD"), cancellable = true)
    private static void farfield$near(CallbackInfoReturnable<Float> cir) {
        if (IrisLod.dhActive()) {
            cir.setReturnValue(IrisLod.dhNear());
        }
    }

    @Inject(method = "getFarPlane", at = @At("HEAD"), cancellable = true)
    private static void farfield$far(CallbackInfoReturnable<Float> cir) {
        if (IrisLod.dhActive()) {
            cir.setReturnValue(IrisLod.dhFar());
        }
    }

    @Inject(method = "getRenderDistance", at = @At("HEAD"), cancellable = true)
    private static void farfield$distance(CallbackInfoReturnable<Integer> cir) {
        if (IrisLod.dhActive()) {
            cir.setReturnValue(IrisLod.dhRenderDistanceBlocks());
        }
    }

    @Inject(method = "getProjection", at = @At("HEAD"), cancellable = true)
    private static void farfield$projection(CallbackInfoReturnable<Matrix4f> cir) {
        if (IrisLod.dhActive()) {
            cir.setReturnValue(IrisLod.dhProjection());
        }
    }

    @Inject(method = "getDepthTex", at = @At("HEAD"), cancellable = true)
    private void farfield$depth(CallbackInfoReturnable<Integer> cir) {
        if (IrisLod.dhActive()) {
            cir.setReturnValue(IrisLod.translucentDepthTexture());
        }
    }

    @Inject(method = "getDepthTexNoTranslucent", at = @At("HEAD"), cancellable = true)
    private void farfield$opaqueDepth(CallbackInfoReturnable<Integer> cir) {
        if (IrisLod.dhActive()) {
            cir.setReturnValue(IrisLod.opaqueDepthTexture());
        }
    }
}
