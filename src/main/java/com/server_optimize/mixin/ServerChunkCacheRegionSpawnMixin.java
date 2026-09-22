package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Moves the per-chunk natural-spawn loop and the spawning-chunk list build off the server
 * thread ([thread.multithread.regionbased], connected-region path only).
 *
 * <p>Vanilla's {@code ServerChunkCache.tickChunks} builds the spawning-chunk list
 * ({@code ChunkMap.collectSpawningChunks}) and then loops over it calling
 * {@code tickSpawningChunk} - both O(ticking chunks) on the server thread, growing with the
 * number of regions. In the connected-region path the list build is skipped (the list stays
 * empty, so the vanilla loop does nothing) and the spawning itself runs inside the region
 * tasks (see {@link com.server_optimize.thread.RandomTickPass}), with the shared mob-cap
 * state serialized and the entity creation deferred to the server thread's replay. Weather
 * ({@code ServerLevel.tickThunder}) is kept on the server thread per the design rule that
 * world time and weather stay serial.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheRegionSpawnMixin {

    @Redirect(
        method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;collectSpawningChunks(Ljava/util/List;)V")
    )
    private void serverOptimize$skipSpawnListBuild(ChunkMap chunkMap, List list) {
        if (serverOptimize$regionConnected()) {
            // The spawning-chunk list stays empty, so vanilla's per-chunk spawn loop below
            // iterates nothing; the region tasks do the spawning instead.
            return;
        }
        ((com.server_optimize.mixin.accessor.ChunkMapAccessor) (Object) chunkMap)
            .serverOptimize$callCollectSpawningChunks(list);
    }

    @Redirect(
        method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V")
    )
    private void serverOptimize$skipTickingWalk(ChunkMap chunkMap, java.util.function.Consumer consumer) {
        if (serverOptimize$regionConnected()) {
            // The server thread does not walk the block-ticking chunks at all: the region pass
            // builds its units from the player simulation squares in RandomTickPass.flush, so
            // the O(ticking chunks) iteration and dispatch never run on the server thread.
            return;
        }
        ((com.server_optimize.mixin.accessor.ChunkMapAccessor) (Object) chunkMap)
            .serverOptimize$callForEachBlockTickingChunk(consumer);
    }

    private static boolean serverOptimize$regionConnected() {
        ModConfig cfg = ModConfig.INSTANCE;
        return cfg != null
            && cfg.thread.regionbased.enableRegionBasedMultithreadTicking
            && !cfg.thread.randomTickParallel;
    }
}