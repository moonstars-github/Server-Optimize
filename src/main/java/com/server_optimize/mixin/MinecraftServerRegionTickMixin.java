package com.server_optimize.mixin;

import com.server_optimize.thread.RegionTickDriver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Region-based full-tick parallelism ([thread.multithread.regionbased]
 * EnableRegionBasedMultithreadTicking, off by default).
 *
 * <p>NOTE (deadlock lesson): offloading the whole ServerLevel.tick to a worker deadlocks -
 * the level tick issues synchronous chunk loads, and vanilla drives chunk generation from
 * the server thread, which is blocked at the join barrier. So this mixin now passes the tick
 * through inline. The per-region executor (unit 2) parallelizes only the SIMULATION of
 * disconnected regions and keeps the chunk-source tick on the server thread; RegionTickDriver
 * and RegionPartitioner stay as the scaffolding for it.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerRegionTickMixin {

    @Redirect(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void serverOptimize$regionTick(ServerLevel level, BooleanSupplier haveTime) {
        level.tick(haveTime);
    }

    @Inject(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
    private void serverOptimize$joinRegionTicks(CallbackInfo ci) {
        RegionTickDriver.joinLevelTicks();
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (RegionTickDriver.enabled() && server.getTickCount() % 20 == 0) {
            RegionTickDriver.recomputeRegions(server);
        }
    }
}
