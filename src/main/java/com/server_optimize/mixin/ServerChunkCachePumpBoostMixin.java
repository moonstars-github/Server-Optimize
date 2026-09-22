package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.ServerChunkCacheRegionAccessor;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.BooleanSupplier;

/**
 * Feeds the worldgen workers harder while a generation/loading backlog exists
 * ([chunk] genUpdatesPerTick).
 *
 * <p>Vanilla runs the chunk-loading pump - the distance-manager updates and the
 * generation-task feeding, {@code DistanceManager.runAllUpdates} plus
 * {@code ChunkMap.runGenerationTasks} - once per tick inside
 * {@code ServerChunkCache.tick}. On the client the tick is render-paced, so with a
 * backlog (Chunky pre-generation, flying into new areas) the workers grind a small
 * queue and then idle until the next tick - Chunky pre-generation shows ~30% CPU
 * while the workers could use several cores. When the config value is above 1, the
 * tick calls the pump again up to that many times, each round doing more of the same
 * work; {@code runDistanceManagerUpdates} reports when nothing is left, so idle ticks
 * cost one boolean check.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCachePumpBoostMixin {

    @WrapMethod(method = "tick(Ljava/util/function/BooleanSupplier;Z)V")
    private void serverOptimize$boostGenerationPump(BooleanSupplier hasTimeLeft, boolean tickChunks,
                                                    Operation<Void> original) {
        original.call(hasTimeLeft, tickChunks);
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || cfg.chunk.genUpdatesPerTick <= 1) {
            return;
        }
        // C2ME-style continuous feeding: keep the pump going until the
        // distance-manager reports nothing left to do, so a backlog is fully
        // drained within the tick and the worldgen workers never idle waiting
        // for the next tick's fixed rounds. The cap is a safety net against an
        // endless backlog stalling the tick forever.
        int cap = 65536;
        while (cap-- > 0
            && ((ServerChunkCacheRegionAccessor) (Object) this)
                .serverOptimize$callRunDistanceManagerUpdates()) {
            // the loop continues while the pump did work
        }
    }
}