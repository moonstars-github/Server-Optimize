package com.server_optimize.thread;

import com.server_optimize.config.ModConfig;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-region entity tick deferral (part of [thread.multithread.regionbased]).
 *
 * <p>The entity tick (movement, AI, push) runs on the server thread in vanilla. With the
 * region engine on, {@link #defer} collects each per-entity tick call of a region-mode tick
 * into per-region lists; {@link #flush} then runs the vanilla per-entity tick for the SLOW
 * CORE entities - the ones that cannot cross an entity-section boundary within one tick (pigs
 * and friends), whose position mutations stay inside the entity itself (single-writer,
 * region-owned) and never touch the shared section storage. The server thread parks at the
 * join, so no other worker mutates the entities this one reads. Players, MISC entities, the
 * border ring of each region and anything outside every region keep the vanilla serial tick.
 * Entity removals requested during a deferred tick are recorded and replayed by the server
 * thread after the join; entity additions already go through the region scene deferral.
 * The separate {@code thread.multithread.entityTickParallel} option is the WITHIN-region
 * further split for a single region's ultra-high entity load, built on top of this.
 */
public final class EntityRegionTicker {

    /** Per-level state of the tick currently being deferred. */
    public static final class State {
        public final ServerLevel level;
        public final Map<Integer, List<Entity>> byRegion = new HashMap<>();
        public final List<Entity> discards = new ArrayList<>();
        boolean submitted;
        private Long2IntMap chunkRegion;
        private LongOpenHashSet borderChunks;

        State(ServerLevel level) {
            this.level = level;
        }

        /** Lazily computed region membership of the connected partition, once per tick. */
        private void ensurePartition() {
            if (this.chunkRegion != null) {
                return;
            }
            this.chunkRegion = new Long2IntOpenHashMap();
            this.borderChunks = new LongOpenHashSet();
            for (RegionPartitioner.Region region : RegionPartitioner.compute(this.level)) {
                LongOpenHashSet in = new LongOpenHashSet();
                for (ChunkPos pos : region.chunks) {
                    in.add(ChunkPos.asLong(pos.x, pos.z));
                }
                for (ChunkPos pos : region.chunks) {
                    long key = ChunkPos.asLong(pos.x, pos.z);
                    this.chunkRegion.put(key, region.id);
                    // A chunk with a neighbour outside its region is border: its entities
                    // may interact with another worker's entities, so they stay serial.
                    if (!in.contains(ChunkPos.asLong(pos.x + 1, pos.z))
                        || !in.contains(ChunkPos.asLong(pos.x - 1, pos.z))
                        || !in.contains(ChunkPos.asLong(pos.x, pos.z + 1))
                        || !in.contains(ChunkPos.asLong(pos.x, pos.z - 1))) {
                        this.borderChunks.add(key);
                    }
                }
            }
        }

        /** The deferrable region of an entity, or null (keep it serial). */
        Integer regionOf(Entity entity) {
            if (entity instanceof Player || entity.getType().getCategory() == MobCategory.MISC) {
                return null;
            }
            ensurePartition();
            if (this.chunkRegion.isEmpty()) {
                return null;
            }
            int cx = entity.blockPosition().getX() >> 4;
            int cz = entity.blockPosition().getZ() >> 4;
            long key = ChunkPos.asLong(cx, cz);
            if (this.borderChunks.contains(key) || !this.chunkRegion.containsKey(key)) {
                return null;
            }
            return this.chunkRegion.get(key);
        }
    }

    /** The state of the tick being deferred, or null outside the deferral. */
    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();

    /** True while a region worker runs a deferred entity tick (skips re-deferral). */
    private static final ThreadLocal<Boolean> DEFERRED_RUN =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Failure-only movement diagnostic cap (a few lines per session, never a flood). */
    private static final java.util.concurrent.atomic.AtomicInteger FAILURE_LOGGED =
        new java.util.concurrent.atomic.AtomicInteger();

    private EntityRegionTicker() {
    }

    /** Whether a deferred entity tick is executing on this thread. */
    public static boolean deferredRun() {
        return DEFERRED_RUN.get();
    }

    /**
     * Records one entity for its region's worker tick. Returns true when the entity was
     * deferred; false keeps the vanilla serial tick on the server thread.
     */
    public static boolean defer(ServerLevel level, Entity entity) {
        ModConfig cfg = ModConfig.INSTANCE;
        // The per-region entity tick is part of the region engine: when the region mode is on,
        // each connected region's entities (the slow core ones) tick on that region's worker.
        // thread.multithread.entityTickParallel is a DIFFERENT feature - the within-region
        // further split for a single region's ultra-high entity load - which builds on this.
        if (cfg == null || !cfg.thread.regionbased.enableRegionBasedMultithreadTicking) {
            return false;
        }
        State state = ACTIVE.get();
        if (state == null) {
            state = new State(level);
            ACTIVE.set(state);
        }
        if (state.level != level || state.submitted) {
            return false;
        }
        Integer region = state.regionOf(entity);
        if (region == null) {
            return false;
        }
        state.byRegion.computeIfAbsent(region, ignored -> new ArrayList<>()).add(entity);
        return true;
    }

    /** Records a discard requested by a deferred tick; the server thread replays it. */
    public static void deferDiscard(Entity entity) {
        if (!DEFERRED_RUN.get()) {
            return;
        }
        State state = ACTIVE.get();
        if (state != null && state.level == entity.level()) {
            state.discards.add(entity);
        }
    }

    /** Runs the deferred entity ticks on the region workers, then replays the removals. */
    public static void flush(ServerLevel level) {
        State state = ACTIVE.get();
        if (state == null || state.level != level) {
            return;
        }
        ACTIVE.remove();
        if (state.byRegion.isEmpty()) {
            return;
        }
        state.submitted = true;
        long[] before = com.server_optimize.thread.RandomTickPass.snapshotSlots();
        long flushStart = System.nanoTime();
        java.util.concurrent.ForkJoinPool pool = AffinityManager.ensureWorkerPool();
        if (pool == null) {
            pool = java.util.concurrent.ForkJoinPool.commonPool();
        }
        List<java.util.concurrent.ForkJoinTask<?>> tasks = new ArrayList<>();
        for (Map.Entry<Integer, List<Entity>> entry : state.byRegion.entrySet()) {
            final int regionId = entry.getKey();
            final List<Entity> entities = entry.getValue();
            tasks.add(pool.submit(() -> {
                long taskStart = System.nanoTime();
                RegionScene.enterRegion(RegionScene.createStandaloneContext(level, regionId));
                try {
                    DEFERRED_RUN.set(Boolean.TRUE);
                    try {
                        for (Entity entity : entities) {
                            if (!entity.isRemoved()) {
                                net.minecraft.world.phys.Vec3 posBefore = entity.position();
                                level.tickNonPassenger(entity);
                                // Failure-only diagnostic: a living ground mob whose tick
                                // produced no movement at all (capped, so it cannot spam).
                                if (entity.isAlive() && entity.onGround()
                                    && entity instanceof net.minecraft.world.entity.Mob mob
                                    && posBefore.distanceToSqr(entity.position()) < 1.0E-6D
                                    && FAILURE_LOGGED.get() < 6) {
                                    FAILURE_LOGGED.incrementAndGet();
                                    net.minecraft.world.entity.ai.navigation.PathNavigation nav = mob.getNavigation();
                                    org.slf4j.LoggerFactory.getLogger("server-optimize").info(
                                        "[region] frozen: {} {} moved={} nav[isDone={} path={}] blockUnder={} "
                                            + "reads[table={} present={} empty={}]",
                                        mob.getType(), mob.position(),
                                        posBefore.distanceTo(mob.position()),
                                        nav.isDone(), nav.getPath() != null,
                                        mob.level().getBlockState(mob.blockPosition().below()),
                                        com.server_optimize.thread.RegionChunkLoad.TABLE_CALLS.sum(),
                                        com.server_optimize.thread.RegionChunkLoad.PRESENT_CALLS.sum(),
                                        com.server_optimize.thread.RegionChunkLoad.EMPTY_CALLS.sum());
                                }
                            }
                        }
                    } finally {
                        DEFERRED_RUN.set(Boolean.FALSE);
                    }
                } finally {
                    RegionScene.exitRegion();
                }
                com.server_optimize.thread.RandomTickPass.recordSlot(taskStart);
                return null;
            }));
        }
        for (java.util.concurrent.ForkJoinTask<?> task : tasks) {
            task.join();
        }
        long spanNanos = System.nanoTime() - flushStart;
        long[] workerNanos = com.server_optimize.thread.RandomTickPass.collectDeltas(before);
        // The per-region entity flush is its own phase ("region"), so it does not pollute the
        // random-tick phase's 随机刻总用时 / 随机刻 numbers; its per-thread busy times still
        // feed the region engine's balance and the scheduling-overhead accounting.
        com.server_optimize.util.WorkerBalance.record(
            com.server_optimize.util.WorkerBalance.REGION, workerNanos);
        com.server_optimize.util.WorkerBalance.recordSpan(
            com.server_optimize.util.WorkerBalance.REGION, spanNanos);
        long longestWorker = 0L;
        for (long nanos : workerNanos) {
            if (nanos > longestWorker) {
                longestWorker = nanos;
            }
        }
        // Overhead = wall span - the busiest thread's own work (the part no thread covers).
        long overhead = spanNanos - longestWorker;
        com.server_optimize.util.WorkerBalance.recordTick(
            com.server_optimize.util.WorkerBalance.REGION, level.getGameTime(),
            spanNanos, longestWorker, overhead);
        for (Entity entity : state.discards) {
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }
    }
}