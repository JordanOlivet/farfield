package io.lakio.farfield.mixin.client;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 1.20.1: the render chunk of an entry of the occlusion graph (a class private to its package). */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer$RenderChunkInfo")
public interface RenderChunkInfoAccessor {

    @Accessor("chunk")
    ChunkRenderDispatcher.RenderChunk farfield$chunk();
}
