package io.lakio.farfield.mixin.iris;

import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

import java.util.Map;
import java.util.function.Function;

/** The pack's preprocessed sources, the Voxy files among them (ADR-113). */
@Mixin(value = ShaderPack.class, remap = false)
public interface ShaderPackAccessor {

    @Accessor("sourceProvider")
    Function<AbsolutePackPath, String> farfield$sourceProvider();

    /** The dimension folders (a field in every 1.8 Iris; its getter came later). */
    @Accessor("dimensionMap")
    Map<NamespacedId, String> farfield$dimensionMap();
}
