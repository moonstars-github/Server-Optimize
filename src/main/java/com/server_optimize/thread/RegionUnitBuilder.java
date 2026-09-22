package com.server_optimize.thread;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

/**
 * Region mode: builds the random-tick pass units from the player simulation squares
 * ([thread.multithread.regionbased], connected-region path). The server thread never walks
 * the block-ticking chunks in this mode (see
 * {@link com.server_optimize.mixin.ServerChunkCacheRegionSpawnMixin}), so the pass units -
 * one per ticking chunk, with the sample positions - are generated here from the players.
 * The generation is itself parallel: the covered chunk list is split across the pool, each
 * worker scans its slices' sections, and every chunk draws its position sequence from a
 * per-chunk start value (a mix of the chunk coordinates and the game tick), so no serial
 * counter chain is needed - the accepted random-sequence divergence of the region path.
 */
public final class RegionUnitBuilder {

    private static final ConcurrentHashMap<Integer, long[]> STEP_CACHE = new ConcurrentHashMap<>();

    private RegionUnitBuilder() {
    }

    /** One section's worth of counter stepping, v -> v * power + add (mod 2^32), cached per speed. */
    public static long[] stepFor(int steps) {
        long[] cached = STEP_CACHE.get(steps);
        if (cached != null) {
            return cached;
        }
        long power = 1L;   // 3^steps mod 2^32
        long base = 3L;
        long remaining = steps;
        while (remaining > 0) {
            if ((remaining & 1) != 0) {
                power = (power * base) & 0xFFFFFFFFL;
            }
            base = (base * base) & 0xFFFFFFFFL;
            remaining >>= 1;
        }
        long add = (1013904223L * ((power - 1) >> 1)) & 0xFFFFFFFFL;
        long[] step = new long[] {power, add};
        STEP_CACHE.put(steps, step);
        return step;
    }

    /**
     * The pass units for one level: every chunk within the simulation-distance square of each
     * of its players, built in parallel on the worker pool. Overlapping squares contribute
     * each chunk once, so no two workers ever touch the same chunk.
     */
    public static List<RandomTickPass.Work> collectUnits(ServerLevel level, int randomTickSpeed) {
        List<RandomTickPass.Work> units = new ArrayList<>();
        if (randomTickSpeed <= 0) {
            return units;
        }
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            return units;
        }
        int sim = level.getServer().getPlayerList().getSimulationDistance();
        LongArrayList chunkKeys = new LongArrayList();
        LongOpenHashSet covered = new LongOpenHashSet();
        for (ServerPlayer player : players) {
            int px = player.blockPosition().getX() >> 4;
            int pz = player.blockPosition().getZ() >> 4;
            for (int cz = pz - sim; cz <= pz + sim; cz++) {
                for (int cx = px - sim; cx <= px + sim; cx++) {
                    long key = ChunkPos.asLong(cx, cz);
                    if (covered.add(key)) {
                        chunkKeys.add(key);
                    }
                }
            }
        }
        if (chunkKeys.isEmpty()) {
            return units;
        }
        long gameTime = level.getGameTime();
        ForkJoinPool pool = com.server_optimize.thread.AffinityManager.ensureWorkerPool();
        if (pool == null) {
            pool = ForkJoinPool.commonPool();
        }
        int parallelism = Math.max(1, pool.getParallelism());
        int slice = Math.max(1, (chunkKeys.size() + parallelism - 1) / parallelism);
        List<ForkJoinTask<List<RandomTickPass.Work>>> tasks = new ArrayList<>();
        for (int base = 0; base < chunkKeys.size(); base += slice) {
            final int from = base;
            final int to = Math.min(chunkKeys.size(), base + slice);
            final long tick = gameTime;
            tasks.add(pool.submit(() -> buildSlice(level, chunkKeys, from, to,
                randomTickSpeed, tick)));
        }
        for (ForkJoinTask<List<RandomTickPass.Work>> task : tasks) {
            units.addAll(task.join());
        }
        return units;
    }

    /** One slice of the covered chunk list, on one worker. */
    private static List<RandomTickPass.Work> buildSlice(ServerLevel level, LongArrayList chunkKeys,
                                                        int from, int to, int randomTickSpeed,
                                                        long gameTime) {
        List<RandomTickPass.Work> sliceUnits = new ArrayList<>(to - from);
        long[] step = stepFor(randomTickSpeed);
        long mult = step[0];
        long add = step[1];
        for (int i = from; i < to; i++) {
            long key = chunkKeys.getLong(i);
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            // Present-only fetch: the chunk map is quiescent here, so the read is safe on a
            // worker, and a chunk that is not fully loaded yet is skipped (it ticks once it
            // is) instead of falling into the vanilla load path, which would hand the work
            // back to the server thread this flush is already parked on.
            LevelChunk chunk = RegionChunkLoad.loadedOrNull(level.getChunkSource(), cx, cz);
            if (chunk == null) {
                continue;
            }
            int minX = cx << 4;
            int minZ = cz << 4;
            int value = startValue(cx, cz, gameTime);
            List<SectionSamples> tasks = null;
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                LevelChunkSection section = sections[index];
                if (section == null || !section.isRandomlyTicking()) {
                    continue;
                }
                if (tasks == null) {
                    tasks = new ArrayList<>(sections.length);
                }
                int sectionBlockY = chunk.getSectionYFromSectionIndex(index) << 4;
                tasks.add(new SectionSamples(section, minX, sectionBlockY, minZ, value,
                    randomTickSpeed));
                value = (int) ((value & 0xFFFFFFFFL) * mult + add);
            }
            sliceUnits.add(new ChunkSamples(tasks == null ? List.of() : tasks,
                null, 0, minX, minZ, randomTickSpeed, true));
        }
        return sliceUnits;
    }

    /** A per-chunk, per-tick start value; the region path's accepted divergence from the
     *  serial counter chain. */
    private static int startValue(int cx, int cz, long gameTime) {
        int h = cx * 0x9E3779B1 ^ cz * 0x85EBCA77;
        h = (h ^ (int) (gameTime * 0x27D4EB2D)) * 0x2545F491;
        return h ^ (h >>> 13);
    }
}