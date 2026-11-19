package io.lakio.farfield.mixin.client;

import io.lakio.farfield.client.render.FrameProbe;
import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.compat.GlCompat;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.2 (ADR-171), as 26.1 and 1.21.11 (ADR-165): the terrain draws inside
 * the frame graph's main pass: the LODs draw just before its call to draw
 * the opaque layers, into their target, and the translucent LODs before the
 * translucent layers (the fallback of Iris's stage). At the call, not at the
 * head of {@code renderGroup}: Sodium cancels that method at its head. The
 * frame's camera and matrices come from {@link GameRendererMixin}, since
 * {@code render} gets the camera's render state and no projection. The
 * renderer drops its sections in {@code invalidateCompiledGeometry} (the
 * old {@code allChanged}) and with the level in {@code resetLevelRenderData}.
 * Marks the end of the level for the frame probe (ADR-099). Optional, so a
 * mod that rewrites {@code renderLevel} cannot crash the game: without it the
 * "LOD renderer ready" line never appears.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    /** The main pass's lambda, which draws the terrain (26.1 ships unobfuscated: one name for both loaders). */
    private static final String MAIN_PASS = "lambda$addMainPass$0";

    @Inject(method = MAIN_PASS,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 0),
            require = 0)
    private void farfield$renderLods(CallbackInfo ci) {
        GlCompat.bindTarget(ChunkSectionLayerGroup.OPAQUE.outputTarget());
        LodRenderer.renderFrame();
    }

    @Inject(method = MAIN_PASS,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 1),
            require = 0)
    private void farfield$renderTranslucentLods(CallbackInfo ci) {
        GlCompat.bindTarget(ChunkSectionLayerGroup.TRANSLUCENT.outputTarget());
        LodRenderer.renderTranslucent(false);
    }

    /** The terrain renderer drops every section: the compiled grid tests each column whole again. */
    @Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"), require = 0)
    private void farfield$terrainReloaded(CallbackInfo ci) {
        LodRenderer.terrainReloaded();
    }

    @Inject(method = "resetLevelRenderData", at = @At("HEAD"), require = 0)
    private void farfield$levelChanged(CallbackInfo ci) {
        LodRenderer.terrainReloaded();
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void farfield$endOfLevel(CallbackInfo ci) {
        FrameProbe.endOfLevel();
    }
}
