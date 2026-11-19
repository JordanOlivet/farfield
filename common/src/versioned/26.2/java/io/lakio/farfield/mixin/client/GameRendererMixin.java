package io.lakio.farfield.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import io.lakio.farfield.client.render.LodRenderer;
import net.minecraft.client.DeltaTracker;
import io.lakio.farfield.compat.McClient;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.2 (ADR-171), as 26.1: the level's frame starts just before the game
 * renderer asks the level renderer to draw it, with the view rotation and
 * the projection the level draws with (the camera's, with the view bobbing
 * and the nausea skew), which {@code LevelRenderer.renderLevel} no longer
 * receives (26.2 names it {@code render}). Optional: without it the LODs do not draw.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"),
            require = 0)
    private void farfield$beginLevel(DeltaTracker deltaTracker, CallbackInfo ci,
                                     @Local Matrix4fc modelViewMatrix, @Local Matrix4f projectionMatrix) {
        Matrix4f view = new Matrix4f(modelViewMatrix);
        Matrix4f projection = new Matrix4f(projectionMatrix);
        LodRenderer.beginLevel(view, projection);
        LodRenderer.setFrame(McClient.mainCamera(), view, projection);
    }
}
