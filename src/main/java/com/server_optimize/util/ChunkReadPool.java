package com.server_optimize.util;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated chunk-read pool for the region batch loader (chunk.regionBatchLoader).
 * <p>
 * Replaces the {@code ThreadPoolExecutor} used before, whose workers park on a
 * blocking queue {@code Condition}. Every park allocated an
 * {@code AbstractQueuedSynchronizer$ConditionNode}: the 15.8.10 profile priced
 * that at 21.2 GB = 8.9% of ALL allocation in the run (each of the eight
 * {@code server-optimize-io-reader-*} threads allocating ~7 GB of park nodes),
 * plus the wake-up latency of handing every single chunk over through a monitor.
 * <p>
 * Here the queue is lock-free ({@link ConcurrentLinkedQueue}) and idle workers
 * park through {@link LockSupport}, which reuses the per-thread Parker object -
 * parking and unparking allocate nothing. Workers also drain in batches, so one
 * wake-up can serve several queued chunks.
 */
public final class ChunkReadPool {

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger waiting = new AtomicInteger();
    private final Thread[] workers;
    private volatile boolean running = true;

    public ChunkReadPool(int threads, String namePrefix) {
        this.workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int index = i;
            Thread t = new Thread(() -> {
                run(namePrefix, index);
            }, namePrefix + (i + 1));
            t.setDaemon(true);
            workers[i] = t;
            t.start();
        }
    }

    public void execute(Runnable task) {
        queue.add(task);
        // Wake one parked worker; unpark on a running thread is a cheap no-op.
        Thread w = workers[Math.floorMod(waiting.get(), workers.length)];
        LockSupport.unpark(w);
    }

    private void run(String poolName, int index) {
        boolean pinAttempted = false;
        while (running) {
            if (!pinAttempted) {
                // lazy: pool threads come from a static initialiser that can run
                // before the mod config exists, where pinning would silently fail
                pinAttempted = true;
                com.server_optimize.thread.AffinityManager.pinPoolThread(poolName, index);
            }
            Runnable task = queue.poll();
            if (task == null) {
                waiting.incrementAndGet();
                // park with a timeout so a missed unpark can never stall a reader
                LockSupport.parkNanos(1_000_000L);
                waiting.decrementAndGet();
                continue;
            }
            // drain a batch: one wake-up serves several chunks
            do {
                try {
                    task.run();
                } catch (Throwable ignored) {
                    // the future carries its own failure; keep the worker alive
                }
                task = queue.poll();
            } while (task != null);
        }
    }
}
