package com.server_optimize.mixin;

import com.server_optimize.thread.RegionScene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Deferred commit for the region apply workers ([thread.multithread.regionbased]).
 *
 * <p>While a region-apply worker is active (RegionScene.enterRegion), setBlock does not touch
 * the shared world structures: the commit is queued in the worker's region scene and replayed
 * on the server thread after the region tasks join, so the LightEngine, the neighbour-update
 * machinery and the game-event dispatcher are never modified concurrently. On the server
 * thread (replay and vanilla paths) the call passes through untouched.
 *
 * <p>A HEAD injection rather than a wrap: setBlock is called often enough (every block change,
 * including the replay) that the wrap bridge's per-call argument array is worth avoiding.
 */
@Mixin(Level.class)
public abstract class LevelSetBlockRegionMixin {

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void serverOptimize$deferRegionSetBlock(BlockPos pos, BlockState state, int flags,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (RegionScene.active()) {
            RegionScene.defer(pos, state, flags);
            cir.setReturnValue(true);
        }
    }
}
