package com.server_optimize.util;

import com.server_optimize.ServerOptimize;
import com.server_optimize.config.ModConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk load/save counters for diagnostic logging ([log] config group).
 * <p>
 * Two modes:
 * <ul>
 *   <li>{@code log.chunkIO = true} — log every chunk load/save (dimension + pos + size)
 *   <li>{@code log.chunkSummary = true} — only log the 30 s summary in the existing diag line
 * </ul>
 * Both are off by default (false). The counters are always incremented so
 * the summary is available regardless of the config setting; only the log
 * output is gated.
 */
public final class ChunkIOCounters {

    private static final AtomicLong loaded = new AtomicLong();
    private static final AtomicLong saved = new AtomicLong();
    /** 30s window counter for chunk load rate (chunks/s). Counts chunks
     *  delivered to players by PlayerChunkSender — reflects true loading
     *  throughput regardless of source (disk / region cache / generation). */
    private static final AtomicLong loadRate = new AtomicLong();

    /** Called every time a chunk NBT is read from disk (IOWorker thread). */
    public static void onLoad(String dimension, int chunkX, int chunkZ, int bytes) {
        loaded.incrementAndGet();
        if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.log.chunkIO) {
            ServerOptimize.LOGGER.info("chunk-load: {} ({},{}) {} bytes", dimension, chunkX, chunkZ, bytes);
        }
    }

    /** Called every time a chunk is saved to disk. */
    public static void onSave(String dimension, int chunkX, int chunkZ, boolean lightCorrect, String type) {
        saved.incrementAndGet();
        if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.log.chunkIO) {
            ServerOptimize.LOGGER.info("chunk-save: {} ({},{}) light={} type={}", dimension, chunkX, chunkZ, lightCorrect, type);
        }
    }

    /** Called every time a chunk is sent to a player (PlayerChunkSender). */
    public static void onChunkSent() {
        loadRate.incrementAndGet();
    }

    /** Reset counters and return the snapshot. */
    public static long[] reset() {
        return new long[]{ loaded.getAndSet(0), saved.getAndSet(0) };
    }

    /** Reset the load-rate counter and return the count. */
    public static long resetLoadRate() {
        return loadRate.getAndSet(0);
    }

    private ChunkIOCounters() {
    }
}