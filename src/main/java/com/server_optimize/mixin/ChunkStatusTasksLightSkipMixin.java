package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Trusted-light skip (chunk.skipLoadedLight).
 *
 * For chunks loaded from disk with complete stored light (persisted status
 * at or after LIGHT and isLightCorrect set, i.e. pre-generated worlds), the
 * vanilla INITIALIZE_LIGHT / LIGHT status tasks still run:
 *  - initializeLight() unconditionally calls ChunkAccess.initializeLightSources()
 *    (re-scanning every non-air block for light sources) and re-registers
 *    all sections with the light engine;
 *  - light() calls lightChunk(), which unconditionally sets lightCorrect to
 *    false and only then skips source propagation.
 *
 * Both tasks are skipped entirely for such chunks: their stored light data
 * was already queued into the engine by SerializableChunkData.read(), and
 * FULL chunks never participate in propagation (vanilla already skips
 * propagateLightSources for isLighted chunks). Chunks whose light is not
 * complete (fresh generation, lightCorrect=false) keep the vanilla path.
 *
 * All state is per-chunk and the skip is decided on the same flag vanilla
 * itself trusts (isLightCorrect), so behavior is identical to vanilla for
 * every chunk that does not carry complete stored light.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksLightSkipMixin {

    @Unique
    private static boolean serverOptimize$trustedLight(ChunkAccess chunk) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.skipLoadedLight) {
            return false;
        }
        return chunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT) && chunk.isLightCorrect();
    }

    /**
     * Trusted-full skip: a chunk loaded from disk whose persisted status is
     * already FULL has its structure-references and biome data persisted. The
     * vanilla STRUCTURE_REFERENCES / BIOMES steps would re-scan the chunk
     * (structure reference re-creation for every referenceable position /
     * biome-holder rewrite) - both are no-ops for a fully generated chunk and
     * only add two more task hops in the load chain.
     *
     * Structure references & biomes are only skipped when the persisted status
     * is FULL. Anything that needs (re)generation keeps the vanilla path.
     */
    @Unique
    private static boolean serverOptimize$trustedFull(ChunkAccess chunk) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.skipCompletedChunks) {
            return false;
        }
        ChunkStatus persisted = chunk.getPersistedStatus();
        return persisted != null && persisted.isOrAfter(ChunkStatus.FULL)
            && chunk.isLightCorrect();
    }

    @SuppressWarnings("unused")
    @Inject(method = "generateStructureReferences", at = @At("HEAD"), cancellable = true)
    private static void serverOptimize$skipStructureReferences(WorldGenContext ctx, ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (serverOptimize$trustedFull(chunk)) {
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
        }
    }

    @SuppressWarnings("unused")
    @Inject(method = "generateBiomes", at = @At("HEAD"), cancellable = true)
    private static void serverOptimize$skipBiomes(WorldGenContext ctx, ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (serverOptimize$trustedFull(chunk)) {
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
        }
    }

    @Inject(method = "initializeLight", at = @At("HEAD"), cancellable = true)
    private static void serverOptimize$skipInitializeLight(WorldGenContext ctx, ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (serverOptimize$trustedLight(chunk)) {
            // Keep the engine binding AND the section registration (the light
            // engine needs to know the section states for neighbor
            // propagation); skip only the light-source rescan. The engine's
            // own initializeLight(chunk, true) is the isLighted path: it
            // registers but never propagates.
            ((net.minecraft.world.level.chunk.ProtoChunk) chunk).setLightEngine(ctx.lightEngine());
            cir.setReturnValue(ctx.lightEngine().initializeLight(chunk, true));
        }
    }

    @Inject(method = "light", at = @At("HEAD"), cancellable = true)
    private static void serverOptimize$skipLight(WorldGenContext ctx, ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (serverOptimize$trustedLight(chunk)) {
            // Skip lightChunk() entirely: no lightCorrect(false), no task
            // queuing - the stored light stays authoritative.
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
        }
    }

    /**
     * Completed-chunk skip (chunk.skipCompletedChunks): the vanilla FULL
     * status task unconditionally submits a new task
     * (CompletableFuture.supplyAsync on the chunk executor - one ForkJoinTask
     * + lambda per chunk) which spins up a WorldGenRegion even for chunks
     * already persisted as FULL. For fully-loaded disk chunks the worldgen
     * pass is a no-op; skipping the submission removes that task allocation
     * and scheduling hop from the load pipeline. Only chunks whose persisted
     * status is FULL and whose stored light is complete are skipped - anything
     * else keeps the vanilla path.
     */
    @Inject(method = "full", at = @At("HEAD"), cancellable = true)
    private static void serverOptimize$skipFull(WorldGenContext ctx, ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.skipCompletedChunks) {
            return;
        }
        ChunkStatus persisted = chunk.getPersistedStatus();
        if (persisted != null && chunk instanceof LevelChunk
            && persisted.isOrAfter(ChunkStatus.FULL)
            && chunk.isLightCorrect()) {
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
        }
    }
}
