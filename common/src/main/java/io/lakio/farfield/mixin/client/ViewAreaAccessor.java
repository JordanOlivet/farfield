package io.lakio.farfield.mixin.client;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's section at a block, for the hand-off by columns (ADR-069). */
@Mixin(ViewArea.class)
public interface ViewAreaAccessor {

    /** The grid wraps around: check the section's origin before trusting it. */
    @Invoker("getRenderSectionAt")
    @Nullable
    SectionRenderDispatcher.RenderSection farfield$renderSectionAt(BlockPos pos);
}
