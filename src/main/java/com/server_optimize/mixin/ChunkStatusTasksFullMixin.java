package com.server_optimize.mixin;

import com.server_optimize.ServerOptimize;
import com.server_optimize.config.ModConfig;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Belt-and-suspenders clean-state recovery for disk-loaded chunks
 * (chunk.skipCleanChunkSaves).
 * <p>
 * {@link ChunkStatusTasks#full} only schedules work on the main-thread
 * executor; the actual ProtoChunk→LevelChunk promotion (the
 * {@code new LevelChunk(ServerLevel, ProtoChunk, PostLoadProcessor)}
 * constructor) plus the post-load sequence runs in the private helper
 * {@code method_60553}. Several of those steps call setLightCorrect() /
 * markUnsaved() and can leave the chunk dirty even though its data is
 * byte-for-byte what was read from disk.
 * <p>
 * Two disk-loaded shapes arrive here:
 * <ul>
 *   <li>{@link ImposterProtoChunk} (a chunk saved as full LevelChunk) — its
 *       own {@code isLightCorrect()} stays false (setLightCorrect delegates
 *       to the wrapped LevelChunk), so it must be recognized by type.</li>
 *   <li>a plain disk {@code ProtoChunk} (chunk saved partially) — recognized
 *       by persistedStatus ≥ LIGHT; a freshly generated ProtoChunk has
 *       persistedStatus EMPTY, so it is never touched.</li>
 * </ul>
 * Either way the freshly built LevelChunk is cleared with tryMarkSaved().
 * Real player edits happen later and re-mark the chunk, so nothing is lost.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksFullMixin {

    @Inject(method = "method_60553", at = @At("TAIL"))
    private static void serverOptimize$clearLoadDirty(
        ChunkAccess chunk,
        WorldGenContext context,
        GenerationChunkHolder holder,
        CallbackInfoReturnable<ChunkAccess> cir
    ) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.skipCleanChunkSaves) {
            return;
        }
        boolean diskLoaded = chunk instanceof ImposterProtoChunk
            || (chunk.isLightCorrect() && chunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT));
        ChunkAccess result = cir.getReturnValue();
        boolean cleared = false;
        if (diskLoaded && result != null) {
            cleared = result.tryMarkSaved();
        }
        if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.log.chunkIO) {
            ServerOptimize.LOGGER.info("[diag] m60553: paramClass={} paramLight={} paramStatus={} disk={} resultClass={} cleared={}",
                chunk.getClass().getSimpleName(), chunk.isLightCorrect(), chunk.getPersistedStatus(),
                diskLoaded, result != null ? result.getClass().getSimpleName() : "null", cleared);
        }
    }
}
