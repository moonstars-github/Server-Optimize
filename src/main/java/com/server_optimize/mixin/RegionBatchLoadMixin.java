package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.IOWorkerAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parallel chunk storage reads (chunk.regionBatchLoader).
 * <p>
 * Vanilla {@link IOWorker#loadAsync} submits the {@code storage.read} task
 * to a {@code PriorityConsecutiveExecutor} — a single-threaded queue. One
 * server dimension therefore reads (and NBT-parses) chunks serially, which
 * caps throughput at ~370/s on large/complex chunks (15.8.3 mountain run).
 * <p>
 * This replaces {@code loadAsync} with the same {@code storage.read} run on
 * a dedicated parallel pool. Safety:
 * <ul>
 *   <li>{@code RegionFileStorage.getRegionFile} (the non-thread-safe region
 *       cache) is serialized by RegionFileStorageSyncMixin.</li>
 *   <li>{@code RegionFile.getChunkDataInputStream} / {@code write} are
 *       {@code synchronized} in vanilla — same-region access is serialized,
 *       different regions run in parallel.</li>
 *   <li>Writes still go through the vanilla IOWorker (store / write are not
 *       touched), so pending-write ordering and merge semantics are kept.</li>
 * </ul>
 * The future is completed with the same {@code Optional<CompoundTag>} shape
 * vanilla produces; chunk parsing downstream is unchanged.
 */
@Mixin(IOWorker.class)
public abstract class RegionBatchLoadMixin {

    @Unique
    private static final int READER_THREADS = 8;

    @Unique
    private static final AtomicInteger READER_ID = new AtomicInteger();

    /**
     * Lock-free reader pool (see {@link com.server_optimize.util.ChunkReadPool}):
     * the previous {@code ThreadPoolExecutor} handed every chunk over through a
     * blocking-queue Condition, whose park nodes alone were 21.2 GB / 8.9% of all
     * allocation in the 15.8.10 profile.
     */
    @Unique
    private static final com.server_optimize.util.ChunkReadPool READ_POOL =
        new com.server_optimize.util.ChunkReadPool(
            com.server_optimize.thread.AffinityManager.clampPoolSize(READER_THREADS),
            "server-optimize-io-reader-");

    @Inject(method = "loadAsync", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$parallelLoadAsync(ChunkPos pos,
        CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.regionBatchLoader) {
            return;
        }
        RegionFileStorage storage = ((IOWorkerAccessor) (Object) this).serverOptimize$getStorage();
        CompletableFuture<Optional<CompoundTag>> future = new CompletableFuture<>();
        READ_POOL.execute(() -> {
            try {
                future.complete(Optional.ofNullable(storage.read(pos)));
            } catch (IOException e) {
                future.completeExceptionally(e);
            }
        });
        cir.setReturnValue(future);
    }
}