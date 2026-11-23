package io.lakio.farfield.forge.mixin;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.client.render.GlContextProbe;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraftforge.client.loading.NoVizFallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge 47 without its early window ({@code earlyWindowControl = false} in
 * {@code fml.toml}, which a test run sets) creates the game's window in the
 * supplier this method returns, after the window's 3.2 hints are set: the
 * OpenGL 4.6 probe runs here. Forge's Mixin (0.8.5) injects into
 * {@code Window}'s constructor at RETURN only, too late for the hints. With
 * the early window on, Forge's own window already holds the highest context
 * the driver gives, and this never runs.
 */
@Mixin(value = NoVizFallback.class, remap = false)
public abstract class NoVizFallbackMixin {

    @Inject(method = "windowHandoff", at = @At("HEAD"), require = 0)
    private static void farfield$requestGl46(IntSupplier width, IntSupplier height, Supplier<String> title,
            LongSupplier monitor, CallbackInfoReturnable<LongSupplier> cir) {
        Farfield.LOG.info("Forge early window off: OpenGL 4.6 probed for the game window");
        GlContextProbe.requestGl46();
    }
}
