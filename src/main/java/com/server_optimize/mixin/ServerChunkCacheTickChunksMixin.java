package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.thread.RandomTickPass;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Closes the random-tick pass once every ticking chunk has been visited.
 *
 * <p>{@code tickChunks(ProfilerFiller, long)} is the method that walks the block-ticking
 * chunks and calls {@code ServerLevel.tickChunk} for each of them, on the server thread.
 * The section-parallel random ticking queues its sampled sections instead of waiting for
 * them per chunk (see {@link RandomTickPass}), so this is where the queue is joined and the
 * collected ticks are applied - after the last chunk, which is what lets the phases overlap
 * within the pass while the applies still happen in chunk order.
 *
 * <p>In region mode the walk itself is skipped (see
 * {@link ServerChunkCacheRegionSpawnMixin}), so the level is passed along and the flush
 * builds its units from the player simulation squares instead.
 *
 * <p>Wrapping rather than injecting at RETURN also covers an early return of the pass, and
 * {@link RandomTickPass#flush()} is a no-op when no pass is open, so a tick that ticked no
 * chunk at all costs one null check.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheTickChunksMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @WrapMethod(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V")
    private void serverOptimize$flushRandomTickPass(ProfilerFiller profiler, long gameTime,
                                                    Operation<Void> original) {
        original.call(profiler, gameTime);
        RandomTickPass.flush(this.level);
    }
}