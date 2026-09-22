package com.server_optimize.thread;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.server_optimize.ServerOptimize;
import com.server_optimize.config.ModConfig;
import com.server_optimize.util.ServerOptimizeEnv;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.TracingExecutor;
import net.minecraft.util.Util;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread affinity management ([thread]).
 * <p>
 * Pins the dedicated/integrated server thread and the client render thread
 * to dedicated CPU cores (never the two hardware threads of the same
 * physical core when choosing automatically), rebuilds the vanilla worker
 * pool with per-worker pinning (thread.pinWorkers), and coordinates core
 * allocation across multiple Minecraft instances on the same machine via a
 * locked ledger file (thread.coordinateMultiInstance).
 * <p>
 * Implementation: Windows SetThreadAffinityMask (JNA, single processor
 * group = up to 64 logical CPUs), Linux sched_setaffinity, unsupported
 * platforms detect themselves and stay disabled.
 * <p>
 * The thread being pinned must call the apply method itself (the OS
 * affinity call is per-thread): the server thread applies at
 * MinecraftServer.runServer HEAD, the render thread at Minecraft.run HEAD,
 * worker threads inside ForkJoinWorkerThread.onStart().
 */
public final class AffinityManager {

    private AffinityManager() {
    }

    // ---------------------------------------------------------------- native

    /** Minimal kernel32 mapping (HANDLE = Pointer on 64-bit). */
    public interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer GetCurrentThread();

        Pointer GetCurrentProcess();

        boolean SetThreadAffinityMask(Pointer hThread, long dwThreadAffinityMask);

        boolean GetProcessAffinityMask(Pointer hProcess, long[] lpProcessAffinityMask, long[] lpSystemAffinityMask);
    }

    /** Minimal libc mapping for sched_setaffinity / sched_getaffinity. */
    public interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);

        int sched_setaffinity(int pid, long cpusetsize, byte[] cpuset);

        int sched_getaffinity(int pid, long cpusetsize, byte[] cpuset);
    }

    private enum Platform { WINDOWS, LINUX, UNSUPPORTED }

    private static final Platform PLATFORM = detectPlatform();

    private static Platform detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) return Platform.WINDOWS;
        if (os.contains("linux")) return Platform.LINUX;
        return Platform.UNSUPPORTED;
    }

    // ---------------------------------------------------------------- state

    private static volatile boolean initialized;
    private static volatile long processMask;
    private static volatile List<Integer> logicalCpus = List.of();
    private static volatile long serverMask;
    private static volatile long renderMask;
    private static volatile long workerMask;
    private static volatile boolean serverPinned;
    private static volatile boolean renderPinned;
    private static volatile boolean workerPoolInstalled;

    /** The ForkJoinPool the rebuild most recently installed, kept so the phase can rely on the pool it actually sized. */
    private static volatile java.util.concurrent.ForkJoinPool installedPool;
    private static volatile int installedParallelism = -1;
    private static volatile long installedWorkerMask;
    private static volatile int poolGeneration;
    private static volatile boolean pendingReapply;

    // Thread priority ordering after pinning: Render Thread > Server Thread
    // > Worker Thread. Java priorities (1..10) map to OS priorities via
    // HotSpot on every platform. Render=MAX so client FPS is never starved by
    // the integrated server thread; Server stays high (it drives the 20 tps
    // tick loop); Workers stay below normal so chunk gen/save/load never
    // steal the tick loop.
    private static final int RENDER_THREAD_PRIORITY = Thread.MAX_PRIORITY;       // 10
    private static final int SERVER_THREAD_PRIORITY = Thread.MAX_PRIORITY - 2;   // 8
    private static final int WORKER_THREAD_PRIORITY = Thread.NORM_PRIORITY - 1;  // 4

    // ---------------------------------------------------------- entry points

    /** Called from MinecraftServer.runServer HEAD (server thread). */
    public static void applyServerThread() {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.thread.enabled || !cfg.thread.pinServerThread) {
            return;
        }
        try {
            ensureInit(false);
            if (serverMask == 0) {
                return;
            }
            boolean ok = bindCurrentThread(serverMask);
            serverPinned = ok;
            if (ok) {
                Thread.currentThread().setPriority(SERVER_THREAD_PRIORITY);
                ServerOptimize.LOGGER.info(
                    "Thread affinity: server thread pinned to logical CPU {} (mask {}, priority {})",
                    coreOf(serverMask), Long.toHexString(serverMask), SERVER_THREAD_PRIORITY);
            }
        } catch (Throwable t) {
            ServerOptimize.LOGGER.warn("Thread affinity: server thread pinning failed", t);
        }
        // The worker pool install must not depend on the server-starting event: on the
        // client's integrated server that event does not reach us, so the pool stayed
        // vanilla and the random-tick phase ran on the common pool while every report
        // claimed the width this mod would have installed. The server thread passes
        // here on every environment; the install is idempotent and only rebuilds (and
        // logs) on a real change.
        if (!workerPoolInstalled) {
            installWorkerPool();
        }
    }

    /** Called from Minecraft.run HEAD (client render thread). */
    public static void applyRenderThread() {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.thread.enabled || !cfg.thread.pinRenderThread) {
            return;
        }
        try {
            ensureInit(false);
            if (renderMask == 0) {
                return;
            }
            boolean ok = bindCurrentThread(renderMask);
            renderPinned = ok;
            if (ok) {
                Thread.currentThread().setPriority(RENDER_THREAD_PRIORITY);
                ServerOptimize.LOGGER.info(
                    "Thread affinity: render thread pinned to logical CPU {} (mask {}, priority {})",
                    coreOf(renderMask), Long.toHexString(renderMask), RENDER_THREAD_PRIORITY);
            }
        } catch (Throwable t) {
            ServerOptimize.LOGGER.warn("Thread affinity: render thread pinning failed", t);
        }
    }

    /** Called from ForkJoinWorkerThread.onStart of the rebuilt worker pool. */
    public static void applyWorkerMask() {
        try {
            if (workerMask != 0) {
                bindCurrentThread(workerMask);
            }
            Thread.currentThread().setPriority(WORKER_THREAD_PRIORITY);
        } catch (Throwable t) {
            ServerOptimize.LOGGER.warn("Thread affinity: worker pinning failed", t);
        }
    }

    /** Called at SERVER_STARTING (integrated + dedicated). Rebuilds the
     *  vanilla worker pool when thread.pinWorkers is on. */
    public static void onServerStarting() {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.thread.enabled) {
            logAdaptivityInfo();
            return;
        }
        installWorkerPool();
    }

    /** Called from the reload command so affinity changes apply at the next
     *  tick boundary (server thread only; render changes need a restart). */
    public static void requestReapply() {
        pendingReapply = true;
    }

    /** Called from ServerTickEvents.END - applies pending re-pinning at the
     *  tick boundary, on the server thread. */
    public static void onServerTick() {
        if (!pendingReapply || !serverPinned) {
            return;
        }
        pendingReapply = false;
        try {
            ensureInit(true);
            applyServerThread();
            ServerOptimize.LOGGER.info("Thread affinity: re-applied after config reload");
        } catch (Throwable t) {
            ServerOptimize.LOGGER.warn("Thread affinity: re-apply failed", t);
        }
    }

    // -------------------------------------------------------------- init

    private static void ensureInit(boolean force) {
        if (initialized && !force) {
            return;
        }
        if (PLATFORM == Platform.UNSUPPORTED) {
            initialized = true;
            return;
        }
        ModConfig cfg = ModConfig.INSTANCE;
        long mask = readProcessAffinityMask();
        // Use the intersection of the process affinity mask and the process CPU sets:
        // an external tool (Process Lasso and friends) can restrict either one, and
        // CPU Sets were the stricter one on the affected machine - reading affinity alone
        // left the worker pool sized for all 20 logical CPUs instead of the 16 allowed,
        // which is what [thread.multithread] parallelThreads and the pinning then inherited.
        long cpuSetAllowed = CpuTopology.allowedMaskLow();
        if (cpuSetAllowed != 0) {
            mask &= cpuSetAllowed;
        }
        processMask = mask;
        logicalCpus = bitsOf(mask);

        int serverCore = cfg != null ? cfg.thread.serverThreadCore : -1;
        int renderCore = cfg != null ? cfg.thread.renderThreadCore : -1;

        // Multi-instance coordination: claim free cores via the ledger when
        // either core is auto (-1).
        boolean coordinate = cfg != null && cfg.thread.coordinateMultiInstance
            && (serverCore < 0 || renderCore < 0);
        if (coordinate) {
            int[] claimed = claimCores(logicalCpus, serverCore, renderCore);
            if (claimed != null) {
                if (serverCore < 0) {
                    serverCore = claimed[0];
                }
                // A dedicated server has no render thread; only clients claim
                // the second (render) core.
                if (renderCore < 0 && ServerOptimizeEnv.isClient() && claimed.length > 1) {
                    renderCore = claimed[1];
                }
            }
        }

        if (serverCore >= 0) {
            if (!logicalCpus.contains(serverCore)) {
                ServerOptimize.LOGGER.warn(
                    "thread.serverThreadCore={} is outside the process affinity mask; using auto", serverCore);
                serverCore = -1;
            } else {
                serverMask = 1L << serverCore;
            }
        }
        if (serverCore < 0 && !logicalCpus.isEmpty()) {
            serverCore = logicalCpus.get(0);
            serverMask = 1L << serverCore;
        }
        if (renderCore >= 0) {
            if (!logicalCpus.contains(renderCore) || renderCore == serverCore) {
                ServerOptimize.LOGGER.warn(
                    "thread.renderThreadCore={} invalid or equals the server core; using auto", renderCore);
                renderCore = -1;
            } else {
                renderMask = 1L << renderCore;
            }
        }
        if (renderCore < 0 && ServerOptimizeEnv.isClient() && logicalCpus.size() > 1) {
            // Auto-pick a core away from the server core to reduce the odds
            // of landing on the sibling hardware thread of the same physical
            // core (manual config is the reliable way).
            int mid = logicalCpus.get(Math.max(1, logicalCpus.size() / 2));
            if ((1L << mid) != serverMask) {
                renderCore = mid;
            } else {
                renderCore = logicalCpus.get(logicalCpus.size() - 1);
            }
            renderMask = 1L << renderCore;
        }
        workerMask = mask & ~serverMask & ~renderMask;
            long cpuSets = CpuTopology.cpuSetMask();
            if (cpuSets != 0) {
                workerMask &= cpuSets;
            }
        initialized = true;
    }

    private static int coreOf(long mask) {
        return Long.numberOfTrailingZeros(mask);
    }

    // ------------------------------------------------------------ affinity

    private static long readProcessAffinityMask() {
        switch (PLATFORM) {
            case WINDOWS -> {
                long[] process = new long[1];
                long[] system = new long[1];
                if (Kernel32.INSTANCE.GetProcessAffinityMask(
                    Kernel32.INSTANCE.GetCurrentProcess(), process, system)) {
                    return process[0];
                }
            }
            case LINUX -> {
                byte[] buf = new byte[128];
                if (LibC.INSTANCE.sched_getaffinity(0, buf.length, buf) == 0) {
                    long mask = 0;
                    for (int i = 0; i < Math.min(buf.length * 8, 64); i++) {
                        if ((buf[i / 8] & (1 << (i % 8))) != 0) {
                            mask |= 1L << i;
                        }
                    }
                    return mask;
                }
            }
            default -> {
            }
        }
        return -1L; // all logical CPUs
    }

    private static List<Integer> bitsOf(long mask) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            if ((mask & (1L << i)) != 0) {
                out.add(i);
            }
        }
        return out;
    }

    /** Pins the CURRENT thread to the given mask. */
    private static boolean bindCurrentThread(long mask) {
        switch (PLATFORM) {
            case WINDOWS -> {
                return Kernel32.INSTANCE.SetThreadAffinityMask(
                    Kernel32.INSTANCE.GetCurrentThread(), mask);
            }
            case LINUX -> {
                byte[] bytes = new byte[16];
                for (int i = 0; i < 64; i++) {
                    if ((mask & (1L << i)) != 0) {
                        bytes[i / 8] |= (byte) (1 << (i % 8));
                    }
                }
                return LibC.INSTANCE.sched_setaffinity(0, bytes.length, bytes) == 0;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------ worker pool

    /**
     * Number of logical CPUs this process may really use (the externally
     * restricted process affinity mask is already applied to {@link #processMask}),
     * minus the cores dedicated to the server/render threads.
     * Returns 1 when the mask is unknown (unrestricted).
     */
    public static int allowedWorkerCount() {
        ensureInit(false);
        long mask = processMask;
        if (mask == 0 || mask == -1L) {
            return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        }
        long usable = mask & ~serverMask & ~renderMask;
        // CPU Sets are the second, independent Windows mechanism (Process Lasso and
        // friends may set either or both): pinning outside them gets migrated back by
        // the scheduler, so only the intersection is really runnable.
        long cpuSets = CpuTopology.cpuSetMask();
        if (cpuSets != 0) {
            usable &= cpuSets;
        }
        int count = Long.bitCount(usable);
        int resolved = count > 0 ? count : Math.max(1, Long.bitCount(mask));
        ModConfig afCfg = ModConfig.INSTANCE;
        int configured = afCfg != null ? afCfg.thread.parallelThreads : 0;
        return configured > 0 ? Math.max(1, Math.min(configured, resolved)) : resolved;
    }

    /** Clamps a pool's desired thread count to what the CPU set actually allows. */
    public static int clampPoolSize(int desired) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.thread.enabled) {
            return desired;
        }
        int allowed = allowedWorkerCount();
        return Math.max(1, Math.min(desired, allowed));
    }

    /**
     * Pins the CURRENT thread (a pool worker) to one core of the allowed CPU set,
     * round-robin over the usable cores, so pool threads do not migrate between
     * cores and keep their L1/L2 working set. Best effort: returns false when
     * pinning is disabled or unsupported.
     */
    public static boolean pinPoolThread(String poolName, int index) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.thread.enabled || !cfg.thread.pinWorkers) {
            return false;
        }
        ensureInit(false);
        long mask = workerMask;
        long cpuSets = CpuTopology.cpuSetMask();
        if (cpuSets != 0) {
            mask &= cpuSets;
        }
        if (mask == 0 || mask == -1L) {
            return false;
        }
        List<Integer> cores = bitsOf(mask);
        if (cores.isEmpty()) {
            return false;
        }
        int core = cores.get(Math.floorMod(index, cores.size()));
        boolean ok = bindCurrentThread(1L << core);
        if (ok) {
            String key = poolName + "/" + index;
            if (PIN_LOGGED.add(key)) {
                ServerOptimize.LOGGER.info(
                    "Thread affinity: {} pool thread pinned to logical CPU {} (allowed set: {}, mask 0x{}, {})",
                    poolName, core, cores.size(), Long.toHexString(mask), CpuTopology.describe());
            }
            warnIfExternallyLimited();
        }
        return ok;
    }

    private static final java.util.Set<String> PIN_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static volatile boolean limitationWarned;

    /**
     * One-time warning when an external tool (e.g. Process Lasso) restricted the
     * process CPU set: our pools adapt, but pools sized by the JVM itself (ZGC,
     * Netty, ForkJoinPool.commonPool) cannot be resized at runtime and keep
     * using {@code availableProcessors}.
     */
    public static void warnIfExternallyLimited() {
        if (limitationWarned) {
            return;
        }
        ensureInit(false);
        long mask = processMask;
        if (mask == 0 || mask == -1L) {
            return;
        }
        int allowed = Long.bitCount(mask);
        int jvmView = Runtime.getRuntime().availableProcessors();
        if (allowed >= jvmView) {
            return;
        }
        limitationWarned = true;
        ServerOptimize.LOGGER.warn(
            "External CPU limit detected: the process affinity mask allows {} logical CPU(s) (mask 0x{}), "
            + "but the JVM reports {}. This mod's pools and the Minecraft worker pool are sized from the "
            + "allowed set, but ZGC/Netty/commonPool threads are fixed at startup - add "
            + "-XX:ActiveProcessorCount={} to the launch arguments so every pool matches the allowed set.",
            allowed, Long.toHexString(mask), jvmView, allowed);
    }

    /** One line describing the CPU set our pools use (for diagnostics). */
    public static String describeCpuSet() {
        ensureInit(false);
        long mask = processMask;
        if (mask == 0 || mask == -1L) {
            return "unrestricted (" + Runtime.getRuntime().availableProcessors() + " logical CPUs)";
        }
        return "allowed=" + Long.bitCount(mask) + " mask=0x" + Long.toHexString(mask)
            + " workers=" + Long.bitCount(workerMask) + " workerMask=0x" + Long.toHexString(workerMask)
            + " jvmView=" + Runtime.getRuntime().availableProcessors()
            + " | " + CpuTopology.describe()
            + (CpuTopology.processorGroups() > 1 ? " | WARNING: multiple processor groups, bitmask pinning covers group 0 only" : "");
    }

    /**
     * Rebuilds Util.backgroundExecutor with a pool whose worker threads pin
     * themselves on start. The COUNT stays vanilla (Util.maxAllowedExecutorThreads
     * = based on availableProcessors, which already adapts to the process CPU
     * affinity mask). Replaces the static final field via Unsafe (modifiers
     * reflection is blocked on JDK 21 without --add-opens).
     */
    /**
     * External CPU rules (Process Lasso and friends) can be applied a moment after the
     * JVM starts, so a single read at startup misses them. This watcher re-reads the
     * process affinity mask (and CPU Sets) every 5 s and only reacts once the value has
     * been stable for two consecutive samples - ProBalance-style temporary tweaks must not
     * make the pool thrash.
     */
    private static void startAffinityWatcher() {
        if (watcherStarted) {
            return;
        }
        watcherStarted = true;
        Thread t = new Thread(() -> {
            long lastSeen = processMask;
            int stable = 0;
            while (true) {
                try {
                    Thread.sleep(5000L);
                    long now = readProcessAffinityMask();
                    if (now == lastSeen || now == 0 || now == -1L) {
                        stable = 0;
                        continue;
                    }
                    if (++stable < 2) {
                        continue;
                    }
                    stable = 0;
                    long previous = lastSeen;
                    lastSeen = now;
                    ServerOptimize.LOGGER.info(
                        "Thread affinity: external CPU set changed (0x{} -> 0x{}, {}); re-applying",
                        Long.toHexString(previous), Long.toHexString(now), CpuTopology.describe());
                    ensureInit(true);
                    limitationWarned = false;
                    warnIfExternallyLimited();
                    installWorkerPool();   // rebuilds only when count or mask really changed
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "server-optimize-affinity-watch");
        t.setDaemon(true);
        t.start();
    }

    private static volatile boolean watcherStarted;

    private static void installWorkerPool() {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.thread.enabled || !cfg.thread.pinWorkers) {
            ServerOptimize.LOGGER.info(
                "Thread affinity: worker pool not installed (configLoaded={}, EnableThreadScheduler={}, pinWorkers={})",
                cfg != null, cfg != null && cfg.thread.enabled, cfg != null && cfg.thread.pinWorkers);
            return;
        }
        try {
            ensureInit(true);
            int desired = Math.max(1, allowedWorkerCount());
            // Idempotent: only a REAL change (thread count or mask) rebuilds the pool,
            // so re-entering a world or a ProBalance wobble cannot churn thread sets.
            if (workerPoolInstalled && desired == installedParallelism
                && workerMask == installedWorkerMask) {
                ServerOptimize.LOGGER.info(
                    "Thread affinity: worker pool unchanged ({} threads, workerMask=0x{}), no rebuild",
                    desired, Long.toHexString(workerMask));
                return;
            }
            // Thread count from the cores this process may really use: the externally
            // restricted process affinity mask intersected with the process CPU Sets,
            // minus the cores dedicated to the server/render threads. No JVM flag needed.
            int parallelism = Math.max(1, allowedWorkerCount());
            AtomicInteger counter = new AtomicInteger(1);
            ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> new ForkJoinWorkerThread(pool) {
                {
                    setName("Worker-Main-" + counter.getAndIncrement());
                }

                @Override
                protected void onStart() {
                    AffinityManager.applyWorkerMask();
                }
            };
            ForkJoinPool pool = new ForkJoinPool(parallelism, factory, null, false);
            replaceBackgroundExecutor(pool);
            installedPool = pool;
            workerPoolInstalled = true;
            installedParallelism = parallelism;
            installedWorkerMask = workerMask;
            poolGeneration++;
            ServerOptimize.LOGGER.info(
                "Thread affinity: worker pool rebuilt with {} threads (workerMask={})",
                parallelism, Long.toHexString(workerMask));
        } catch (Throwable t) {
            ServerOptimize.LOGGER.warn("Thread affinity: worker pool rebuild failed (keeping vanilla pool)", t);
        }
    }

    private static void replaceBackgroundExecutor(java.util.concurrent.ExecutorService service) {
        // The remapped production jar renames the field, so locate it by
        // type + current value (backgroundExecutor() returns the field), then
        // write it via sun.misc.Unsafe. A mixin @Accessor setter cannot write
        // a static FINAL field (JVM rejects putstatic from another class),
        // and on JDK 24+ static fields need staticFieldOffset/staticFieldBase
        // (objectFieldOffset throws IllegalArgumentException for them).
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            TracingExecutor current = com.server_optimize.mixin.accessor.UtilAccessor.serverOptimize$getBackgroundExecutor();
            Field target = null;
            for (Field f : Util.class.getDeclaredFields()) {
                if (TracingExecutor.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    if (f.get(null) == current) {
                        target = f;
                        break;
                    }
                }
            }
            if (target == null) {
                throw new IllegalStateException("Util.BACKGROUND_EXECUTOR field not found");
            }
            Object base = staticFieldBase.invoke(unsafe, target);
            long offset = (long) staticFieldOffset.invoke(unsafe, target);
            putObject.invoke(unsafe, base, offset, new TracingExecutor(service));
        } catch (Exception e) {
            throw new RuntimeException("Failed to replace Util.BACKGROUND_EXECUTOR", e);
        }
    }

    // ------------------------------------------------ multi-instance ledger

    private static final class InstanceEntry {
        long pid;
        String role;
        List<Integer> cores = new ArrayList<>();
        long ts;
    }

    private static Path ledgerPath() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("server-optimize-instances.json");
    }

    /** Claims free cores via a locked ledger file; returns [server, render]
     *  or null when coordination is disabled/unavailable. */
    private static int[] claimCores(List<Integer> allCpus, int fixedServer, int fixedRender) {
        Path path = ledgerPath();
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                List<InstanceEntry> entries = readLedger(channel);
                long myPid = ProcessHandle.current().pid();
                boolean dedicated = !ServerOptimizeEnv.isClient();
                String role = dedicated ? "server" : "client";

                // Prune dead instances.
                entries.removeIf(e -> !ProcessHandle.of(e.pid).map(ProcessHandle::isAlive).orElse(false));

                // Reuse our own previous claim when present.
                InstanceEntry mine = null;
                for (InstanceEntry e : entries) {
                    if (e.pid == myPid) {
                        mine = e;
                        break;
                    }
                }
                int serverCore = fixedServer;
                int renderCore = fixedRender;
                boolean wantRender = ServerOptimizeEnv.isClient();
                if (mine != null && mine.cores.size() >= 1) {
                    serverCore = mine.cores.get(0);
                    if (wantRender && mine.cores.size() >= 2) {
                        renderCore = mine.cores.get(1);
                    }
                    entries.remove(mine);
                } else {
                    // Pick the lowest cores not used by a live instance.
                    java.util.Set<Integer> used = new java.util.HashSet<>();
                    for (InstanceEntry e : entries) {
                        used.addAll(e.cores);
                    }
                    for (int c : allCpus) {
                        if (serverCore < 0 && !used.contains(c)) {
                            serverCore = c;
                            used.add(c);
                        } else if (serverCore >= 0 && wantRender && renderCore < 0
                            && !used.contains(c) && c != serverCore) {
                            renderCore = c;
                            used.add(c);
                            break;
                        }
                    }
                }
                if (serverCore < 0 && !allCpus.isEmpty()) {
                    serverCore = allCpus.get(0);
                }
                if (renderCore < 0 && wantRender && allCpus.size() > 1 && serverCore >= 0) {
                    final int skipCore = serverCore;
                    renderCore = allCpus.stream().filter(c -> c != skipCore)
                        .skip(allCpus.size() / 2).findFirst().orElse(-1);
                }
                // Write the ledger back.
                InstanceEntry self = new InstanceEntry();
                self.pid = myPid;
                self.role = role;
                self.ts = System.currentTimeMillis();
                if (serverCore >= 0) {
                    self.cores.add(serverCore);
                }
                if (renderCore >= 0) {
                    self.cores.add(renderCore);
                }
                entries.add(self);
                writeLedger(channel, entries);
                return new int[]{serverCore, renderCore};
            }
        } catch (Exception e) {
            ServerOptimize.LOGGER.warn("Thread affinity: multi-instance coordination failed ({}), using auto", e.toString());
            return null;
        }
    }

    private static List<InstanceEntry> readLedger(FileChannel channel) throws IOException {
        channel.position(0);
        long size = channel.size();
        if (size == 0) {
            return new ArrayList<>();
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) Math.min(size, 1 << 20));
        channel.read(buf);
        String json = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8).trim();
        if (json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return new Gson().fromJson(json, new TypeToken<List<InstanceEntry>>() {
            }.getType());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void writeLedger(FileChannel channel, List<InstanceEntry> entries) throws IOException {
        String json = new Gson().toJson(entries);
        channel.truncate(0);
        channel.position(0);
        channel.write(java.nio.ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        channel.force(true);
    }

    // ---------------------------------------------------------------- info

    /** Reports the affinity adaptivity state when [thread] is off. */
    private static void logAdaptivityInfo() {
        try {
            long mask = readProcessAffinityMask();
            int cpus = bitsOf(mask).size();
            ServerOptimize.LOGGER.info(
                "Thread affinity disabled ([thread].enabled=false); process CPU affinity allows {} logical CPUs, "
                    + "worker pool parallelism follows it automatically ({}). Enable [thread] to pin server/render threads.",
                cpus, Util.maxAllowedExecutorThreads());
        } catch (Throwable ignored) {
        }
    }

    /** True when [thread] is active and this side could pin something. */
    public static boolean isActive() {
        ModConfig cfg = ModConfig.INSTANCE;
        return cfg != null && cfg.thread.enabled && PLATFORM != Platform.UNSUPPORTED;
    }

    /** Client remote-connection state (thread.disableWorkersWhenConnected):
     *  the mod keeps its own background workers off while connected to a
     *  remote server. Pure-remote clients never fire SERVER_STARTING, so the
     *  worker pool is never rebuilt there anyway. */
    public static boolean remoteWorkersDisabled() {
        if (ModConfig.INSTANCE == null || !ModConfig.INSTANCE.thread.disableWorkersWhenConnected) {
            return false;
        }
        if (!ServerOptimizeEnv.isClient()) {
            return false;
        }
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            return mc.getConnection() != null && !mc.isLocalServer();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Number of worker threads the random-tick engine currently occupies (0 while the
     *  section-parallel engine is not built yet; the engine will publish its width here
     *  so /serveroptimize status thread reports it). */
    private static final java.util.concurrent.atomic.AtomicInteger RANDOM_TICK_THREADS =
        new java.util.concurrent.atomic.AtomicInteger();

    public static int randomTickThreads() {
        return RANDOM_TICK_THREADS.get();
    }

    /** True while the random-tick phase also runs on the server thread. */
    private static volatile boolean RANDOM_TICK_INCLUDES_SERVER_THREAD;

    /**
     * Publishes how the random-tick phase is currently staffed: the worker width plus
     * whether the server thread joins it. The phase is exclusive, so the server thread
     * is meant to take part rather than idle through it.
     */
    public static void publishRandomTickThreads(int workers, boolean includeServerThread) {
        RANDOM_TICK_THREADS.set(Math.max(0, workers));
        RANDOM_TICK_INCLUDES_SERVER_THREAD = includeServerThread && workers > 0;
    }

    /** "0" while the engine is idle, otherwise "14" or "14+Server Thread". */
    public static String randomTickThreadsText() {
        int workers = RANDOM_TICK_THREADS.get();
        if (workers <= 0) {
            return "0";
        }
        return RANDOM_TICK_INCLUDES_SERVER_THREAD ? workers + "+Server Thread" : Integer.toString(workers);
    }

    public static void setRandomTickThreads(int threads) {
        RANDOM_TICK_THREADS.set(Math.max(0, threads));
    }

    /**
     * The worker pool this mod rebuilt and pinned (Util.backgroundExecutor), or null when
     * it was not installed - callers must fall back to vanilla behaviour rather than
     * create a pool of their own.
     */
    public static java.util.concurrent.ForkJoinPool workerPool() {
        // The pool this mod built, first: relying on the vanilla field to still hold it as a
        // plain ForkJoinPool has failed on the client, where the field is a TracingExecutor
        // wrapper and a value written into it cannot be relied on to pass an instanceof check.
        java.util.concurrent.ForkJoinPool installed = installedPool;
        if (installed != null) {
            return installed;
        }
        Object executor = com.server_optimize.mixin.accessor.UtilAccessor.serverOptimize$getBackgroundExecutor();
        return executor instanceof java.util.concurrent.ForkJoinPool pool ? pool : null;
    }

    /**
     * The pool this mod rebuilt and pinned, installing it on first use when the event that
     * would have installed it at server start has not run yet - which is what happens on the
     * client integrated server, where installing at start is not reliable and the phase must
     * not silently run on an unpinned pool of the wrong width. Returns the vanilla background
     * executor when the mod's pool is not wanted (the scheduler or pinning is off) and null
     * only when the field does not hold a ForkJoinPool at all.
     */
    public static java.util.concurrent.ForkJoinPool ensureWorkerPool() {
        if (!workerPoolInstalled) {
            installWorkerPool();
        }
        return workerPool();
    }
}
