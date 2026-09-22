package com.server_optimize.mixin;

import com.server_optimize.thread.RegionScene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Deferred tick scheduling for the region apply ([thread.multithread.regionbased]).
 *
 * <p>Random ticks can request a block tick (frosted ice melting, pointed dripstone, ...),
 * which vanilla routes into the per-level {@code LevelTicks} structure through its single
 * {@code schedule} funnel. That structure is one shared map per level, so two region workers
 * scheduling ticks concurrently would race on it. While a region-apply worker is active,
 * {@code LevelTicks.schedule} records the schedule in the region scene instead; the server
 * thread replays it after the region tasks join, keeping LevelTicks main-thread-only (like
 * setBlock and the entity storage).
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksRegionScheduleMixin {

    @Inject(method = "schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V", at = @At("HEAD"),
        cancellable = true)
    private void serverOptimize$deferSchedule(ScheduledTick<?> tick, CallbackInfo ci) {
        if (RegionScene.active()) {
            Object type = tick.type();
            if (type instanceof Block block) {
                RegionScene.deferTickSchedule(tick.pos(), block, tick.triggerTick(),
                    tick.priority());
                ci.cancel();
            }
        }
    }
}