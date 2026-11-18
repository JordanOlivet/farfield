package io.lakio.farfield.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import io.lakio.farfield.client.render.FrameProbe;
import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.compat.GlCompat;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.21.11 (ADR-165): the level's frame starts here, with the
 * camera and matrices the LODs draw with; the terrain itself draws later,
 * inside the frame graph's main pass: the LODs draw just before its call to
 * draw the opaque layers, into their target, and the translucent LODs
 * before the translucent layers (the fallback of Iris's stage, as in
 * 1.21.1). At the call, not at the head of {@code renderGroup}: Sodium
 * cancels that method at its head, and which of two head injections runs
 * first depends on the loader's mixin order (NeoForge ran Sodium's first).
 * Marks the end of the level for the frame probe (ADR-099). Optional, so a
 * mod that rewrites {@code renderLevel} cannot crash the game: without it the
 * "LOD renderer ready" line never appears.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    /**
     * The main pass's lambda, which draws the terrain: its Mojang name (NeoForge) and its
     * intermediary one (Fabric, whose mappings name no lambda), as Sodium targets it too.
     */
    private static final String MAIN_PASS = "lambda$addMainPass$1";
    private static final String MAIN_PASS_INTERMEDIARY = "method_62214";

    /** The shader pack's LOD depth and matrices for this frame (ADR-113), and what the terrain pass draws with. */
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void farfield$beginLevel(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker,
                                     boolean renderBlockOutline, Camera camera, Matrix4f frustumMatrix,
                                     Matrix4f projectionMatrix, Matrix4f cullingProjectionMatrix,
                                     GpuBufferSlice shaderFog, Vector4f fogColor, boolean renderSky,
                                     CallbackInfo ci) {
        LodRenderer.beginLevel(frustumMatrix, projectionMatrix);
        LodRenderer.setFrame(camera, frustumMatrix, projectionMatrix);
    }

    @Inject(method = {MAIN_PASS, MAIN_PASS_INTERMEDIARY}, remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 0, remap = true),
            require = 0)
    private void farfield$renderLods(CallbackInfo ci) {
        GlCompat.bindTarget(ChunkSectionLayerGroup.OPAQUE.outputTarget());
        LodRenderer.renderFrame();
    }

    @Inject(method = {MAIN_PASS, MAIN_PASS_INTERMEDIARY}, remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 1, remap = true),
            require = 0)
    private void farfield$renderTranslucentLods(CallbackInfo ci) {
        GlCompat.bindTarget(ChunkSectionLayerGroup.TRANSLUCENT.outputTarget());
        LodRenderer.renderTranslucent(false);
    }

    /** The terrain renderer drops every section: the compiled grid tests each column whole again. */
    @Inject(method = "allChanged", at = @At("HEAD"), require = 0)
    private void farfield$terrainReloaded(CallbackInfo ci) {
        LodRenderer.terrainReloaded();
    }

    @Inject(method = "setLevel", at = @At("HEAD"), require = 0)
    private void farfield$levelChanged(net.minecraft.client.multiplayer.ClientLevel level, CallbackInfo ci) {
        LodRenderer.terrainReloaded();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 0)
    private void farfield$endOfLevel(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker,
                                     boolean renderBlockOutline, Camera camera, Matrix4f frustumMatrix,
                                     Matrix4f projectionMatrix, Matrix4f cullingProjectionMatrix,
                                     GpuBufferSlice shaderFog, Vector4f fogColor, boolean renderSky,
                                     CallbackInfo ci) {
        FrameProbe.endOfLevel();
    }
}
