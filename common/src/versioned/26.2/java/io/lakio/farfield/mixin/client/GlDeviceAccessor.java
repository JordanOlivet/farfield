package io.lakio.farfield.mixin.client;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The OpenGL backend of the GPU device, package-private since 26.1 (ADR-171):
 * its direct state access, which makes a target's framebuffer.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice")
public interface GlDeviceAccessor {
    @Accessor("directStateAccess")
    DirectStateAccess farfield$directStateAccess();
}
