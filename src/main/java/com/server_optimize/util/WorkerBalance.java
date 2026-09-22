package com.server_optimize.util;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Load-balance statistics for the phases this mod runs on workers.
 *
 * <p>Per phase we keep the busy time of every participating thread - the server thread
 * included, because it takes part in the same phase (it draws the random-tick sample
 * positions and applies the ticks) - and report
 *
 * <pre>balance = shortest thread time / longest thread time * 100%</pre>
 *
 * <p>A value near 100% means every thread finished its slice at about the same time
 * (the work was spread evenly), a low value means some thread was still busy while
 * others had already run dry. Reported by {@code /serveroptimize status thread balance}.
 *
 * <p>The grand total ({@link #TOTAL_KEY}) averages every phase, so it becomes
 * meaningful as soon as more than one phase runs in parallel.
 */
public final class WorkerBalance {

    /** Phase name used for the aggregate over all phases. */
    public static final String TOTAL_KEY = "*";

    /** Phase name of the section-parallel random-tick phase. */
    public static final String RANDOM_TICK = "randomtick";

    /** Phase name of the region engine's per-region entity flush. */
    public static final String REGION = "region";

    /** phase name -> [0] = sum of the per-sample ratios, [1] = number of samples. */
    private static final Map<String, long[]> BALANCE = new ConcurrentHashMap<>();

    private WorkerBalance() {
    }

    /**
     * Records one completed phase.
     *
     * @param phase          phase name, see {@link #RANDOM_TICK}
     * @param perThreadNanos busy nanoseconds per participating thread; entries below one
     *                       nanosecond and the callers that had no work are ignored, and
     *                       fewer than two usable entries carries no information about
     *                       balance, so it is skipped
     */
    public static void record(String phase, long[] perThreadNanos) {
        if (phase == null || perThreadNanos == null || perThreadNanos.length < 2) {
            return;
        }
        long min = Long.MAX_VALUE;
        long max = 0L;
        for (long nanos : perThreadNanos) {
            if (nanos <= 0L) {
                continue;
            }
            if (nanos < min) {
                min = nanos;
            }
            if (nanos > max) {
                max = nanos;
            }
        }
        if (max <= 0L || min == Long.MAX_VALUE) {
            return;
        }
        // Guard against a rounding artefact turning a real sample into 0%.
        long percent = min * 100L / max;
        if (percent < 1L) {
            percent = 1L;
        } else if (percent > 100L) {
            percent = 100L;
        }
        accumulate(phase, percent);
        if (!TOTAL_KEY.equals(phase)) {
            accumulate(TOTAL_KEY, percent);
        }
        // Exact ratio in double, for two-decimal display.
        double ratio = (double) min * 100.0D / max;
        accumulateDouble(phase, ratio);
        if (!TOTAL_KEY.equals(phase)) {
            accumulateDouble(TOTAL_KEY, ratio);
        }
    }

    /** phase -> [0] = sum of the raw per-sample ratios, [1] = sample count. */
    private static final Map<String, double[]> BALANCE_D = new ConcurrentHashMap<>();

    private static void accumulateDouble(String key, double ratio) {
        double[] slot = BALANCE_D.computeIfAbsent(key, ignored -> new double[2]);
        synchronized (slot) {
            slot[0] += ratio;
            slot[1]++;
        }
    }

    /** Exact average balance of one phase in percent with two decimals, or -1 when it
     *  has not run yet. */
    public static double percentDouble(String phase) {
        double[] slot = phase == null ? null : BALANCE_D.get(phase);
        if (slot == null) {
            return -1.0D;
        }
        synchronized (slot) {
            return slot[1] == 0.0D ? -1.0D : slot[0] / slot[1];
        }
    }

    /** Average balance of one phase in percent, or -1 when it has not run yet. */
    public static long percent(String phase) {
        return average(BALANCE.get(phase));
    }

    /** Average balance across every phase in percent, or -1 when nothing ran yet. */
    public static long overallPercent() {
        return average(BALANCE.get(TOTAL_KEY));
    }

    /** Render a value from {@link #percent(String)} for command output. */
    public static String text(long percent) {
        return percent < 0L ? "无数据" : percent + "%";
    }

    /** Forgets all samples (used by a config reload). */
    public static void reset() {
        BALANCE.clear();
        BALANCE_D.clear();
        LAST_SPAN.clear();
        TICK_TOTALS.clear();
        DETAIL.clear();
    }

    private static void accumulate(String key, long percent) {
        long[] slot = BALANCE.computeIfAbsent(key, ignored -> new long[2]);
        synchronized (slot) {
            slot[0] += percent;
            slot[1]++;
        }
    }

    private static long average(long[] slot) {
        if (slot == null) {
            return -1L;
        }
        synchronized (slot) {
            return slot[1] == 0L ? -1L : slot[0] / slot[1];
        }
    }

    /** phase name -> wall-clock span of the most recent run, in nanoseconds. */
    private static final Map<String, Long> LAST_SPAN = new ConcurrentHashMap<>();

    /**
     * Records how long the phase took on the wall clock: first thread starting to last
     * thread finishing. Reported as 随机刻总用时 by /serveroptimize status thread count.
     */
    public static void recordSpan(String phase, long nanos) {
        if (phase != null && nanos > 0L) {
            LAST_SPAN.put(phase, nanos);
        }
    }

    /** Span of a phase's most recent run in milliseconds with three decimals. */
    public static String spanMillisText(String phase) {
        Long nanos = phase == null ? null : LAST_SPAN.get(phase);
        if (nanos == null || nanos <= 0L) {
            return "无数据";
        }
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    /** Wall clock of a phase's most recent run in milliseconds, or -1 when it has not run. */
    public static double lastSpanMillis(String phase) {
        Long nanos = phase == null ? null : LAST_SPAN.get(phase);
        return nanos == null || nanos <= 0L ? -1.0D : nanos / 1_000_000.0D;
    }

    /** phase -> [0] = game tick, [1] = sum of the phase spans in it, [2] = longest thread work, [3] = scheduling overhead. */
    private static final Map<String, long[]> TICK_TOTALS = new ConcurrentHashMap<>();

    /**
     * Accumulates one run of a phase into its game tick. A single run is one chunk, which
     * is far too small to compare against MSPT; the tick total is what a server operator
     * can hold next to the tick duration.
     *
     * @param spanNanos     wall clock of this run, first thread starting to last finishing
     * @param longestThread longest busy time of any participating thread in this run
     */
    public static void recordTick(String phase, long tick, long spanNanos, long longestThread) {
        if (phase == null || spanNanos <= 0L) {
            return;
        }
        long overhead = spanNanos - longestThread;
        recordTick(phase, tick, spanNanos, longestThread, overhead);
    }

    /**
     * Accumulates one run of a phase into its game tick with an explicit scheduling
     * overhead (for phases whose wall span is not measured the same way as their longest
     * thread work).
     */
    public static void recordTick(String phase, long tick, long spanNanos, long longestThread,
                                  long overheadNanos) {
        if (phase == null || spanNanos <= 0L) {
            return;
        }
        long[] slot = TICK_TOTALS.computeIfAbsent(phase, ignored -> new long[] {Long.MIN_VALUE, 0L, 0L, 0L});
        synchronized (slot) {
            if (slot[0] != tick) {
                slot[0] = tick;
                slot[1] = 0L;
                slot[2] = 0L;
                slot[3] = 0L;
            }
            slot[1] += spanNanos;
            if (longestThread > slot[2]) {
                slot[2] = longestThread;
            }
            if (overheadNanos > 0L) {
                slot[3] += overheadNanos;
            }
        }
    }

    /** Random-tick time accumulated for the current game tick, in milliseconds. */
    public static String tickMillisText(String phase) {
        return millis(TICK_TOTALS.get(phase), 1);
    }

    /**
     * Scheduling overhead of the current tick: the part of the wall clock that no
     * participating thread's own work covers - task hand-out, worker wake-up and stealing.
     */
    public static String overheadMillisText(String phase) {
        return millis(TICK_TOTALS.get(phase), 3);
    }

    /** Scheduling overhead of the current tick in milliseconds, or -1 when none. */
    public static double overheadMillis(String phase) {
        long[] slot = phase == null ? null : TICK_TOTALS.get(phase);
        if (slot == null) {
            return -1.0D;
        }
        synchronized (slot) {
            return slot[0] == Long.MIN_VALUE || slot[3] <= 0L ? -1.0D : slot[3] / 1_000_000.0D;
        }
    }

    private static String millis(long[] slot, int index) {
        if (slot == null) {
            return "无数据";
        }
        long nanos;
        synchronized (slot) {
            if (slot[0] == Long.MIN_VALUE || slot[index] <= 0L) {
                return "无数据";
            }
            nanos = slot[index];
        }
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    /**
     * phase -> [0]=span, [1]=server work, [2]=wait for the pool, [3]=apply, [4]=pool total,
     * [5]=busiest pool thread, [6]=participating threads, [7]=pool width, [8]=ice and snow.
     */
    private static final Map<String, long[]> DETAIL = new ConcurrentHashMap<>();

    /**
     * Records the split of one pass, so that a reading says where its wall clock went instead
     * of only how long it was: how much the server thread did itself, how much the pool did in
     * total and at most, how long the server waited for the pool, and how wide the pool was.
     *
     * <p>Only the last pass is kept: an average over a world that has since moved on would
     * describe a situation that no longer exists, and the whole point of the split is to point
     * at the part that is worth working on next.
     *
     * @param iceSnowNanos the part of the server work spent in the ice and snow pass, which
     *                     vanilla scales with the random tick speed and which is therefore worth
     *                     seeing on its own at a high one
     * @param workerNanos  busy time per participating pool thread; the server thread's own work
     *                     is passed separately and is deliberately not part of this array
     */
    public static void recordDetail(String phase, long spanNanos, long serverNanos,
                                    long iceSnowNanos, long joinWaitNanos, long applyNanos,
                                    long[] workerNanos, int poolWidth) {
        if (phase == null) {
            return;
        }
        long sum = 0L;
        long max = 0L;
        long participants = 0L;
        if (workerNanos != null) {
            for (long nanos : workerNanos) {
                if (nanos <= 0L) {
                    continue;
                }
                sum += nanos;
                participants++;
                if (nanos > max) {
                    max = nanos;
                }
            }
        }
        long[] slot = DETAIL.computeIfAbsent(phase, ignored -> new long[9]);
        synchronized (slot) {
            slot[0] = spanNanos;
            slot[1] = serverNanos;
            slot[2] = joinWaitNanos;
            slot[3] = applyNanos;
            slot[4] = sum;
            slot[5] = max;
            slot[6] = participants;
            slot[7] = poolWidth;
            slot[8] = iceSnowNanos;
        }
    }

    /** Server-thread work of the last pass - drawing positions and queueing them. */
    public static String serverMillisText(String phase) {
        return millis(DETAIL.get(phase), 1);
    }

    /** Time the server thread spent waiting for the pool in the last pass. */
    public static String joinWaitMillisText(String phase) {
        return millis(DETAIL.get(phase), 2);
    }

    /** Time spent applying the collected random ticks in the last pass. */
    public static String applyMillisText(String phase) {
        return millis(DETAIL.get(phase), 3);
    }

    /** Total busy time of the pool in the last pass. */
    public static String workerSumMillisText(String phase) {
        return millis(DETAIL.get(phase), 4);
    }

    /** Busiest pool thread in the last pass. */
    public static String workerBusiestMillisText(String phase) {
        return millis(DETAIL.get(phase), 5);
    }

    /** Time the server thread spent in the ice and snow pass of the last pass. */
    public static String iceSnowMillisText(String phase) {
        return millis(DETAIL.get(phase), 8);
    }

    /** Pool threads that ran something in the last pass, as used/width. */
    public static String participantsText(String phase) {
        long[] slot = phase == null ? null : DETAIL.get(phase);
        if (slot == null) {
            return "无数据";
        }
        synchronized (slot) {
            return slot[6] <= 0L ? "无数据" : slot[6] + "/" + slot[7];
        }
    }

    /** Whether the last pass of a phase ran on the pool, as opposed to serially. */
    public static boolean lastPassParallel(String phase) {
        long[] slot = phase == null ? null : DETAIL.get(phase);
        if (slot == null) {
            return false;
        }
        synchronized (slot) {
            return slot[7] > 0L;
        }
    }

    /**
     * How many pool threads were busy at once on average in the last pass: the pool's total
     * work over the busiest thread's work. 1.00 means the pool delivered the work of a single
     * thread however many it has, which is what a starved hand-out looks like.
     */
    public static String parallelismText(String phase) {
        long[] slot = phase == null ? null : DETAIL.get(phase);
        if (slot == null) {
            return "无数据";
        }
        synchronized (slot) {
            return slot[5] <= 0L
                ? "无数据"
                : String.format(Locale.ROOT, "%.2f", slot[4] / (double) slot[5]);
        }
    }
}
