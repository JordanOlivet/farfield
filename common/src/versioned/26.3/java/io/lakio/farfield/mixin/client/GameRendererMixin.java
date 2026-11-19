package io.lakio.farfield.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.compat.McClient;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.3 (ADR-171), as 26.1: the level's frame starts just before
 * the game renderer asks the level renderer to draw it, with the camera's
 * view rotation and the projection the level draws with (the view bobbing
 * and the nausea skew applied), which the level renderer does not receive.
 * Optional: without it the LODs do not draw.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"),
            require = 0)
    private void farfield$beginLevel(CallbackInfo ci, @Local CameraRenderState cameraState,
                                     @Local Matrix4f projectionMatrix) {
        Matrix4f view = new Matrix4f(cameraState.viewRotationMatrix);
        Matrix4f projection = new Matrix4f(projectionMatrix);
        LodRenderer.beginLevel(view, projection);
        LodRenderer.setFrame(McClient.mainCamera(), view, projection);
    }
}
