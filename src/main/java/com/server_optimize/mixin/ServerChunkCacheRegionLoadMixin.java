package com.server_optimize.mixin;

import com.server_optimize.thread.RegionChunkLoad;
import com.server_optimize.thread.RegionScene;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Worker-side chunk access for the region apply ([thread.multithread.regionbased]).
 *
 * <p>Vanilla {@link ServerChunkCache#getChunk(int, int, ChunkStatus, boolean)} served to a
 * non-server thread does {@code supplyAsync(supplier, mainThreadProcessor).join()}: the load
 * is drained by the server thread. During a region apply the server thread is parked at the
 * join barrier, so that join would never complete. While a region-apply worker is active,
 * this injection serves the read from {@link RegionChunkLoad} instead: the region's
 * pre-resolved table, an already-loaded chunk, or an empty chunk - never a load, because
 * completing one on a worker runs the FULL-status transition off the server thread, which
 * lithium's ChunkStatusTracker rejects with an IllegalStateException (observed crash).
 *
 * <p>A HEAD injection rather than a wrap: this method is on the hottest path the workers
 * have - every block read a random tick makes goes through it - and MixinExtras' wrap bridge
 * builds an Object[] of boxed arguments per call (it showed up as ~6% of the server thread's
 * samples). The injection only replaces the body when a region context is active; every
 * other caller falls through to vanilla untouched.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheRegionLoadMixin {

    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void serverOptimize$workerLoadChunk(int x, int z, ChunkStatus status, boolean require,
                                                CallbackInfoReturnable<ChunkAccess> cir) {
        RegionScene.Context context = RegionScene.context();
        if (context != null) {
            cir.setReturnValue(RegionChunkLoad.load(context, (ServerChunkCache) (Object) this,
                x, z, status, require));
        }
    }

    /**
     * {@code ChunkSource.getChunkNow} is what {@code PathNavigationRegion} uses to
     * pre-fetch the chunk rectangle for the pathfinder, and it is a SEPARATE method from
     * {@code getChunk}: on a non-server thread vanilla returns null for it (the
     * main-thread-only branch), so a region worker's pathfinding would read a rectangle of
     * null chunks and fall back to the plains/air filler - which is why the deferred
     * entities' paths all degenerated to single-node paths. With a region context active,
     * serve the already-loaded chunk (or null, matching the vanilla semantics) from the
     * visible holders, never loading and never waiting.
     */
    @Inject(
        method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void serverOptimize$workerGetChunkNow(int x, int z,
                                                  CallbackInfoReturnable<net.minecraft.world.level.chunk.LevelChunk> cir) {
        RegionScene.Context context = RegionScene.context();
        if (context != null) {
            cir.setReturnValue(RegionChunkLoad.loadedOrNull((ServerChunkCache) (Object) this, x, z));
        }
    }
}
