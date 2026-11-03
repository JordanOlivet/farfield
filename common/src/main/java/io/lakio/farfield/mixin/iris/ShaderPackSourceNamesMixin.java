package io.lakio.farfield.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.lakio.farfield.client.render.iris.VoxyContract;
import net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes Iris read the packs' Voxy files (ADR-113) as it reads its own
 * programs: their includes, options and preprocessing follow.
 */
@Mixin(value = ShaderPackSourceNames.class, remap = false)
public abstract class ShaderPackSourceNamesMixin {

    @ModifyReturnValue(method = "findPotentialStarts", at = @At("RETURN"))
    private static ImmutableList<String> farfield$voxyStarts(ImmutableList<String> starts) {
        return ImmutableList.<String>builder().addAll(starts).addAll(VoxyContract.FILES).build();
    }
}
