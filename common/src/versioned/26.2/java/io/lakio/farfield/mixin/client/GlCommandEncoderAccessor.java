package io.lakio.farfield.mixin.client;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.VertexArrayCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The GPU device's encoder skips {@code glUseProgram} when a draw's program
 * is the last one it bound, across render passes (ADR-165), and since 26.2
 * it also skips setting up the vertex array it bound last: after the LODs
 * drew with their own programs and buffers it must set both up again
 * (ADR-171). The encoder is package-private, so the target is named.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public interface GlCommandEncoderAccessor {
    @Accessor("lastProgram")
    void farfield$setLastProgram(GlProgram program);

    @Accessor("lastVertexArray")
    void farfield$setLastVertexArray(VertexArrayCache.VertexArray vertexArray);
}
