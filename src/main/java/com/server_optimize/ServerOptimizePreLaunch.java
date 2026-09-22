package com.server_optimize;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import com.server_optimize.thread.CpuTopology;

/**
 * Runs before mod initialisation, so the Lithium conflict resolution lands in
 * {@code config/lithium.properties} before Lithium reads its options.
 * Controlled by {@code [compatibility] disableOverlappingLithiumFeatures}.
 */
public final class ServerOptimizePreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        sizeCommonPool();
    }

    /**
     * Sizes ForkJoinPool.commonPool from the cores this process may really use.
     * The common pool reads its parallelism lazily on first use and can never be
     * resized afterwards, so this has to happen before any code touches it - which
     * is exactly what the preLaunch entrypoint guarantees. Purely mod side: no JVM
     * flag of the user is needed. An explicitly configured value is never overridden.
     */
    private static void sizeCommonPool() {
        try {
            String key = "java.util.concurrent.ForkJoinPool.common.parallelism";
            if (System.getProperty(key) != null) {
                return;
            }
            int allowed = CpuTopology.allowedCount();
            int jvmView = Runtime.getRuntime().availableProcessors();
            int parallelism = Math.max(1, allowed - 1);
            System.setProperty(key, String.valueOf(parallelism));
            ServerOptimize.LOGGER.info(
                "commonPool sized from the allowed CPU set: parallelism={} (allowed={}, jvmView={}, {})",
                parallelism, allowed, jvmView, CpuTopology.describe());
        } catch (Throwable t) {
            ServerOptimize.LOGGER.warn("could not size ForkJoinPool.commonPool", t);
        }
    }
}
