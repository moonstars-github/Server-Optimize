package com.server_optimize.util;

import com.server_optimize.config.ModConfig;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Batch region loading coordinator (chunk.regionBatchLoader).
 * <p>
 * When the first chunk of a region is requested for read, a background
 * prefetch task is submitted to the common ForkJoinPool that reads ALL
 * present chunks from that region's storage (the write-back mirror in
 * memory) and parses them into a shared map. Subsequent chunk reads for
 * the same region receive their result from this prefetched map instead
 * of going through the per-chunk IOWorker task chain.
 * <p>
 * The prefetch happens once per region. If the region is already cached
 * (regionFileCaching mirror), the prefetch is a fast in-memory operation.
 */
public final class RegionBatchLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("server-optimize");

    // regionKey -> shared prefetch future (map of all present chunk tags)
    private static final ConcurrentHashMap<Long, CompletableFuture<Void>> PREFETCHES = new ConcurrentHashMap<>();

    private static final Executor PREFETCHER = ForkJoinPool.commonPool();

    private RegionBatchLoader() {
    }

    /**
     * Get or create a region prefetch. Returns the shared future that, when
     * complete, has populated the tag cache for all chunks in the region.
     * The caller is responsible for consulting the cache after completion.
     *
     * @param regionKey packed region coordinates (x>>5, z>>5)
     * @param regionPrefetcher a runnable that performs the actual batch read
     * @return true if this call initiated the prefetch (first caller), false otherwise
     */
    public static boolean prefetchRegion(long regionKey, Runnable regionPrefetcher) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.regionBatchLoader) {
            return false;
        }
        CompletableFuture<Void> existing = PREFETCHES.get(regionKey);
        if (existing != null) {
            return false; // already running or done
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture<Void> race = PREFETCHES.putIfAbsent(regionKey, future);
        if (race != null) {
            return false; // another thread beat us
        }
        PREFETCHER.execute(() -> {
            try {
                regionPrefetcher.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                PREFETCHES.remove(regionKey);
            }
        });
        return true;
    }

    /**
     * Wait for the region prefetch to complete (blocking).
     */
    public static void awaitRegion(long regionKey) {
        CompletableFuture<Void> f = PREFETCHES.get(regionKey);
        if (f != null) {
            f.join();
        }
    }

    /** Clean up after a region's chunks are no longer pending. */
    public static void finishRegion(long regionKey) {
        PREFETCHES.remove(regionKey);
    }

    /** Check if a region currently has a prefetch running/completed. */
    public static boolean isPrefetching(long regionKey) {
        return PREFETCHES.containsKey(regionKey);
    }

    /** Compute the region key for a chunk position. */
    public static long regionKey(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX >> 5, chunkZ >> 5);
    }

    /** Compute the region key for a ChunkPos. */
    public static long regionKey(ChunkPos pos) {
        return ChunkPos.asLong(pos.x >> 5, pos.z >> 5);
    }
}