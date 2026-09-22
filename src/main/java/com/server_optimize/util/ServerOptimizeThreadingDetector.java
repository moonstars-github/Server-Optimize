package com.server_optimize.util;

import net.minecraft.util.ThreadingDetector;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Shared per-thread-free replacement for the per-PalettedContainer
 * ThreadingDetector (chunk.reuseSyncObjects).
 *
 * Vanilla creates one ThreadingDetector per PalettedContainer, and each
 * detector allocates a Semaphore plus a ReentrantLock - measured ~7.4% of
 * server thread allocation during fast chunk loading. The detector only
 * guards against accidental re-entrant/deadlocked access; the normal path is
 * a single tryAcquire that succeeds immediately.
 *
 * This shared instance keeps the cross-thread mutual exclusion (render
 * thread cloning vs server thread writes) via one shared re-entrant lock,
 * but drops the per-container semaphore re-entrancy probe (which would
 * false-positive on nested container access when the semaphore is shared).
 * The lock is held for microseconds per operation, so the cross-thread
 * contention is negligible.
 */
public final class ServerOptimizeThreadingDetector extends ThreadingDetector {

    private static final Lock SHARED_LOCK = new ReentrantLock();

    private static final ServerOptimizeThreadingDetector INSTANCE = new ServerOptimizeThreadingDetector();

    private ServerOptimizeThreadingDetector() {
        super("server-optimize-shared");
    }

    public static ServerOptimizeThreadingDetector instance() {
        return INSTANCE;
    }

    @Override
    public void checkAndLock() {
        SHARED_LOCK.lock();
    }

    @Override
    public void checkAndUnlock() {
        SHARED_LOCK.unlock();
    }
}
