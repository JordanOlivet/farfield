package io.lakio.farfield.mixin.iris;

import com.google.common.collect.ImmutableList;
import io.lakio.farfield.client.render.iris.PackContract;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.nio.file.Path;
import java.util.Map;

/**
 * The macro of the LOD contract Farfield follows under a pack ({@code VOXY}
 * or {@code DISTANT_HORIZONS}, ADR-113, ADR-119), among the environment
 * defines Iris loads the pack with: chosen from the pack's own files.
 */
@Mixin(value = Iris.class, remap = false)
public abstract class IrisPackLoadMixin {

    @ModifyArg(method = "loadExternalShaderpack",
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V"),
            index = 2)
    private static ImmutableList<StringPair> farfield$contractMacro(Path root, Map<String, String> changedConfigs,
                                                                   ImmutableList<StringPair> defines, boolean isZip) {
        String macro = PackContract.choose(root);
        return ImmutableList.<StringPair>builder().addAll(defines).add(new StringPair(macro, "")).build();
    }
}
