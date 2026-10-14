package io.lakio.farfield.mixin;

import io.lakio.farfield.server.LodServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells the LOD server which loaded chunks changed, so it snapshots them again. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void farfield$onSetBlockState(BlockPos pos, BlockState state, boolean isMoving,
                                          CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null && level instanceof ServerLevel serverLevel) {
            LodServer.onBlockChanged(serverLevel, ((LevelChunk) (Object) this).getPos());
        }
    }
}
