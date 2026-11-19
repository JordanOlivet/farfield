package io.lakio.farfield.mixin.client;

import io.lakio.farfield.client.render.FrameProbe;
import io.lakio.farfield.client.render.LodRenderer;
import io.lakio.farfield.compat.GlCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.3 (ADR-171): the main pass opens one Renderpearl render pass
 * for the opaque terrain and, without the improved transparency, the
 * translucent one too. The LODs draw just before it opens, once the level's
 * lighting is set, into the main target (raw OpenGL calls inside an open
 * render pass would run under its state). The translucent LODs draw with the
 * opaque ones (the separate translucent stage serves shader packs, not yet
 * on 26.3). The frame's camera and matrices come from
 * {@link GameRendererMixin}. Marks the end of the level for the frame probe
 * (ADR-099). Optional, so a mod that rewrites the pass cannot crash the game:
 * without it the "LOD renderer ready" line never appears.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    /** The main pass's lambda, which opens the terrain's render pass (26.3, unobfuscated). */
    private static final String MAIN_PASS = "lambda$addMainPass$0";

    @Inject(method = MAIN_PASS,
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/Lighting;setupFor(Lcom/mojang/blaze3d/platform/Lighting$Entry;)V",
                    ordinal = 0, shift = At.Shift.AFTER),
            require = 0)
    private void farfield$renderLods(CallbackInfo ci) {
        GlCompat.bindTarget(Minecraft.getInstance().gameRenderer.mainRenderTarget());
        LodRenderer.renderFrame();
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
