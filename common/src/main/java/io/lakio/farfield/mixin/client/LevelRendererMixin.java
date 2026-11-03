package io.lakio.farfield.mixin.client;

import io.lakio.farfield.client.render.FrameProbe;
import io.lakio.farfield.client.render.LodRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
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
 * Optional, so a mod that rewrites {@code renderLevel} cannot crash the game:
 * without it the "LOD renderer ready" line never appears.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                    ordinal = 0),
            require = 0)
    private void farfield$renderLods(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                     GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
                                     Matrix4f projectionMatrix, CallbackInfo ci) {
        LodRenderer.render(camera, frustumMatrix, projectionMatrix);
    }

    /** The shader pack's LOD depth and matrices for this frame (ADR-113), before any pass reads them. */
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void farfield$beginLevel(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                     GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
                                     Matrix4f projectionMatrix, CallbackInfo ci) {
        LodRenderer.beginLevel(frustumMatrix, projectionMatrix);
    }

    /**
     * The translucent LODs under a shader pack (ADR-113), as the game's
     * translucent terrain starts: after Iris's deferred passes and its copy
     * of the opaque depth, both run at the "translucent" profiler step just
     * before. One of the two calls runs per frame (Fabulous or not).
     */
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;translucent()Lnet/minecraft/client/renderer/RenderType;"),
            require = 0)
    private void farfield$renderTranslucentLods(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                                GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
                                                Matrix4f projectionMatrix, CallbackInfo ci) {
        LodRenderer.renderTranslucent();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 0)
    private void farfield$endOfLevel(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                     GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
                                     Matrix4f projectionMatrix, CallbackInfo ci) {
        FrameProbe.endOfLevel();
    }
}
