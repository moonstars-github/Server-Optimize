package com.server_optimize.thread;

import com.server_optimize.config.ModConfig;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.BooleanSupplier;

/**
 * Region-based full-tick parallelism: when [thread.multithread.regionbased]
 * EnableRegionBasedMultithreadTicking is on, each dimension ticks as its own region on the
 * worker pool - the only synchronized surface is the server tick counter, which the main
 * thread owns and increments before tickChildren runs. Chat and the player list also stay on
 * the main thread, so the shared surface is exactly "chat + world time".
 *
 * <p>MinecraftServerRegionTickMixin diverts every ServerLevel.tick call here and joins at
 * the end of tickChildren, so the barrier is one join per server tick. A dimension's
 * simulation state (chunks, entities, random, tick lists, gamerules, profiler) is already
 * isolated by vanilla, which is what makes a whole dimension a safe region.
 *
 * <p>Known deviations from vanilla (both inherent to the design): inter-dimension events
 * (portal travel, respawn, the rare death-scoreboard write) can interleave, and scoreboard
 * mutation is not locked. Opt-in and off by default.
 *
 * <p>NOTE: offloading the whole ServerLevel.tick to a worker is NOT wired in - the level tick
 * issues chunk-source work that vanilla drives from the server thread, and a worker waiting on
 * that while the server thread waits at the join barrier is the deadlock this class was parked
 * on. The per-region simulation path is the one that moved off the server thread instead, and
 * it no longer has that dependency: an apply worker that needs an unloaded chunk loads and if
 * necessary generates it itself, pumping the chunk pipeline on its own thread
 * ({@link RegionChunkLoad}). RegionTickDriver stays as the scaffolding for offloading a whole
 * dimension, which would additionally need the chunk-source tick to keep running on the server
 * thread.
 */
public final class RegionTickDriver {

    private static final List<ForkJoinTask<?>> PENDING = new ArrayList<>();

    private RegionTickDriver() {
    }

    /** True when region ticking is enabled and a pool is available. */
    public static boolean enabled() {
        ModConfig cfg = ModConfig.INSTANCE;
        return cfg != null && cfg.thread.regionbased.enableRegionBasedMultithreadTicking;
    }

    /**
     * Runs one dimension's full tick on the pool. Returns true when the caller must NOT run
     * it itself - it was submitted and will be joined by {@link #joinLevelTicks}. Falls back
     * to running inline when the pool is unavailable.
     */
    public static boolean submitLevelTick(ServerLevel level, BooleanSupplier haveTime) {
        ForkJoinPool pool = AffinityManager.ensureWorkerPool();
        if (pool == null || pool.getParallelism() <= 1) {
            return false;
        }
        PENDING.add(pool.submit(() -> level.tick(haveTime)));
        return true;
    }

    /** Barrier: the server thread waits for every submitted dimension tick. */
    public static void joinLevelTicks() {
        for (ForkJoinTask<?> task : PENDING) {
            task.join();
        }
        PENDING.clear();
    }

    // ------------------------------------------------------------- statistics

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> REGION_COUNTS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final String TOTAL_KEY = "__total__";

    /** Recomputes the region layout of every dimension (for the status commands). */
    public static void recomputeRegions(net.minecraft.server.MinecraftServer server) {
        if (!enabled()) {
            return;
        }
        REGION_COUNTS.clear();
        int total = 0;
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            int count = RegionPartitioner.compute(level).size();
            REGION_COUNTS.put(dimensionKey(server, level), count);
            total += count;
        }
        REGION_COUNTS.put(TOTAL_KEY, total);
    }

    /** minecraft:overworld / the_nether / the_end, or "other". */
    private static String dimensionKey(net.minecraft.server.MinecraftServer server,
                                       net.minecraft.server.level.ServerLevel level) {
        if (level == server.getLevel(net.minecraft.world.level.Level.OVERWORLD)) {
            return "minecraft:overworld";
        }
        if (level == server.getLevel(net.minecraft.world.level.Level.NETHER)) {
            return "minecraft:the_nether";
        }
        if (level == server.getLevel(net.minecraft.world.level.Level.END)) {
            return "minecraft:the_end";
        }
        return "other";
    }

    /** Region count across all dimensions, or 0 when disabled / not measured yet. */
    public static int totalRegionCount() {
        Integer n = REGION_COUNTS.get(TOTAL_KEY);
        return n == null ? 0 : n;
    }

    /** Region count of one dimension ("minecraft:overworld", ...) or 0. */
    public static int regionCountOf(String location) {
        Integer n = REGION_COUNTS.get(location);
        return n == null ? 0 : n;
    }

    /** Min/max of one region execution pass; unit 2 records, the balance command reads. */
    private static final long[] REGION_BALANCE = new long[2];

    /**
     * Phase split of the last region pass, for the status command:
     * [0]=walk, [1]=ice and snow draws, [2]=sampling barrier, [3]=apply wall,
     * [4]=region table build, [5]=apply join, [6]=replay, [7]=commits, [8]=groups.
     * Reading it next to 随机刻总用时 says where the wall went: the serial part the server
     * thread still does, or the parallel apply divided by the pool.
     */
    private static final long[] LAST_PASS = new long[9];

    /** Chunk reads served by the region's pre-resolved table in the last pass. */
    private static volatile long lastTableReads;

    /** Chunk reads served by an already-loaded chunk outside the table in the last pass. */
    private static volatile long lastPresentReads;

    /** Chunk reads answered empty because the chunk was not loaded, in the last pass. */
    private static volatile long lastEmptyReads;

    /** Reads not asking for FULL status that no table entry satisfied, in the last pass. */
    private static volatile long lastOtherReads;

    /** Table fill of the last pass, "filled/total". */
    private static volatile String lastTableFill = "无数据";

    /** Records the phase split of one region pass (fed by RandomTickPass). */
    public static void recordPassSplit(long walkNanos, long iceSnowNanos, long barrierNanos,
                                       long applyNanos, long tableNanos, long joinNanos,
                                       long replayNanos, long commits, long groups,
                                       long tableReads, long presentReads, long emptyReads,
                                       long otherReads, String tableFill) {
        synchronized (LAST_PASS) {
            LAST_PASS[0] = walkNanos;
            LAST_PASS[1] = iceSnowNanos;
            LAST_PASS[2] = barrierNanos;
            LAST_PASS[3] = applyNanos;
            LAST_PASS[4] = tableNanos;
            LAST_PASS[5] = joinNanos;
            LAST_PASS[6] = replayNanos;
            LAST_PASS[7] = commits;
            LAST_PASS[8] = groups;
        }
        lastTableReads = tableReads;
        lastPresentReads = presentReads;
        lastEmptyReads = emptyReads;
        lastOtherReads = otherReads;
        lastTableFill = tableFill;
    }

    /** The last region pass as {@code walk/ice/barrier/apply(join)/replay} in milliseconds. */
    public static String passSplitText() {
        synchronized (LAST_PASS) {
            if (LAST_PASS[3] <= 0L && LAST_PASS[0] <= 0L) {
                return "无数据";
            }
            return String.format(java.util.Locale.ROOT,
                "%.2f / %.2f / %.2f / %.2f (join %.2f, table %.2f, replay %.2f) ms, %d 组",
                LAST_PASS[0] / 1e6, LAST_PASS[1] / 1e6, LAST_PASS[2] / 1e6, LAST_PASS[3] / 1e6,
                LAST_PASS[5] / 1e6, LAST_PASS[4] / 1e6, LAST_PASS[6] / 1e6, LAST_PASS[8]);
        }
    }

    /** Chunk reads of the last pass: table hits, loaded chunks, empty fallbacks, other states. */
    public static String passReadsText() {
        long table = lastTableReads;
        long present = lastPresentReads;
        long empty = lastEmptyReads;
        long other = lastOtherReads;
        if (table == 0L && present == 0L && empty == 0L && other == 0L) {
            return "无数据";
        }
        return table + " 表内 / " + present + " 现取 / " + empty + " 未加载(空占位) / "
            + other + " 非FULL状态 (表填充 " + lastTableFill + ")";
    }

    /** Scheduling overhead accumulated by the region module, in milliseconds. */
    private static volatile double regionOverheadMillis;

    /** Records the region module's scheduling overhead (unit 2 feeds this). */
    public static void recordRegionOverheadMillis(double millis) {
        regionOverheadMillis = millis;
    }

    /** The region module's scheduling overhead, in milliseconds (0 until unit 2). */
    public static double regionOverheadMillis() {
        return regionOverheadMillis;
    }

    /** Records one region-parallel pass for the balance ratio. */
    public static void recordRegionRun(long minNanos, long maxNanos) {
        REGION_BALANCE[0] = minNanos;
        REGION_BALANCE[1] = maxNanos;
    }

    /** 仅可分割一个区域 when there is nothing to balance, else min/max as a percent. */
    public static String regionBalanceText() {
        if (!enabled()) {
            return "无数据";
        }
        int regions = totalRegionCount();
        if (regions == 0) {
            // Not measured yet (the layout is recomputed every 20 ticks), or the gate is off.
            return "无数据";
        }
        if (regions == 1) {
            return "仅可分割一个区域";
        }
        long min = REGION_BALANCE[0];
        long max = REGION_BALANCE[1];
        if (max <= 0L) {
            // Multiple regions exist but no parallel pass has been executed yet.
            return "无数据";
        }
        long percent = min * 100L / max;
        return String.format(java.util.Locale.ROOT, "%.2f%%", (double) min * 100.0D / max);
    }
}
