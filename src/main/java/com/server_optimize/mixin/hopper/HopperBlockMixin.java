package com.server_optimize.mixin.hopper;

import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks block neighbor updates to wake nearby hoppers.
 * When any block changes adjacent to a hopper, the hopper's
 * neighborChanged() fires. We use it to call neighborWake,
 * which adds the hopper to neighborWakeSet (processed at next
 * tick start, matching vanilla 1-tick delay).
 */
@Mixin(HopperBlock.class)
public class HopperBlockMixin {

    @Inject(method = "neighborChanged", at = @At("TAIL"))
    private void serverOptimize(BlockState state, Level level, BlockPos pos,
                                                 Block block, Orientation orientation, boolean isMoving,
                                                 CallbackInfo ci) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        HopperTimeWheel wheel = HopperTimeWheel.get(serverLevel);
        wheel.neighborWake(pos);
    }
}
