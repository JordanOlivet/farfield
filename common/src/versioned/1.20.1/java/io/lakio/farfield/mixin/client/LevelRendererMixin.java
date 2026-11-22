package io.lakio.farfield.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.lakio.farfield.client.render.FrameProbe;
import io.lakio.farfield.client.render.LodRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the LODs after the sky and before the first terrain layer, and
 * marks the end of the level for the frame probe (ADR-099).
 * Minecraft 1.20.1: {@code renderLevel} takes the pose stack (the camera's
 * rotation, which 1.20.5 on passes as the frustum matrix) and the partial
 * tick, and draws the terrain layers through {@code renderChunkLayer}.
 * Optional, so a mod that rewrites {@code renderLevel} cannot crash the game:
 * without it the "LOD renderer ready" line never appears.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 0),
            require = 0)
    private void farfield$renderLods(PoseStack poseStack, float partialTick, long finishNanoTime,
                                     boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                     LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        LodRenderer.render(camera, poseStack.last().pose(), projectionMatrix);
    }

    /** The shader pack's LOD depth and matrices for this frame (ADR-113), before any pass reads them. */
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void farfield$beginLevel(PoseStack poseStack, float partialTick, long finishNanoTime,
                                     boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                     LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        LodRenderer.beginLevel(poseStack.last().pose(), projectionMatrix);
    }

    /**
     * The translucent LODs under a shader pack (ADR-113), a fallback: Iris's
     * {@code beginTranslucents} draws them first, before the pack's deferred
     * passes ({@code IrisRenderingPipelineMixin}); this runs only when that
     * mixin did not apply. One of the two calls runs per frame (Fabulous or not).
     */
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;translucent()Lnet/minecraft/client/renderer/RenderType;"),
            require = 0)
    private void farfield$renderTranslucentLods(PoseStack poseStack, float partialTick, long finishNanoTime,
                                                boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                                LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
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
    private void farfield$endOfLevel(PoseStack poseStack, float partialTick, long finishNanoTime,
                                     boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                     LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        FrameProbe.endOfLevel();
    }
}
