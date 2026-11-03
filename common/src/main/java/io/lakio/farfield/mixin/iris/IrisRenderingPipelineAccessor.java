package io.lakio.farfield.mixin.iris;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What the LOD pass needs of Iris's pipeline (ADR-113). */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public interface IrisRenderingPipelineAccessor {

    @Accessor("renderTargets")
    RenderTargets farfield$renderTargets();

    @Accessor("packDirectives")
    PackDirectives farfield$packDirectives();
}
