package io.lakio.farfield.mixin.client;

import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.compat.GlCompat;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.21.11 (ADR-165): the LODs draw just before the opaque terrain
 * layers, into the layers' own target, with the frame's camera and matrices
 * ({@code LevelRendererMixin}); the translucent LODs before the translucent
 * layers, the fallback of Iris's stage as in 1.21.1. Optional.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(method = "renderGroup", at = @At("HEAD"), require = 0)
    private void farfield$renderLods(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.OPAQUE) {
            GlCompat.bindTarget(group.outputTarget());
            LodRenderer.renderFrame();
        } else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            GlCompat.bindTarget(group.outputTarget());
            LodRenderer.renderTranslucent(false);
        }
    }
}
