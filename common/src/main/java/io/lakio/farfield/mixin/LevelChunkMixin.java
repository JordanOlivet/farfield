package io.lakio.farfield.mixin;

import io.lakio.farfield.lod.LodPipeline;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the LOD pipelines (server or client side) which loaded chunks
 * changed, so they snapshot them again. Optional: if another mod replaces
 * {@code setBlockState}, changes are only seen at the next load (the
 * pipeline's close line counts the block changes seen).
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"), require = 0)
    private void farfield$onSetBlockState(BlockPos pos, BlockState state, boolean isMoving,
                                          CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null) {
            LodPipeline.blockChanged(level, (LevelChunk) (Object) this);
        }
    }
}
