package io.lakio.farfield.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import io.lakio.farfield.client.render.FrameProbe;
import io.lakio.farfield.client.render.LodRenderer;
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
 * inside the frame graph's main pass ({@code ChunkSectionsToRenderMixin}).
 * Marks the end of the level for the frame probe (ADR-099). Optional, so a
 * mod that rewrites {@code renderLevel} cannot crash the game: without it the
 * "LOD renderer ready" line never appears.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

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
