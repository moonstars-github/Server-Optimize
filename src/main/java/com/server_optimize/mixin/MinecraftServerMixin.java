package com.server_optimize.mixin;

import com.mojang.logging.LogUtils;
import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import com.server_optimize.mixin.accessor.ChunkMapAccessor;
import com.server_optimize.networking.HopperStatsSync;
import com.server_optimize.networking.SectionCulling;
import com.server_optimize.util.ChunkIOCounters;
import com.server_optimize.util.EntityPacketBatcher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Hooks into MinecraftServer tick cycle to process time wheel
 * wake-ups before any world-level hopper processing.
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Unique
    private int serverOptimize$diagTick;

    @Inject(method = "tickChildren", at = @At("HEAD"))
    private void serverOptimize$onTickChildrenStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer)(Object)this;
        // Per-tick high-speed velocity windows for section culling, and the
        // per-tick projectile region-state cache (air-section/4^3 skip).
        SectionCulling.updateAllHighSpeedTrackers(server);
        com.server_optimize.util.ProjectileRegionCache.clear();
        for (ServerLevel level : server.getAllLevels()) {
            HopperTimeWheel wheel = HopperTimeWheel.get(level);
            wheel.onTickStart();
        }

        // Diagnostic line: heapUsed, culledCache, player count,
        // chunk map sizes, optional IO counts and load rate.
        int interval = 600;
        if (ModConfig.INSTANCE != null) {
            interval = Math.max(20, ModConfig.INSTANCE.log.diagInfoInterval);
        }
        if (++this.serverOptimize$diagTick >= interval) {
            this.serverOptimize$diagTick = 0;
            try {
                Runtime rt = Runtime.getRuntime();
                long used = rt.totalMemory() - rt.freeMemory();
                StringBuilder sb = new StringBuilder();
                for (ServerLevel level : server.getAllLevels()) {
                    net.minecraft.server.level.ChunkMap cm = level.getChunkSource().chunkMap;
                    ChunkMapAccessor acc = (ChunkMapAccessor) (Object) cm;
                    sb.append(" [").append(level.dimension().toString()).append("] upd=")
                        .append(acc.serverOptimize$getUpdatingChunkMap().size())
                        .append(" vis=").append(acc.serverOptimize$getVisibleChunkMap().size());
                }
                String io = "";
                if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.log.chunkSummary) {
                    long[] counts = ChunkIOCounters.reset();
                    io = " loaded=" + counts[0] + " saved=" + counts[1];
                }
                String rate = "";
                long rateCount = ChunkIOCounters.resetLoadRate();
                if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.log.chunkLoadRate) {
                    rate = " loadRate=" + (rateCount / (interval / 20)) + "/s";
                }
                if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.log.diagInfo) {
                    com.server_optimize.ServerOptimize.LOGGER.info(
                        "diag: heapUsed={}MB culledCache={} players={}{}{}{}",
                        used / 1048576L, SectionCulling.cacheSize(), server.getPlayerList().getPlayers().size(), sb, io, rate);
                }
            } catch (Exception e) {
                com.server_optimize.ServerOptimize.LOGGER.warn("diag failed", e);
            }
        }

        // Handle idle timeout: close connections directly on Java 25+ to avoid DisconnectPayload codec errors.
        serverOptimize$handleIdleTimeout(server, hasTimeLeft);
    }

    /** Close all player connections when server is empty — avoids broken disconnect codec on Java 25+. */
    private void serverOptimize$handleIdleTimeout(MinecraftServer server, BooleanSupplier hasTimeLeft) {
        // TODO: Fix for correct Mojang mapping names. Skip until proper connection API is known.
    }

    @Inject(method = "tickChildren", at = @At("RETURN"))
    private void serverOptimize$onTickChildrenEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer)(Object)this;
        for (ServerLevel level : server.getAllLevels()) {
            HopperTimeWheel wheel = HopperTimeWheel.get(level);
            wheel.onTickEnd();
        }
        // Flush batched entity position packets once per tick (net.entityPacketBatching).
        EntityPacketBatcher.flushAll();
        // Push whole-server hopper stats to modded clients (dedicated only).
        HopperStatsSync.tick(server);
    }
}
