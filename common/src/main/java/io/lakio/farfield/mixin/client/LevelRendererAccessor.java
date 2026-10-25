package io.lakio.farfield.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sections vanilla's renderer draws this frame, for the hand-off with the LODs (ADR-052, ADR-069). */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> farfield$visibleSections();

    @Accessor("viewArea")
    ViewArea farfield$viewArea();

    @Accessor("cullingFrustum")
    Frustum farfield$cullingFrustum();
}
