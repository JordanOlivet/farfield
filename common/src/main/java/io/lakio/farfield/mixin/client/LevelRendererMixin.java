package io.lakio.farfield.mixin.client;

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

/** Draws the LODs after the sky and before the first terrain layer. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                    ordinal = 0))
    private void farfield$renderLods(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                     GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
                                     Matrix4f projectionMatrix, CallbackInfo ci) {
        LodRenderer.render(camera, frustumMatrix, projectionMatrix);
    }
}
