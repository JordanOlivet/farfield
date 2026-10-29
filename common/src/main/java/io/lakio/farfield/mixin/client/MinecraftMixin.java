package io.lakio.farfield.mixin.client;

import io.lakio.farfield.client.AgentWindow;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An agent's dev client starts out of the user's way (ADR-096): vanilla
 * marks its window active as soon as it has one, focused or not, and then
 * takes the mouse when a world opens; this marks it inactive instead (the
 * focus callback sets it again when the user clicks in) and sends the
 * window to the taskbar or behind the others.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setWindowActive(Z)V",
                    shift = At.Shift.AFTER))
    private void farfield$agentWindow(CallbackInfo ci) {
        if (AgentWindow.LAUNCH != AgentWindow.Mode.NORMAL) {
            Minecraft self = (Minecraft) (Object) this;
            self.setWindowActive(false);
            AgentWindow.afterCreate(self.getWindow().getWindow());
        }
    }
}
