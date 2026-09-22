package com.server_optimize.thread;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Region partitioner for within-dimension parallelism
 * ([thread.multithread.regionbased] EnableRegionBasedMultithreadTicking, off by default).
 *
 * <p>A region is the connected component of the loaded-area squares that players (and bots,
 * which are players) keep loaded: every player square is inflated by {@link #GAP_CHUNKS} so
 * regions that merely touch keep a guaranteed gap, and components are merged over
 * 8-neighbourhood - two regions are therefore either far enough apart to never interact, or
 * they are one region and tick serially with vanilla semantics. Connected areas merge into a
 * serial unit before the tick, so there is no boundary problem by construction.
 *
 * <p>Output for one level: the disjoint regions as chunk sets, largest first. Unit 2 (the
 * per-region tick executor) consumes this; unit 0 (dimension-level parallelism) is the
 * trivial case where the whole level is one component.
 */
public final class RegionPartitioner {

    /** Extra chunks added around every player square before merging, so disconnected
     *  regions are separated by at least one unloaded chunk. */
    private static final int GAP_CHUNKS = 1;

    public static final class Region {
        public final int id;
        public final List<ChunkPos> chunks;

        Region(int id, List<ChunkPos> chunks) {
            this.id = id;
            this.chunks = chunks;
        }
    }

    private RegionPartitioner() {
    }

    /** The disjoint regions of one level: connected components of the inflated loaded
     *  areas, largest first. Empty when there are no players. */
    public static List<Region> compute(ServerLevel level) {
        int radius = 8 + GAP_CHUNKS;
        long r2 = (long) radius * radius;
        // Mark the loaded squares. A circle instead of a square keeps the area smaller;
        // connectivity below uses 8-neighbourhood, so the shape does not matter.
        Set<Long> marked = new HashSet<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level) {
                continue;
            }
            BlockPos pos = player.blockPosition();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= r2) {
                        marked.add(key(cx + dx, cz + dz));
                    }
                }
            }
        }
        // Connected components over the 8-neighbourhood.
        Map<Long, Integer> chunkRegion = new HashMap<>();
        Map<Integer, List<ChunkPos>> regions = new HashMap<>();
        int nextId = 0;
        for (long start : marked) {
            if (chunkRegion.containsKey(start)) {
                continue;
            }
            int id = nextId++;
            List<ChunkPos> component = new ArrayList<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            chunkRegion.put(start, id);
            while (!queue.isEmpty()) {
                long cur = queue.poll();
                int cx = (int) (cur >> 32);
                int cz = (int) (cur & 0xFFFFFFFFL);
                component.add(new ChunkPos(cx, cz));
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        long next = key(cx + dx, cz + dz);
                        if (marked.contains(next) && !chunkRegion.containsKey(next)) {
                            chunkRegion.put(next, id);
                            queue.add(next);
                        }
                    }
                }
            }
            regions.put(id, component);
        }
        List<Region> result = new ArrayList<>(regions.size());
        for (Map.Entry<Integer, List<ChunkPos>> e : regions.entrySet()) {
            result.add(new Region(e.getKey(), e.getValue()));
        }
        result.sort((a, b) -> Integer.compare(b.chunks.size(), a.chunks.size()));
        return result;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
