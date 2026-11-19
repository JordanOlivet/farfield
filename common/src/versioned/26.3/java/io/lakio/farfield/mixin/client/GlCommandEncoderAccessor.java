package io.lakio.farfield.mixin.client;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The GPU device's encoder skips {@code glUseProgram} when a draw's program
 * is the last one it bound, across render passes (ADR-165): after the LODs
 * drew with their own programs it must bind again. Since 26.3 the encoder is
 * Renderpearl's OpenGL backend's, package-private, so the target is named;
 * its vertex arrays set their buffers at every draw (ADR-171).
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public interface GlCommandEncoderAccessor {
    @Accessor("lastProgram")
    void farfield$setLastProgram(GlProgram program);
}
