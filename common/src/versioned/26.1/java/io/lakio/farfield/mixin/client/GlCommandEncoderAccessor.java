package io.lakio.farfield.mixin.client;

import com.mojang.blaze3d.opengl.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The GPU device's encoder skips {@code glUseProgram} when a draw's program
 * is the last one it bound, across render passes: after the LODs drew with
 * their own programs it must bind again (ADR-165). Since 26.1 the encoder is
 * package-private, so the target is named (ADR-171).
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public interface GlCommandEncoderAccessor {
    @Accessor("lastProgram")
    void farfield$setLastProgram(GlProgram program);
}
