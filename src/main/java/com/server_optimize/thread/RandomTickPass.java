package com.server_optimize.thread;

import com.server_optimize.util.WorkerBalance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.LongAdder;

/**
 * One random-tick pass per server tick, queued first and sampled on the pool afterwards.
 *
 * <p>{@code ServerLevel.tickChunk} is called once per ticking chunk from
 * {@code ServerChunkCache.tickChunks}, on the server thread. Each call draws its sample
 * positions (which must happen on the server thread, in chunk order: they advance the level's
 * position counter, and every later draw has to see the counter the earlier ones left) and
 * hands the sections to this queue. When the pass has visited every chunk,
 * {@code ServerChunkCacheTickChunksMixin} calls {@link #flush()}, which samples the whole
 * queue on the pool, joins it, and then applies the collected random ticks on the server
 * thread in chunk order.
 *
 * <p>Why the hand-out waits for the end of the pass: one chunk's worth of sampling is a few
 * microseconds. Submitting it the moment it was drawn - and joining it before the next chunk
 * - made every chunk a barrier, so the pool drained between chunks. Submitting it without
 * joining still did not use the pool: a task that appears every few microseconds is taken by
 * whichever worker is already awake, so the others stay parked and one thread does nearly all
 * of the sampling (measured on a loaded test world: pool width 14, one participant doing the
 * work). Handing out a whole pass gives the pool a backlog to steal from, which is the only
 * thing that makes it use more than one or two threads.
 *
 * <p>A pass whose sampling is estimated to finish within the configurable threshold
 * (thread.multithread.randomTickParallelThresholdMs, 0 = off) runs serial instead: such a
 * pass is not worth waking the pool for, since the hand-out itself would be a noticeable part
 * of it. The estimate is the number of queued sample positions times a per-position cost
 * remembered from the last parallel pass, so it describes the pass being queued rather than
 * the previous one.
 *
 * <p>What is not vanilla about the result: a chunk's random ticks are applied after the later
 * chunks were sampled, and the sampling of the whole pass happens after the walk. Sampling
 * only reads block states, so this is visible only for a block whose random tick changed a
 * state that another chunk samples in the same tick, and for a block that starts randomly
 * ticking after it was sampled - it ticks on the next tick instead of this one. The RNG
 * sequence of the positions is untouched: they are all drawn on the server thread, in chunk
 * order, before any of them is applied.
 *
 * <p>Three modes share that structure. Without region-based ticking the whole pass is sampled
 * on the pool, joined lazily per chunk and applied serially on the server thread in chunk order.
 * With [thread.multithread.regionbased] enabled and [thread.multithread] randomTickParallel
 * OFF, each CONNECTED region of the partitioner runs as one worker task - the region's chunks
 * tick sequentially on that worker in pass order with a region-local RNG, like an independent
 * server thread for the region (see {@link #applyConnectedRegions}); only regions run in
 * parallel. With randomTickParallel ON the pass is additionally sampled and applied per 8x8-chunk
 * group inside the regions (see {@link #applyRegions}). In both region modes setBlock is deferred
 * into {@link RegionScene} and replayed on the server thread after the region tasks join, so the
 * light engine and the update machinery are never touched concurrently; a worker reads chunks
 * from its region's pre-resolved table or as already-loaded data, never loading (see
 * {@link RegionChunkLoad}).
 *
 * <p>All mutable state here is touched by the server thread only, except the slot table,
 * which workers add to. Switching the phase off, or a pool with a single thread, never
 * reaches this class: those chunks run vanilla.
 */
public final class RandomTickPass {

    /** Per-pool-thread busy time, indexed by thread id; no per-chunk allocation. */
    private static final int SLOTS = 64;

    private static final LongAdder[] SLOT_NANOS = newSlots();

    /** A shorter queue than this is not worth handing out - it finishes during the hand-out. */
    private static final int MIN_PARALLEL_UNITS = 4;

    /** A unit of work that a worker may sample and only the server thread may apply. */
    public interface Work {

        /** Read-only: runs on a worker. */
        void sample();

        /** Mutating: runs on the server thread, in chunk order. */
        void applyRandomTicks(ServerLevel level);

        /** Mutating: runs on a worker, in chunk order within its region. */
        void applyRandomTicks(ServerLevel level, net.minecraft.util.RandomSource random);

        /** Number of sample positions this unit will read; the parallel decision is built from it. */
        int positionCount();

        /**
         * The region this unit belongs to, as a flat grid id (8x8 chunks per region when
         * region-based ticking is enabled). Only used to group the sampling tasks; the apply
         * always stays in chunk order.
         */
        int regionId();

        /** Chunk x of this unit; the region apply pre-resolves the chunks a worker may read. */
        int chunkX();

        /** Chunk z of this unit; the region apply pre-resolves the chunks a worker may read. */
        int chunkZ();
    }

    /** Units queued during the open pass, in the order their chunks were visited. */
    private static List<Work> pending = new ArrayList<>();

    /** Level the open pass belongs to; null when no pass is open. */
    private static ServerLevel pendingLevel;

    /** Game tick of the open pass. */
    private static long pendingTick = Long.MIN_VALUE;

    /** When the open pass started, i.e. when its first chunk began drawing positions. */
    private static long pendingStart;

    /** Server-thread work accumulated by the open pass: the ice and snow pass, then queueing. */
    private static long pendingServerNanos;

    /** The part of that work spent in the ice and snow pass. */
    private static long pendingIceSnowNanos;

    /**
     * Estimated per-position sampling cost in nanoseconds, remembered from the last parallel
     * pass as an EWMA. The parallel decision multiplies it by the number of queued positions,
     * so it is a decision about the pass being queued, not about the previous one.
     */
    private static volatile double costPerPositionNanos = 40.0D;

    private RandomTickPass() {
    }

    private static LongAdder[] newSlots() {
        LongAdder[] slots = new LongAdder[SLOTS];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new LongAdder();
        }
        return slots;
    }

    /**
     * Adds one chunk's share to the open pass.
     *
     * @param unit         the chunk's work, not sampled yet
     * @param serverNanos  server-thread work this chunk spent on the phase
     * @param iceSnowNanos the part of it spent in the ice and snow pass
     */
    public static void addChunk(ServerLevel level, Work unit, long serverNanos, long iceSnowNanos) {
        addServerWork(level, serverNanos, iceSnowNanos);
        pending.add(unit);
    }

    /**
     * Adds a chunk that produced no work, so only its server-thread cost joins the pass.
     * Used for a chunk with no tickable section.
     */
    public static void addServerOnly(ServerLevel level, long serverNanos, long iceSnowNanos) {
        addServerWork(level, serverNanos, iceSnowNanos);
    }

    /**
     * Opens the pass when it is not open yet - flushing a straggler one instead - and adds this
     * chunk's server-thread cost to it.
     */
    private static void addServerWork(ServerLevel level, long serverNanos, long iceSnowNanos) {
        long now = System.nanoTime();
        // A straggler pass - tickChunk reached from somewhere other than
        // ServerChunkCache.tickChunks, or the next level already ticking - is flushed here
        // rather than left to apply its ticks a tick late.
        if (pendingLevel != null
            && (pendingLevel != level || pendingTick != level.getGameTime())) {
            flush();
        }
        if (pendingLevel == null) {
            pendingLevel = level;
            pendingTick = level.getGameTime();
            // The pass began serverNanos ago, in this chunk's own server-thread slice, which now
            // starts with the ice and snow pass.
            pendingStart = now - serverNanos;
            pendingServerNanos = 0L;
            pendingIceSnowNanos = 0L;
        }
        pendingServerNanos += serverNanos;
        pendingIceSnowNanos += iceSnowNanos;
    }

    /**
     * Samples the open pass on the pool, applies its collected random ticks in chunk order
     * and reports it. Called at the end of {@code ServerChunkCache.tickChunks}, so the pool
     * gets the whole pass at once. Does nothing when no pass is open.
     */
    public static void flush() {
        if (pendingLevel == null) {
            return;
        }
        flushImpl(pendingLevel);
    }

    /**
     * Same as {@link #flush()} with the ticking level known: in region mode (regionbased on,
     * randomTickParallel off) the server thread never walks the block-ticking chunks - the
     * walk is skipped and the pass units are built here from the player simulation squares
     * instead, so the O(ticking chunks) iteration and dispatch never run on the server thread.
     */
    public static void flush(ServerLevel level) {
        com.server_optimize.config.ModConfig regionCfg = com.server_optimize.config.ModConfig.INSTANCE;
        boolean regionConnected = regionCfg != null
            && regionCfg.thread.regionbased.enableRegionBasedMultithreadTicking
            && !regionCfg.thread.randomTickParallel;
        if (regionConnected) {
            if (pendingLevel != null) {
                flushImpl(pendingLevel);
            }
            regionFlush(level);
            return;
        }
        if (pendingLevel == null) {
            return;
        }
        flushImpl(level);
    }

    /** Region mode: opens a pass whose units come from the partition, then flushes it. */
    private static void regionFlush(ServerLevel level) {
        int speed = level.getGameRules().get(
            net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
        long start = System.nanoTime();
        java.util.List<Work> units =
            com.server_optimize.thread.RegionUnitBuilder.collectUnits(level, speed);
        long serverNanos = System.nanoTime() - start;
        if (units.isEmpty()) {
            return;
        }
        pendingLevel = level;
        pendingTick = level.getGameTime();
        pendingStart = start;
        pendingServerNanos = serverNanos;
        pendingIceSnowNanos = 0L;
        pending = units;
        flushImpl(level);
    }

    /** The body of {@link #flush()}: apply the open pass, whatever its source. */
    private static void flushImpl(ServerLevel level) {
        long tick = pendingTick;
        long start = pendingStart;
        long serverNanos = pendingServerNanos;
        long iceSnowNanos = pendingIceSnowNanos;
        List<Work> work = pending;
        // Close the pass before doing anything else: the applies below mutate the world, and a
        // chunk tick that re-entered here would otherwise append to a queue already in flight.
        pending = new ArrayList<>();
        pendingLevel = null;
        pendingTick = Long.MIN_VALUE;
        pendingServerNanos = 0L;
        pendingIceSnowNanos = 0L;

        // The pass goes serial when its own sampling is estimated to finish within the
        // configurable threshold: the estimate is the number of queued sample positions times
        // a per-position cost remembered from the last parallel pass, so the decision is about
        // this pass, not the previous one, and cannot oscillate - or let a first pass run
        // parallel just because there is no history yet. The pool (and its install) is only
        // asked for when it is.
        com.server_optimize.config.ModConfig regionCfg = com.server_optimize.config.ModConfig.INSTANCE;
        boolean regionMode = regionCfg != null
            && regionCfg.thread.regionbased.enableRegionBasedMultithreadTicking;
        long positions = 0L;
        for (Work unit : work) {
            positions += unit.positionCount();
        }
        boolean worthParallel = work.size() >= MIN_PARALLEL_UNITS
            && (regionMode || (long) (positions * costPerPositionNanos) >= thresholdNanos());
        ForkJoinPool pool = worthParallel ? workerPool() : null;
        int poolWidth = pool == null ? 0 : Math.max(1, pool.getParallelism());
        long[] before = pool == null ? null : snapshotSlots();
        long joinWaitNanos = 0L;
        long applyNanos = 0L;
        long spanNanos;
        long[] workerNanos;
        // Attribute the deferred half of the phase to tickBlocks rather than to whatever
        // section the flush happens to run under; the profiler is a stack, so this pairs up.
        ProfilerFiller profiler = Profiler.get();
        profiler.push("tickBlocks");
        try {
            if (pool != null) {
                if (regionParallel()) {
                    if (regionCfg != null && regionCfg.thread.randomTickParallel) {
                        // randomTickParallel on: the per-region INNER split. Region-based
                        // sampling: one task per 8x8-chunk region, each returning its run time
                        // so the region balance below has real per-region data.
                        List<ForkJoinTask<Long>> submitted = new ArrayList<>();
                        java.util.Map<Integer, List<Work>> regions = new java.util.HashMap<>();
                        for (Work unit : work) {
                            regions.computeIfAbsent(unit.regionId(), ignored -> new ArrayList<>()).add(unit);
                        }
                        for (List<Work> units : regions.values()) {
                            ForkJoinTask<Long> task = pool.submit(() -> {
                                long taskStart = System.nanoTime();
                                for (Work unit : units) {
                                    unit.sample();
                                }
                                recordSlot(taskStart);
                                return System.nanoTime() - taskStart;
                            });
                            submitted.add(task);
                        }
                        // Barrier: sample first, apply after. The region partition covers every
                        // unit of the pass, so unlike the per-unit mode there is no overlap here.
                        for (ForkJoinTask<Long> task : submitted) {
                            long joinStart = System.nanoTime();
                            task.join();
                            joinWaitNanos += System.nanoTime() - joinStart;
                        }
                        // Region apply on workers: each region runs its collected random ticks
                        // with a region-local RNG, setBlock is deferred to the region scene and
                        // replayed below.
                        long applyStart = System.nanoTime();
                        long[] regionStats = new long[10];
                        applyRegions(level, work, pool, regionStats);
                        applyNanos = System.nanoTime() - applyStart;
                        logRegionPass(work.size(), serverNanos, iceSnowNanos, joinWaitNanos,
                            applyNanos, regionStats);
                        long min = Long.MAX_VALUE;
                        long max = 0L;
                        for (ForkJoinTask<Long> task : submitted) {
                            long duration = task.getRawResult();
                            if (duration > max) {
                                max = duration;
                            }
                            if (duration < min) {
                                min = duration;
                            }
                        }
                        RegionTickDriver.recordRegionRun(min, max);
                    } else {
                        // randomTickParallel off: ONE worker per CONNECTED region, like an
                        // independent server thread for that region. The region's chunks run
                        // sequentially on its worker in pass (chunk) order with a region-local
                        // RNG; only regions are parallel to each other. This is the
                        // RegionBasedMultithreadTicking semantics itself - randomTickParallel
                        // only adds the inside-of-a-region split.
                        long applyStart = System.nanoTime();
                        long[] regionStats = new long[10];
                        applyConnectedRegions(level, work, pool, regionStats);
                        applyNanos = System.nanoTime() - applyStart;
                        logRegionPass(work.size(), serverNanos, iceSnowNanos, 0L,
                            applyNanos, regionStats);
                    }
                } else {
                    // Non-region mode: one task per chunk, ordered lazy join - a chunk may
                    // be applied as soon as its own sampling task is done, so the apply
                    // overlaps the sampling of the later chunks instead of waiting for all
                    // of them first - which is the wait that used to sit at the end of the
                    // pass and showed up as scheduling overhead.
                    java.util.Map<Work, ForkJoinTask<Long>> taskOf = new java.util.IdentityHashMap<>();
                    for (Work unit : work) {
                        ForkJoinTask<Long> task = pool.submit(() -> {
                            long taskStart = System.nanoTime();
                            unit.sample();
                            recordSlot(taskStart);
                            return System.nanoTime() - taskStart;
                        });
                        taskOf.put(unit, task);
                    }
                    for (int i = 0; i < work.size(); i++) {
                        long joinStart = System.nanoTime();
                        taskOf.get(work.get(i)).join();
                        joinWaitNanos += System.nanoTime() - joinStart;
                        long applyStart = System.nanoTime();
                        work.get(i).applyRandomTicks(level);
                        applyNanos += System.nanoTime() - applyStart;
                    }
                }
            } else {
                long applyStart = System.nanoTime();
                for (Work unit : work) {
                    unit.sample();
                    unit.applyRandomTicks(level);
                }
                applyNanos = System.nanoTime() - applyStart;
            }
            spanNanos = System.nanoTime() - start;
            workerNanos = pool == null ? new long[0] : collectDeltas(before);
        } finally {
            profiler.pop();
        }

        // The server thread's own work is everything it did itself; the wait for the workers
        // is charged to neither side, which is why it is reported on its own.
        long serverWork = serverNanos + applyNanos;
        long longestWorker = 0L;
        for (long nanos : workerNanos) {
            if (nanos > longestWorker) {
                longestWorker = nanos;
            }
        }
        // The balance figure is over the workers only: whether the pool is used evenly is a
        // question about the pool, and mixing the server thread's own work into the ratio made
        // a pass the server did alone read as a perfectly balanced one. How much of the pool
        // took part is reported separately, because with one participant the ratio is 100%
        // however little work was done.
        WorkerBalance.record(WorkerBalance.RANDOM_TICK, workerNanos);
        WorkerBalance.recordSpan(WorkerBalance.RANDOM_TICK, spanNanos);
        WorkerBalance.recordTick(WorkerBalance.RANDOM_TICK, tick, spanNanos,
            Math.max(longestWorker, serverWork));
        WorkerBalance.recordDetail(WorkerBalance.RANDOM_TICK, spanNanos, serverNanos,
            iceSnowNanos, joinWaitNanos, applyNanos, workerNanos, poolWidth);
        if (pool != null && positions > 0L) {
            long workerSumNanos = 0L;
            for (long nanos : workerNanos) {
                workerSumNanos += nanos;
            }
            if (workerSumNanos > 0L) {
                // EWMA of the per-position sampling cost, so the estimate follows the machine
                // and the world instead of being a guess forever.
                costPerPositionNanos = costPerPositionNanos * 0.75
                    + (workerSumNanos / (double) positions) * 0.25;
            }
        }
        AffinityManager.publishRandomTickThreads(poolWidth, pool != null);
    }

    /**
     * The parallel threshold of the config, in nanoseconds: a pass this short runs serial.
     * 0 turns the threshold off, so every pass goes parallel.
     */
    private static long thresholdNanos() {
        com.server_optimize.config.ModConfig cfg = com.server_optimize.config.ModConfig.INSTANCE;
        long ms = cfg == null ? 10L : cfg.thread.randomTickParallelThresholdMs;
        return Math.max(0L, ms) * 1_000_000L;
    }

    /** Whether region-based multithreading is enabled ([thread.multithread.regionbased]). */
    private static boolean regionParallel() {
        com.server_optimize.config.ModConfig cfg = com.server_optimize.config.ModConfig.INSTANCE;
        return cfg != null && cfg.thread.regionbased.enableRegionBasedMultithreadTicking;
    }

    /**
     * Region mode: each region applies its collected random ticks on a worker with a
     * region-local RNG; every setBlock is deferred to the region scene and replayed on the
     * server thread afterwards, so shared structures (light, updates, events) are never
     * touched concurrently. The random sequence per region may diverge from vanilla - the
     * region engine syncs only chat and world time.
     *
     * <p>{@code stats} receives the split of this call, so a reading can say where the pass
     * wall went instead of only how long it was:
     * [0]=submit, [1]=join wall, [2]=replay, [3]=groups, [4]=units in the largest group,
     * [5]=busiest group's apply, [6]=sum of the groups' apply, [7]=replayed commits.
     */
    private static void applyRegions(ServerLevel level, List<Work> work, ForkJoinPool pool,
                                     long[] stats) {
        java.util.Map<Integer, List<Work>> byRegion = new java.util.HashMap<>();
        for (Work unit : work) {
            byRegion.computeIfAbsent(unit.regionId(), ignored -> new ArrayList<>()).add(unit);
        }
        int maxUnits = 0;
        for (List<Work> units : byRegion.values()) {
            if (units.size() > maxUnits) {
                maxUnits = units.size();
            }
        }
        // Pre-resolve every region's chunks on the server thread: a dense array covering the
        // region's chunks plus a one-chunk ring, so a worker's block reads are an indexed
        // lookup in its own array instead of a locked walk of the shared chunk maps. The
        // world is quiescent here (the sampling tasks have been joined), so the lookups need
        // no lock and the references cannot be unloaded while the apply runs.
        long submitStart = System.nanoTime();
        java.util.Map<Integer, RegionScene.Context> contexts = new java.util.HashMap<>();
        long tableFilled = 0L;
        long tableSize = 0L;
        for (java.util.Map.Entry<Integer, List<Work>> entry : byRegion.entrySet()) {
            RegionScene.Context context =
                buildRegionContext(level, entry.getKey(), entry.getValue());
            contexts.put(entry.getKey(), context);
            tableFilled += context.filledEntries();
            tableSize += context.totalEntries();
        }
        List<ForkJoinTask<Long>> tasks = new ArrayList<>();
        for (java.util.Map.Entry<Integer, RegionScene.Context> entry : contexts.entrySet()) {
            final RegionScene.Context context = entry.getValue();
            final List<Work> units = byRegion.get(entry.getKey());
            final net.minecraft.util.RandomSource regionRandom =
                regionRandom(level, entry.getKey());
            tasks.add(pool.submit(() -> {
                long taskStart = System.nanoTime();
                RegionScene.enterRegion(context);
                try {
                    for (Work unit : units) {
                        unit.applyRandomTicks(level, regionRandom);
                    }
                } finally {
                    RegionScene.exitRegion();
                }
                recordSlot(taskStart);
                return System.nanoTime() - taskStart;
            }));
        }
        long submitNanos = System.nanoTime() - submitStart;
        long joinStart = System.nanoTime();
        long applySum = 0L;
        long applyMax = 0L;
        for (ForkJoinTask<Long> task : tasks) {
            task.join();
            long duration = task.getRawResult();
            applySum += duration;
            if (duration > applyMax) {
                applyMax = duration;
            }
        }
        long joinNanos = System.nanoTime() - joinStart;
        long replayStart = System.nanoTime();
        long commits = 0L;
        for (RegionScene.Context context : contexts.values()) {
            for (RegionScene.Entry commit : context.scene()) {
                level.setBlock(commit.pos, commit.state, commit.flags);
                commits++;
            }
        }
        if (stats != null && stats.length >= 10) {
            stats[0] = submitNanos;
            stats[1] = joinNanos;
            stats[2] = System.nanoTime() - replayStart;
            stats[3] = byRegion.size();
            stats[4] = maxUnits;
            stats[5] = applyMax;
            stats[6] = applySum;
            stats[7] = commits;
            stats[8] = tableFilled;
            stats[9] = tableSize;
        }
    }

    /**
     * The connected-region path (randomTickParallel off): each CONNECTED region of the
     * partitioner runs as one worker task, sequentially in pass order with a region-local RNG -
     * the region's chunks are ticked by one worker like an independent server thread for that
     * region. Only regions run in parallel with each other. setBlock is deferred to the region
     * scene and replayed by the server thread after the join; anything outside the player
     * regions' area (forced-loaded chunks) is applied on the server thread afterwards.
     */
    private static void applyConnectedRegions(ServerLevel level, List<Work> work,
                                              ForkJoinPool pool, long[] stats) {
        java.util.List<net.minecraft.server.level.ServerPlayer> players = new java.util.ArrayList<>();
        for (net.minecraft.server.level.ServerPlayer player
            : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level) {
                players.add(player);
            }
        }
        // The region partition is the connected player-loaded areas (the partitioner's own
        // radius, the one the status command reports): players whose loaded areas touch share
        // one region and tick serially on one worker. Every ticking chunk is then assigned to
        // the region of the player it is nearest to (within the simulation distance), so the
        // whole ticking area is covered and no two workers ever touch the same chunk - the
        // ring beyond the loaded circles belongs to its region's worker like the circle does.
        it.unimi.dsi.fastutil.longs.Long2IntMap partitionChunkRegion =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        for (RegionPartitioner.Region region : RegionPartitioner.compute(level)) {
            for (net.minecraft.world.level.ChunkPos pos : region.chunks) {
                partitionChunkRegion.put(
                    net.minecraft.world.level.ChunkPos.asLong(pos.x, pos.z), region.id);
            }
        }
        int[] playerRegion = new int[players.size()];
        for (int i = 0; i < players.size(); i++) {
            net.minecraft.core.BlockPos pos = players.get(i).blockPosition();
            playerRegion[i] = partitionChunkRegion.get(
                net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        }
        int sim = level.getServer().getPlayerList().getSimulationDistance();
        java.util.Map<Integer, List<Work>> byRegion = new java.util.LinkedHashMap<>();
        List<Work> unassigned = new ArrayList<>();
        for (Work unit : work) {
            int cx = unit.chunkX();
            int cz = unit.chunkZ();
            int best = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int i = 0; i < players.size(); i++) {
                net.minecraft.core.BlockPos pos = players.get(i).blockPosition();
                long dx = Math.abs((long) cx - (pos.getX() >> 4));
                long dz = Math.abs((long) cz - (pos.getZ() >> 4));
                // Chebyshev distance: vanilla TICKS the SQUARE |dx|,|dz| <= simulation distance,
                // so the corner chunks of a player's area (dx=dz=16) are ticking too. A
                // Euclidean cutoff left those corners unassigned and they ran serially on the
                // server thread - the pass grew with the region count again.
                long distance = Math.max(dx, dz);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            if (best >= 0 && bestDistance <= sim && playerRegion[best] >= 0) {
                byRegion.computeIfAbsent(playerRegion[best], ignored -> new ArrayList<>()).add(unit);
            } else {
                unassigned.add(unit);
            }
        }
        int maxUnits = 0;
        for (List<Work> units : byRegion.values()) {
            if (units.size() > maxUnits) {
                maxUnits = units.size();
            }
        }
        long submitStart = System.nanoTime();
        java.util.Map<Integer, RegionScene.Context> contexts = new java.util.HashMap<>();
        long tableFilled = 0L;
        long tableSize = 0L;
        for (java.util.Map.Entry<Integer, List<Work>> entry : byRegion.entrySet()) {
            RegionScene.Context context =
                buildRegionContext(level, entry.getKey(), entry.getValue());
            contexts.put(entry.getKey(), context);
            tableFilled += context.filledEntries();
            tableSize += context.totalEntries();
        }
        List<ForkJoinTask<Long>> tasks = new ArrayList<>();
        // Natural spawning: vanilla keeps only the mob-cap census on the server thread (the
        // weather stays here too - the design rule keeps world time and weather serial); the
        // per-chunk spawn runs inside each region task, fully parallel across regions, with a
        // per-region SpawnState copy and the worker's own RNG (see RegionSpawn), and entity
        // creation deferred to the replay.
        com.server_optimize.mixin.accessor.ServerChunkCacheSpawnAccessor spawnAcc =
            (com.server_optimize.mixin.accessor.ServerChunkCacheSpawnAccessor)
                (Object) level.getChunkSource();
        net.minecraft.world.level.NaturalSpawner.SpawnState spawnSnapshot =
            spawnAcc.serverOptimize$getLastSpawnState();
        boolean spawnMobs = level.getGameRules().get(
            net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS);
        java.util.List<net.minecraft.world.entity.MobCategory> spawnCategories = java.util.List.of();
        if (spawnMobs && spawnSnapshot != null) {
            spawnCategories = net.minecraft.world.level.NaturalSpawner.getFilteredSpawningCategories(
                spawnSnapshot, true, spawnAcc.serverOptimize$isSpawnEnemies(),
                level.getGameTime() % 400L == 0L);
        }
        final java.util.List<net.minecraft.world.entity.MobCategory> categories = spawnCategories;
        final boolean spawnEnabled = spawnMobs;
        final long gameTime = level.getGameTime();
        // Weather per chunk stays on the server thread (world time and weather are serial by
        // design): vanilla did it inside its spawn loop, so it moves here as its own pass.
        for (Work unit : work) {
            level.tickThunder(level.getChunk(unit.chunkX(), unit.chunkZ()));
        }
        for (java.util.Map.Entry<Integer, RegionScene.Context> entry : contexts.entrySet()) {
            final RegionScene.Context context = entry.getValue();
            final List<Work> units = byRegion.get(entry.getKey());
            final net.minecraft.util.RandomSource regionRandom =
                regionRandom(level, entry.getKey());
            // One SpawnState per region: a value copy of the census counters, a fresh local-cap
            // calculator and the per-copy position/charge cache, so the spawn passes share no
            // mutable state; the spawn RNG comes from the worker's thread (RegionSpawn) via the
            // NaturalSpawner redirect. The spawn is fully parallel across regions now.
            final net.minecraft.world.level.NaturalSpawner.SpawnState regionState =
                com.server_optimize.thread.RegionSpawn.copySpawnState(spawnSnapshot, level);
            tasks.add(pool.submit(() -> {
                long taskStart = System.nanoTime();
                RegionScene.enterRegion(context);
                try {
                    // Sequential per chunk, in pass order: sample the positions, then apply
                    // them (precipitation first), then the natural spawn - like this region's
                    // own server thread would. The entity creation is deferred and added by
                    // the server thread during the replay.
                    for (Work unit : units) {
                        unit.sample();
                        unit.applyRandomTicks(level, regionRandom);
                        if (spawnEnabled && !categories.isEmpty()) {
                            net.minecraft.world.level.chunk.LevelChunk chunk =
                                (net.minecraft.world.level.chunk.LevelChunk)
                                    context.chunk(unit.chunkX(), unit.chunkZ());
                            if (chunk != null && level.canSpawnEntitiesInChunk(chunk.getPos())) {
                                chunk.incrementInhabitedTime(gameTime);
                                net.minecraft.world.level.NaturalSpawner.spawnForChunk(
                                    level, chunk, regionState, categories);
                            }
                        }
                    }
                } finally {
                    RegionScene.exitRegion();
                }
                recordSlot(taskStart);
                return System.nanoTime() - taskStart;
            }));
        }
        long submitNanos = System.nanoTime() - submitStart;
        long joinStart = System.nanoTime();
        long applySum = 0L;
        long applyMax = 0L;
        for (ForkJoinTask<Long> task : tasks) {
            task.join();
            long duration = task.getRawResult();
            applySum += duration;
            if (duration > applyMax) {
                applyMax = duration;
            }
        }
        long joinNanos = System.nanoTime() - joinStart;
        if (!tasks.isEmpty()) {
            long min = Long.MAX_VALUE;
            long max = 0L;
            for (ForkJoinTask<Long> task : tasks) {
                long duration = task.getRawResult();
                if (duration > max) {
                    max = duration;
                }
                if (duration < min) {
                    min = duration;
                }
            }
            RegionTickDriver.recordRegionRun(min, max);
        }
        long replayStart = System.nanoTime();
        long commits = 0L;
        for (RegionScene.Context context : contexts.values()) {
            for (RegionScene.Entry commit : context.scene()) {
                level.setBlock(commit.pos, commit.state, commit.flags);
                commits++;
            }
            // Tick schedules requested by the region's random ticks (frosted ice, dripstone,
            // ...) are replayed now that LevelTicks is reachable main-thread-only.
            for (RegionScene.ScheduledTick tick : context.ticks()) {
                level.scheduleTick(tick.pos, tick.block,
                    (int) (tick.triggerTick - level.getGameTime()), tick.priority);
            }
            // The region spawn pass created these; the server thread adds them now that the
            // world is quiescent, so the entity storage stays main-thread-only.
            for (RegionScene.EntityAdd add : context.entities()) {
                if (add.withPassengers) {
                    level.addFreshEntityWithPassengers(add.entity);
                } else {
                    level.addFreshEntity(add.entity);
                }
            }
        }
        long replayNanos = System.nanoTime() - replayStart;
        // Chunks with tickable sections outside any player region (forced-loaded areas):
        // applied on the server thread, vanilla style.
        for (Work unit : unassigned) {
            unit.sample();
            unit.applyRandomTicks(level);
        }
        if (stats != null && stats.length >= 10) {
            stats[0] = submitNanos;
            stats[1] = joinNanos;
            stats[2] = replayNanos;
            stats[3] = byRegion.size();
            stats[4] = maxUnits;
            stats[5] = applyMax;
            stats[6] = applySum;
            stats[7] = commits;
            stats[8] = tableFilled;
            stats[9] = tableSize;
        }
    }

    /**
     * The chunk table of one region: its units' chunks plus a one-chunk ring (a random tick can
     * read the neighbouring chunk), fetched on the server thread before the region task starts.
     */
    private static RegionScene.Context buildRegionContext(ServerLevel level, int regionId,
                                                          List<Work> units) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Work unit : units) {
            int cx = unit.chunkX();
            int cz = unit.chunkZ();
            if (cx < minX) {
                minX = cx;
            }
            if (cx > maxX) {
                maxX = cx;
            }
            if (cz < minZ) {
                minZ = cz;
            }
            if (cz > maxZ) {
                maxZ = cz;
            }
        }
        int baseX = minX - 1;
        int baseZ = minZ - 1;
        int width = maxX - minX + 3;
        int height = maxZ - minZ + 3;
        net.minecraft.server.level.ServerChunkCache cache = level.getChunkSource();
        net.minecraft.world.level.chunk.ChunkAccess[] table =
            new net.minecraft.world.level.chunk.ChunkAccess[width * height];
        int filled = 0;
        for (int cx = baseX; cx < baseX + width; cx++) {
            for (int cz = baseZ; cz < baseZ + height; cz++) {
                net.minecraft.world.level.chunk.ChunkAccess chunk =
                    RegionChunkLoad.loadedOrNull(cache, cx, cz);
                table[(cx - baseX) * height + (cz - baseZ)] = chunk;
                if (chunk != null) {
                    filled++;
                }
            }
        }
        return RegionScene.createContext(regionId, table, baseX, baseZ, width, height, filled);
    }

    /** A region-local RNG, derived deterministically from seed, tick and region id. */
    private static net.minecraft.util.RandomSource regionRandom(ServerLevel level, int regionId) {
        long seed = level.getSeed() * 31L + level.getGameTime() * 1000003L + regionId;
        return net.minecraft.util.RandomSource.create(seed);
    }

    // ------------------------------------------------------- region pass diagnostics

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("server-optimize");

    /** Region passes seen so far; the first few report their phase split. */
    private static int regionPasses;

    /** Region passes that are logged (bounded so a long run does not fill the log). */
    private static final int REGION_LOG_LIMIT = 40;

    /** After the first passes, one region pass is logged every this many ticks (once a minute). */
    private static final int REGION_LOG_PERIODIC = 1200;

    /** Read counters of the previous logged pass, for per-pass deltas. */
    private static long lastTableReads;
    private static long lastPresentReads;
    private static long lastEmptyReads;
    private static long lastOtherReads;

    /**
     * Reports one region pass: where its wall clock went (walk, ice and snow, sampling
     * barrier, region apply with the submit/join/replay split) plus the chunk reads the
     * workers needed. The first passes are logged so a reading can point at the part worth
     * working on; the read counters are per pass, not cumulative.
     */
    private static void logRegionPass(int units, long serverNanos, long iceSnowNanos,
                                      long barrierNanos, long applyNanos, long[] stats) {
        long table = RegionChunkLoad.TABLE_CALLS.sum();
        long present = RegionChunkLoad.PRESENT_CALLS.sum();
        long empty = RegionChunkLoad.EMPTY_CALLS.sum();
        long other = RegionChunkLoad.OTHER_CALLS.sum();
        long tableDelta = table - lastTableReads;
        long presentDelta = present - lastPresentReads;
        long emptyDelta = empty - lastEmptyReads;
        long otherDelta = other - lastOtherReads;
        lastTableReads = table;
        lastPresentReads = present;
        lastEmptyReads = empty;
        lastOtherReads = other;
        // Live split for /serveroptimize status thread count, so a reading says where the pass
        // wall went without needing the log.
        RegionTickDriver.recordPassSplit(serverNanos, iceSnowNanos, barrierNanos, applyNanos,
            stats[0], stats[1], stats[2], stats[7], stats[3],
            tableDelta, presentDelta, emptyDelta, otherDelta, stats[8] + "/" + stats[9]);
        regionPasses++;
        if (regionPasses > REGION_LOG_LIMIT && regionPasses % REGION_LOG_PERIODIC != 0) {
            return;
        }
        LOGGER.info("[region] pass {}: units={} groups={} maxUnits={} walk={}ms ice={}ms "
                + "barrier={}ms applyWall={}ms (submit={}ms join={}ms replay={}ms/{} commits) "
                + "applyWork={}ms busiestGroup={}ms reads={}table+{}present+{}empty+{}other "
                + "tableFill={}",
            regionPasses, units, stats[3], stats[4],
            millis(serverNanos), millis(iceSnowNanos), millis(barrierNanos), millis(applyNanos),
            millis(stats[0]), millis(stats[1]), millis(stats[2]), stats[7],
            millis(stats[6]), millis(stats[5]), tableDelta, presentDelta, emptyDelta,
            otherDelta, stats[8] + "/" + stats[9]);
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0D);
    }

    /**
     * The pool this mod installs and pins, or the common pool when not even that is
     * available. The pool is installed on first use: on the client integrated server the
     * event that would have installed it at start does not always run before the first flush,
     * and running the phase on whatever vanilla had left in place instead would mean an
     * unpinned pool of the wrong width (the width a reading reports as 18/19). Never a new
     * thread of our own - {@link AffinityManager} rebuilds Util's own executor, and the
     * command reports that same pool.
     */
    private static ForkJoinPool workerPool() {
        ForkJoinPool pool = AffinityManager.ensureWorkerPool();
        if (pool == null) {
            pool = ForkJoinPool.commonPool();
        }
        return pool.getParallelism() > 1 ? pool : null;
    }

    /** Busy time per pool thread since the snapshot, threads that ran nothing dropped. */
    public static long[] collectDeltas(long[] before) {
        long[] times = new long[before.length];
        int used = 0;
        for (int i = 0; i < before.length; i++) {
            long delta = SLOT_NANOS[i].sum() - before[i];
            if (delta > 0L) {
                times[used++] = delta;
            }
        }
        return Arrays.copyOf(times, used);
    }

    /**
     * Adds one worker's busy time to the slot of the thread that ran it. Called from the
     * worker itself, right after the sampling it measures.
     */
    public static void recordSlot(long startNanos) {
        int slot = (int) (Thread.currentThread().threadId() & (SLOTS - 1));
        SLOT_NANOS[slot].add(System.nanoTime() - startNanos);
    }

    public static long[] snapshotSlots() {
        long[] snapshot = new long[SLOTS];
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = SLOT_NANOS[i].sum();
        }
        return snapshot;
    }
}
