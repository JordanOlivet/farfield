package io.lakio.farfield.mixin.client;

import io.lakio.farfield.client.render.LodRenderer;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A boss bar's world fog (the ender dragon's, within 192 blocks of the End's
 * centre while it lives) is left out while LODs are drawn (ADR-179). The game
 * treats it as the Nether's thick fog, opaque 96 blocks away, and hides the
 * sky with it: with the thick fog distance at its default, vanilla, no LOD
 * showed on the End's main island until the dragon died.
 *
 * <p>The question itself is answered, not one of its callers: the fog
 * distances, the sky and the End flash's light all ask it, in classes that
 * differ from one Minecraft version to the next. Optional: without it the
 * boss fog stays, and hides the LODs as before.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {

    @Inject(method = "shouldCreateWorldFog", at = @At("RETURN"), cancellable = true, require = 0)
    private void farfield$noBossFog(CallbackInfoReturnable<Boolean> cir) {
        if (LodRenderer.leavesOutBossFog(cir.getReturnValueZ())) {
            cir.setReturnValue(false);
        }
    }
}
