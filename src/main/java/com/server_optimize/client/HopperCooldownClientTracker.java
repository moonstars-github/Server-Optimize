package com.server_optimize.client;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last server-synced TransferCooldown for a hopper.
 *
 * The server computes the cooldown only when Tweakmore requests NBT or when a
 * block entity update/save is needed. The client returns that snapshot directly
 * instead of extrapolating by game time, which keeps /tick freeze and /tick step
 * displays aligned with the server value.
 */
public final class HopperCooldownClientTracker {
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> COOLDOWNS = new ConcurrentHashMap<>();

    private HopperCooldownClientTracker() {
    }

    public static void onSync(Level level, BlockPos pos, int cooldown) {
        if (level == null) return;
        COOLDOWNS.computeIfAbsent(level.dimension(), key -> new ConcurrentHashMap<>())
            .put(pos.asLong(), cooldown);
    }

    public static void onRemoved(Level level, BlockPos pos) {
        if (level == null) return;
        Map<Long, Integer> cooldowns = COOLDOWNS.get(level.dimension());
        if (cooldowns != null && cooldowns.remove(pos.asLong()) != null && cooldowns.isEmpty()) {
            COOLDOWNS.remove(level.dimension(), cooldowns);
        }
    }

    public static void clear() {
        COOLDOWNS.clear();
    }

    public static int getDisplay(Level level, BlockPos pos, int fallback) {
        if (level == null) return fallback;
        Map<Long, Integer> cooldowns = COOLDOWNS.get(level.dimension());
        if (cooldowns == null) return fallback;
        Integer cooldown = cooldowns.get(pos.asLong());
        return cooldown == null ? fallback : cooldown;
    }
}
