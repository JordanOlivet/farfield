package io.lakio.farfield.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Packs read the Voxy contract's composite side under {@code VOXY} (ADR-113). */
@Mixin(value = StandardMacros.class, remap = false)
public abstract class StandardMacrosMixin {

    @ModifyReturnValue(method = "createStandardEnvironmentDefines", at = @At("RETURN"))
    private static ImmutableList<StringPair> farfield$voxy(ImmutableList<StringPair> defines) {
        return ImmutableList.<StringPair>builder().addAll(defines).add(new StringPair("VOXY", "")).build();
    }
}
