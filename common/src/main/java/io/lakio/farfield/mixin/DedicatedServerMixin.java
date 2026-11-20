package io.lakio.farfield.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Keeps an empty dedicated server ticking while a preview pregeneration
 * runs (ADR-172): from 1.21.2 the game stops ticking a server left empty
 * for {@code pause-when-empty-seconds}, and the pregeneration, scheduled on
 * the server's ticks, is meant for exactly that empty server. Listed in the
 * mixin configs of 1.21.11 and later only.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {

    //? if >=1.21.11 {
    /*@org.spongepowered.asm.mixin.injection.Inject(method = "pauseWhenEmptySeconds",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void farfield$keepAwakeForPregen(
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        if (io.lakio.farfield.server.LodServer.keepsServerAwake()) {
            cir.setReturnValue(0);
        }
    }
    *///?}
}
