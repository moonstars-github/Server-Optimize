package com.server_optimize.thread;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CPU topology: which logical CPUs the process may really use, how they are
 * graded (P / E / LPE) and which ones share a physical core (SMT siblings).
 *
 * <p>No 64-CPU ceiling: masks are multi-word (up to 1024 logical CPUs, the size
 * of Linux's {@code cpu_set_t}). Windows restricts bitmask APIs to one processor
 * GROUP (≤64 CPUs), so on Windows the primary source is
 * {@code GetSystemCpuSetInformation}, which reports each CPU's group, logical
 * index, core index (SMT siblings) and efficiency class; the process CPU Sets
 * (a second, independent mechanism next to the legacy affinity mask) come from
 * {@code GetProcessDefaultCpuSets}.
 *
 * <p>Linux (and Android launchers such as FCL / ZL2, treated as Linux) uses
 * {@code sched_getaffinity} for the allowed set and sysfs for the grading:
 * {@code cpu_capacity} (or {@code cpufreq/cpuinfo_max_freq}) for the P/E/LPE tier
 * and {@code topology/thread_siblings_list} for SMT siblings.
 *
 * <p>Grading: distinct capacity/efficiency values are ranked; the top tier is P,
 * the middle tier (when present) is E and the lowest tier is LPE. A machine with
 * a single tier reports everything as P - no artificial split.
 *
 * <p>Everything is best effort: any failure degrades to "affinity mask only".
 */
public final class CpuTopology {

    /** Max logical CPUs tracked (Linux cpu_set_t size). */
    public static final int MAX_CPUS = 1024;
    private static final int WORDS = MAX_CPUS / 64;

    /**
     * Performance tiers are RANKED, not named: tier 0 is the fastest class the
     * machine reports and tier {@code tierCount()-1} the slowest. Nothing in the
     * scheduling code branches on a marketing name, so a machine with four or more
     * heterogeneous core classes (or a rack-level controller that re-grades at
     * runtime) needs no code change. The TIER_* constants below are only the
     * familiar labels for "fastest / second / slowest" and are kept for callers
     * that want to name a tier.
     */
    public static final int TIER_P = 0;
    public static final int TIER_E = 1;
    public static final int TIER_LPE = 2;

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer GetCurrentProcess();

        int GetActiveProcessorGroupCount();

        boolean GetSystemCpuSetInformation(Pointer information, int length, int[] returnedLength,
                                           Pointer process, int flags);

        boolean GetProcessDefaultCpuSets(Pointer process, int[] cpuSetIds, int count, int[] requiredCount);

        boolean GetProcessAffinityMask(Pointer process, long[] processMask, long[] systemMask);
    }

    private interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);

        int sched_getaffinity(int pid, long cpusetsize, byte[] mask);
    }

    private static final int TYPE_CPU_SET = 0;
    private static final int OFF_SIZE = 0;
    private static final int OFF_TYPE = 4;
    private static final int OFF_ID = 8;
    private static final int OFF_GROUP = 12;
    private static final int OFF_LOGICAL = 14;
    private static final int OFF_CORE = 15;
    private static final int OFF_EFFICIENCY = 18;

    private static volatile boolean read;

    /** Allowed logical CPUs (affinity ∩ CPU Sets), as a multi-word mask. */
    private static long[] allowed = new long[WORDS];
    /** Tier per logical CPU (index = global CPU number). */
    private static byte[] tier = new byte[MAX_CPUS];
    /** SMT siblings per physical core, keyed by "group:core". */
    private static final Map<String, Set<Integer>> SIBLINGS = new HashMap<>();
    private static int processorGroups = 1;
    private static boolean windows;
    /** SMT state as seen through the ALLOWED set: width 1 means "no sibling usable". */
    private static int smtWidth = 1;
    private static int physicalCores;
    private static final Map<Integer, Integer> CORE_ID = new HashMap<>();

    private CpuTopology() {
    }

    private static synchronized void ensureRead() {
        if (read) {
            return;
        }
        read = true;
        windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        try {
            if (windows) {
                readWindows();
            } else {
                readLinux();
            }
        } catch (Throwable ignored) {
            // degrade to affinity-only
        }
    }

    // ------------------------------------------------------------- Windows

    private static void readWindows() {
        Kernel32 k = Kernel32.INSTANCE;
        processorGroups = Math.max(1, k.GetActiveProcessorGroupCount());
        Pointer process = k.GetCurrentProcess();

        int[] needed = new int[1];
        k.GetSystemCpuSetInformation(null, 0, needed, process, 0);
        int length = needed[0];
        if (length <= 0) {
            return;
        }
        Memory buffer = new Memory(length);
        if (!k.GetSystemCpuSetInformation(buffer, length, needed, process, 0)) {
            return;
        }

        Map<Integer, Integer> setIdToCpu = new HashMap<>();
        Map<Integer, Integer> cpuToEfficiency = new TreeMap<>();
        int offset = 0;
        while (offset + 20 <= length) {
            int size = buffer.getInt(offset + OFF_SIZE);
            int type = buffer.getInt(offset + OFF_TYPE);
            if (size <= 0) {
                break;
            }
            if (type == TYPE_CPU_SET) {
                int id = buffer.getInt(offset + OFF_ID);
                int group = buffer.getShort(offset + OFF_GROUP) & 0xFFFF;
                int logical = buffer.getByte(offset + OFF_LOGICAL) & 0xFF;
                int core = buffer.getByte(offset + OFF_CORE) & 0xFF;
                int efficiency = buffer.getByte(offset + OFF_EFFICIENCY) & 0xFF;
                int cpu = group * 64 + logical;              // global CPU number (no 64 ceiling)
                if (cpu < MAX_CPUS) {
                    setIdToCpu.put(id, cpu);
                    cpuToEfficiency.put(cpu, efficiency);
                    SIBLINGS.computeIfAbsent(group + ":" + core, key -> new LinkedHashSet<>()).add(cpu);
                }
            }
            offset += size;
        }

        // allowed set: affinity mask (per group) intersect process CPU sets
        long[] affinity = readWindowsAffinity();
        int[] countNeeded = new int[1];
        k.GetProcessDefaultCpuSets(process, null, 0, countNeeded);
        boolean haveSets = false;
        long[] sets = new long[WORDS];
        if (countNeeded[0] > 0) {
            int[] ids = new int[countNeeded[0]];
            if (k.GetProcessDefaultCpuSets(process, ids, ids.length, countNeeded)) {
                for (int id : ids) {
                    Integer cpu = setIdToCpu.get(id);
                    if (cpu != null) {
                        sets[cpu >> 6] |= 1L << (cpu & 63);
                        haveSets = true;
                    }
                }
            }
        }
        System.arraycopy(affinity, 0, allowed, 0, WORDS);
        if (haveSets) {
            for (int i = 0; i < WORDS; i++) {
                allowed[i] &= sets[i];
            }
        }
        grade(cpuToEfficiency);
    }

    /** Affinity mask across processor groups; group 0 comes from the process mask. */
    private static long[] readWindowsAffinity() {
        long[] mask = new long[WORDS];
        long[] process = new long[1];
        long[] system = new long[1];
        if (Kernel32.INSTANCE.GetProcessAffinityMask(Kernel32.INSTANCE.GetCurrentProcess(), process, system)) {
            mask[0] = process[0];
        } else {
            int cpus = Runtime.getRuntime().availableProcessors();
            for (int i = 0; i < Math.min(cpus, 64); i++) {
                mask[0] |= 1L << i;
            }
        }
        return mask;
    }

    // --------------------------------------------------------------- Linux

    private static void readLinux() {
        byte[] buf = new byte[MAX_CPUS / 8];
        if (LibC.INSTANCE.sched_getaffinity(0, buf.length, buf) != 0) {
            return;
        }
        for (int cpu = 0; cpu < MAX_CPUS; cpu++) {
            if ((buf[cpu / 8] & (1 << (cpu % 8))) != 0) {
                allowed[cpu >> 6] |= 1L << (cpu & 63);
            }
        }
        Map<Integer, Integer> cpuToCapacity = new TreeMap<>();
        for (int cpu = 0; cpu < MAX_CPUS; cpu++) {
            if (!isAllowed(cpu)) {
                continue;
            }
            Integer capacity = readLong("/sys/devices/system/cpu/cpu" + cpu + "/cpu_capacity");
            if (capacity == null) {
                capacity = readLong("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq");
            }
            if (capacity != null) {
                cpuToCapacity.put(cpu, capacity);
            }
            String siblings = readText("/sys/devices/system/cpu/cpu" + cpu + "/topology/thread_siblings_list");
            if (siblings != null) {
                Set<Integer> group = new LinkedHashSet<>();
                for (String part : siblings.trim().split(",")) {
                    String[] range = part.split("-");
                    try {
                        int from = Integer.parseInt(range[0].trim());
                        int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                        for (int c = from; c <= to && c < MAX_CPUS; c++) {
                            group.add(c);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (group.size() > 1) {
                    SIBLINGS.put("cpu" + cpu, group);
                }
            }
        }
        grade(cpuToCapacity);
    }

    private static Integer readLong(String path) {
        String text = readText(path);
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readText(String path) {
        try {
            Path p = Path.of(path);
            return Files.isReadable(p) ? Files.readString(p) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    // --------------------------------------------------------------- grading

    /**
     * Ranks the distinct capacity/efficiency values: highest = P, middle = E,
     * lowest = LPE. A single value (or no data) leaves every CPU at P.
     */
    /** Number of distinct performance classes reported by the platform (>= 1). */
    private static int tierCount = 1;
    /** Relative performance of each tier, 1.0 = fastest class. */
    private static double[] tierScores = {1.0};

    /**
     * Ranks the distinct capacity/efficiency values of the platform: highest value
     * becomes tier 0, lowest becomes tier {@code tierCount-1}. Scores are normalised
     * against the fastest class so a scheduler can weigh "cost of running a task on
     * this tier" instead of guessing from a core type name.
     */
    private static void grade(Map<Integer, Integer> cpuToValue) {
        for (int cpu = 0; cpu < MAX_CPUS; cpu++) {
            tier[cpu] = 0;
        }
        if (cpuToValue.isEmpty()) {
            tierCount = 1;
            tierScores = new double[] {1.0};
            computeSmt();
            return;
        }
        java.util.TreeSet<Integer> distinct = new java.util.TreeSet<>(cpuToValue.values());
        List<Integer> descending = new ArrayList<>(distinct);
        java.util.Collections.reverse(descending);
        tierCount = descending.size();
        tierScores = new double[tierCount];
        int fastest = descending.get(0);
        for (int i = 0; i < tierCount; i++) {
            tierScores[i] = fastest == 0 ? 1.0 : descending.get(i) / (double) fastest;
        }
        for (Map.Entry<Integer, Integer> entry : cpuToValue.entrySet()) {
            tier[entry.getKey()] = (byte) descending.indexOf(entry.getValue());
        }
        computeSmt();
    }

    // ------------------------------------------------------------------ API

    private static boolean isAllowed(int cpu) {
        return cpu >= 0 && cpu < MAX_CPUS && (allowed[cpu >> 6] & (1L << (cpu & 63))) != 0;
    }

    /**
     * Works out the SMT situation AS THE SCHEDULER SEES IT: logical CPUs that share
     * a physical core are grouped, and the width is measured over the ALLOWED set
     * only. That last part matters: if a rule (or a cpuset) allows just one sibling
     * of each core - e.g. only even logical CPUs - then every allowed CPU is in fact
     * an exclusive core, and treating it as a hyperthread would make the allocator
     * needlessly pessimistic. Likewise, a machine with SMT switched off in firmware
     * reports one sibling per core and lands on width 1.
     */
    private static void computeSmt() {
        CORE_ID.clear();
        Map<Integer, Integer> coreOfCpu = new HashMap<>();
        Map<Integer, Integer> widthOfCore = new HashMap<>();
        int nextCore = 0;
        for (int cpu : allowedCpus()) {
            Integer id = null;
            for (Set<Integer> group : SIBLINGS.values()) {
                if (group.contains(cpu)) {
                    int key = minOf(group);
                    Integer existing = coreOfCpu.get(key);
                    if (existing == null) {
                        existing = nextCore++;
                        coreOfCpu.put(key, existing);
                    }
                    id = existing;
                    break;
                }
            }
            if (id == null) {
                id = nextCore++;
            }
            CORE_ID.put(cpu, id);
            widthOfCore.merge(id, 1, Integer::sum);
        }
        physicalCores = widthOfCore.size();
        smtWidth = 1;
        for (int width : widthOfCore.values()) {
            if (width > smtWidth) {
                smtWidth = width;
            }
        }
    }

    private static int minOf(Set<Integer> group) {
        int min = Integer.MAX_VALUE;
        for (int value : group) {
            if (value < min) {
                min = value;
            }
        }
        return min;
    }

    /** True when two allowed logical CPUs can share one physical core. */
    public static boolean smtEnabled() {
        ensureRead();
        return smtWidth > 1;
    }

    /** Max allowed logical CPUs per physical core (1 = SMT off, or siblings not allowed). */
    public static int smtWidth() {
        ensureRead();
        return smtWidth;
    }

    /** Number of distinct physical cores inside the allowed set. */
    public static int physicalCoreCount() {
        ensureRead();
        return Math.max(1, physicalCores);
    }

    /** Physical core a logical CPU belongs to (allowed set only). */
    public static int physicalCoreId(int cpu) {
        ensureRead();
        Integer id = CORE_ID.get(cpu);
        return id == null ? -1 : id;
    }

    /** Allowed logical CPUs, ascending. */
    public static List<Integer> allowedCpus() {
        ensureRead();
        List<Integer> out = new ArrayList<>();
        for (int cpu = 0; cpu < MAX_CPUS; cpu++) {
            if (isAllowed(cpu)) {
                out.add(cpu);
            }
        }
        return out;
    }

    public static int allowedCount() {
        ensureRead();
        int count = 0;
        for (long word : allowed) {
            count += Long.bitCount(word);
        }
        return count;
    }

    /** Number of performance classes the platform reports (1 = homogeneous). */
    public static int tierCount() {
        ensureRead();
        return tierCount;
    }

    /** Performance tier of a logical CPU: 0 = fastest class. */
    public static int tierOf(int cpu) {
        ensureRead();
        return cpu >= 0 && cpu < MAX_CPUS ? tier[cpu] : 0;
    }

    /** Relative performance of a tier (1.0 = fastest class). */
    public static double tierScore(int index) {
        ensureRead();
        return index >= 0 && index < tierScores.length ? tierScores[index] : 1.0;
    }

    /** Allowed CPUs of one tier, ascending. */
    public static List<Integer> cpusOfTier(int wanted) {
        ensureRead();
        List<Integer> out = new ArrayList<>();
        for (int cpu : allowedCpus()) {
            if (tier[cpu] == wanted) {
                out.add(cpu);
            }
        }
        return out;
    }

    /** Physical-core siblings of a logical CPU (including itself), or just itself. */
    public static List<Integer> siblingsOf(int cpu) {
        ensureRead();
        if (windows) {
            for (Set<Integer> group : SIBLINGS.values()) {
                if (group.contains(cpu)) {
                    return new ArrayList<>(group);
                }
            }
            return List.of(cpu);
        }
        Set<Integer> merged = new LinkedHashSet<>();
        for (Set<Integer> group : SIBLINGS.values()) {
            if (group.contains(cpu)) {
                merged.addAll(group);
            }
        }
        return merged.isEmpty() ? List.of(cpu) : new ArrayList<>(merged);
    }

    /** Legacy 64-bit view of the allowed set (truncates beyond CPU 63). */
    public static long allowedMaskLow() {
        ensureRead();
        return allowed[0];
    }

    /** Legacy 64-bit view of the P-core set (truncates beyond CPU 63). */
    public static long performanceMaskLow() {
        ensureRead();
        long mask = 0;
        for (int cpu : cpusOfTier(TIER_P)) {
            if (cpu < 64) {
                mask |= 1L << cpu;
            }
        }
        return mask;
    }

    /** Legacy 64-bit CPU-Set view (the intersection already lives in allowed). */
    public static long cpuSetMaskLow() {
        return 0L;
    }

    /** Previous name of {@link #cpuSetMaskLow()} (kept for callers). */
    public static long cpuSetMask() {
        return cpuSetMaskLow();
    }

    /** Previous name of {@link #performanceMaskLow()} (kept for callers). */
    public static long performanceMask() {
        return performanceMaskLow();
    }

    public static int processorGroups() {
        ensureRead();
        return processorGroups;
    }

    /** One diagnostic line. */
    public static String describe() {
        ensureRead();
        StringBuilder tiers = new StringBuilder();
        for (int i = 0; i < tierCount; i++) {
            if (i > 0) {
                tiers.append('+');
            }
            tiers.append(cpusOfTier(i).size()).append('@').append(String.format("%.2f", tierScores[i]));
        }
        return "allowed=" + allowedCount() + " cores=" + physicalCoreCount()
            + (smtWidth > 1 ? (" smt=on(" + smtWidth + ")") : " smt=off")
            + " tiers=[" + tiers + "] (" + tierCount + " classes)"
            + " siblings=" + SIBLINGS.size() + " groups=" + processorGroups
            + " mask0=0x" + Long.toHexString(allowed[0]);
    }
}
