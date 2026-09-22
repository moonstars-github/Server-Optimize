package com.server_optimize.mixin.hopper;

import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import com.server_optimize.mixin.accessor.BoundTickingBlockEntityAccessor;
import com.server_optimize.mixin.accessor.HopperBlockEntityAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public class HopperBlockEntityTickerMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$skipSleepingHopper(CallbackInfo ci) {
        BlockEntity blockEntity = ((BoundTickingBlockEntityAccessor)(Object)this).getBlockEntity();
        if (!(blockEntity instanceof HopperBlockEntity hopper)) return;
        if (!ModConfig.INSTANCE.hopper.enabled) return;

        Level level = hopper.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.tickRateManager().runsNormally()) return;

        HopperTimeWheel wheel = HopperTimeWheel.get(serverLevel);
        if (!wheel.isStale(hopper.getBlockPos())) return;

        ((HopperBlockEntityAccessor) hopper).setTickedGameTime(serverLevel.getGameTime());
        ci.cancel();
    }
}
