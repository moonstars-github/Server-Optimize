package com.server_optimize.networking;

import com.server_optimize.hopper.HopperTimeWheel;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side F3 hopper-stats broadcaster.
 * <p>
 * Only dedicated servers broadcast. Players marked modded (via
 * {@link HopperStatsSyncPacket.HopperStatsHello}) receive a whole-server
 * {@link HopperStatsSyncPacket.HopperStatsPayload} every
 * {@value #INTERVAL_TICKS} ticks. Singleplayer clients never get this; they
 * read the integrated server's wheel locally.
 */
public final class HopperStatsSync {

    private static final int INTERVAL_TICKS = 20;

    private static final Set<ServerPlayer> MODDED = ConcurrentHashMap.newKeySet();
    private static int tickCounter = 0;

    public static void markModded(ServerPlayer player) {
        MODDED.add(player);
    }

    public static void removePlayer(ServerPlayer player) {
        MODDED.remove(player);
    }

    public static void tick(MinecraftServer server) {
        if (server == null || !server.isDedicatedServer()) return;
        if (MODDED.isEmpty()) return;
        if (++tickCounter < INTERVAL_TICKS) return;
        tickCounter = 0;

        HopperTimeWheel.DebugStats stats = HopperTimeWheel.aggregateServer(server);
        if (stats == null) return;

        HopperStatsSyncPacket.HopperStatsPayload payload =
            new HopperStatsSyncPacket.HopperStatsPayload(
                stats.bucketCounts, stats.checkedByBucket, stats.wokeByBucket, stats.sleptByBucket,
                stats.sleepingCount, stats.currentBucket, stats.totalActive,
                stats.checkedCount, stats.wokeCount, stats.sleptCount);

        for (ServerPlayer player : MODDED) {
            try {
                ServerPlayNetworking.send(player, payload);
            } catch (Exception ignored) {
                // A failing player must never disturb the rest of the broadcast.
            }
        }
    }

    private HopperStatsSync() {
    }
}
