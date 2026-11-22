package io.lakio.farfield.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The sections vanilla's renderer draws this frame, for the hand-off with the LODs (ADR-052).
 * 1.20.1: the frustum's list of the occlusion graph's entries ({@code RenderChunkInfo}, private
 * to the package), each read through {@link RenderChunkInfoAccessor}.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("renderChunksInFrustum")
    ObjectArrayList<Object> farfield$visibleSections();
}
