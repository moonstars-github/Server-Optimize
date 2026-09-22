package com.server_optimize.thread;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;

/**
 * The per-region natural-spawn machinery ([thread.multithread.regionbased],
 * connected-region path).
 *
 * <p>The spawn path mutates the shared {@code SpawnState} (mob-cap counters and the per-player
 * local caps, plus a position/charge cache) and draws spawn positions from the shared level
 * RNG, which forced the old offload to serialize every spawn segment under one lock. This
 * class removes both shared resources:
 * <ul>
 *   <li>{@link #copySpawnState} builds one {@code SpawnState} per region: a value copy of the
 *       per-category census counters, the shared spawn-potential table (read-only while
 *       spawning), and a fresh local-cap calculator (each region accounts only its own
 *       spawns - the existing population is still counted by the census copy, and the next
 *       tick's census rebuilds from the real entities, so the global mob caps self-correct
 *       within one tick). The mutable position/charge cache is per-copy, so nothing is
 *       shared between workers.</li>
 *   <li>{@link #threadRandom} is the per-worker RNG the region spawn draws from instead of
 *       {@code Level.random} - installed as a thread-aware wrapper on the field itself
 *       ({@link RegionAwareRandomSource}), so every spawn-path read resolves to it on the
 *       workers (divergence accepted - regions already run their random ticks on region-local
 *       RNGs).</li>
 * </ul>
 */
public final class RegionSpawn {

    private static final ThreadLocal<RandomSource> THREAD_RANDOM =
        ThreadLocal.withInitial(RandomSource::create);

    private RegionSpawn() {
    }

    /** The calling worker's spawn RNG (one per pool thread). */
    public static RandomSource threadRandom() {
        return THREAD_RANDOM.get();
    }

    /** One SpawnState per region, so the spawn passes never share mutable cap state. */
    public static NaturalSpawner.SpawnState copySpawnState(NaturalSpawner.SpawnState snapshot,
                                                           ServerLevel level) {
        com.server_optimize.mixin.accessor.NaturalSpawnerSpawnStateAccessor acc =
            (com.server_optimize.mixin.accessor.NaturalSpawnerSpawnStateAccessor) (Object) snapshot;
        PotentialCalculator spawnPotential = acc.serverOptimize$getSpawnPotential();
        LocalMobCapCalculator localCaps = new LocalMobCapCalculator(
            ((com.server_optimize.mixin.accessor.ServerChunkCacheRegionAccessor)
                (Object) level.getChunkSource()).serverOptimize$getChunkMap());
        return com.server_optimize.mixin.accessor.NaturalSpawnerSpawnStateAccessor
            .serverOptimize$newSpawnState(snapshot.getSpawnableChunkCount(),
                new Object2IntOpenHashMap<>(snapshot.getMobCategoryCounts()),
                spawnPotential, localCaps);
    }
}