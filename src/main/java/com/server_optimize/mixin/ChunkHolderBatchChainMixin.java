package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Batch chunk status chain (chunk.batchChain).
 * <p>
 * Vanilla ramps a chunk through every status step (EMPTY → ... → FULL) even
 * when the chunk is already persisted as FULL on disk. Each step submits an
 * async task to the chunk executor (one ForkJoinTask per step), even when the
 * task body is a no-op for a fully generated chunk. Under a loading storm the
 * per-chunk task hops dominate allocation (ForkJoinPool.execute ≈ 22% of
 * allocation pressure in the loading JFR).
 * <p>
 * This mixin short-circuits {@link ChunkMap#applyStep}: when the chunk's
 * persisted status is already at/after the step's target and its light is
 * complete, the step completes immediately with the existing chunk instead
 * of submitting a fresh async task. This collapses the whole generation chain
 * for disk-loaded FULL chunks into a single already-completed future — no
 * ForkJoinTask, no WorldGenRegion, no executor hop.
 * <p>
 * The EMPTY step (the actual disk read) is left untouched so chunks are still
 * read from storage. Anything that is not a complete disk chunk (missing,
 * needs generation, incomplete light) keeps the vanilla per-step path.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkHolderBatchChainMixin {

    @Unique
    private static boolean serverOptimize$shouldSkipStep(ChunkStep step, GenerationChunkHolder holder) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.batchChain) {
            return false;
        }
        ChunkStatus target = step.targetStatus();
        if (target == ChunkStatus.EMPTY || target == null) {
            return false; // keep the disk-read step intact
        }
        // Never skip the FULL step: it is where the ProtoChunk is converted
        // to a LevelChunk (world spawn / entity ticking setup). skipCompletedChunks
        // handles the already-LevelChunk case separately.
        if (target == ChunkStatus.FULL) {
            return false;
        }
        // The chunk produced by the previous step (parent status). If it is
        // already fully persisted + light-complete, this and all later steps
        // are no-ops for it.
        ChunkStatus parent = target.getParent();
        ChunkAccess chunk = holder.getChunkIfPresentUnchecked(parent);
        if (chunk == null) {
            return false;
        }
        ChunkStatus persisted = chunk.getPersistedStatus();
        return persisted != null && persisted.isOrAfter(target) && chunk.isLightCorrect();
    }

    @SuppressWarnings("unused")
    @Inject(method = "applyStep", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$batchApplyStep(GenerationChunkHolder holder, ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (serverOptimize$shouldSkipStep(step, holder)) {
            ChunkAccess chunk = holder.getChunkIfPresentUnchecked(step.targetStatus().getParent());
            if (chunk != null) {
                cir.setReturnValue(CompletableFuture.completedFuture(chunk));
            }
        }
    }
}