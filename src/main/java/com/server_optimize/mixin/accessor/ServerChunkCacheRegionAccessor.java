package com.server_optimize.mixin.accessor;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/**
 * Reach into {@link ServerChunkCache} for the region-engine worker chunk reads
 * ([thread.multithread.regionbased]).
 *
 * <p>{@code getVisibleChunkIfPresent} is the read-only holder lookup used by both the region
 * table build (server thread) and the worker's present-chunk fallback. Region workers never
 * load chunks - see {@link com.server_optimize.thread.RegionChunkLoad} - so no part of the
 * load pipeline is reached from here. {@code chunkMap} feeds the per-region spawn state's
 * local-cap calculator.
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheRegionAccessor {

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder serverOptimize$callGetVisibleChunkIfPresent(long pos);

    @Accessor("chunkMap")
    ChunkMap serverOptimize$getChunkMap();

    /** Package-private ServerChunkCache.runDistanceManagerUpdates (the per-tick chunk-loading
     *  pump: distance-manager updates + generation-task feeding), reachable for the extra
     *  pumping rounds while a generation backlog exists (chunk.genUpdatesPerTick). */
    @Invoker("runDistanceManagerUpdates")
    boolean serverOptimize$callRunDistanceManagerUpdates();
}
