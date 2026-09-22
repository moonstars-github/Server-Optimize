package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Merge chunk writes to the same region file (tweak.mergedWrite).
 *
 * Vanilla IOWorker.storePendingChunk() pollFirstEntry()s exactly ONE pending
 * write per executor task, then reschedules via tellStorePending(). Under a
 * save storm that is one executor hop per chunk. This batching version drains
 * up to 64 pending writes per task and groups them by 32x32 region so
 * consecutive writes target the same .mca file (better write locality),
 * cutting executor task count ~64x.
 */
@Mixin(IOWorker.class)
public abstract class StorageIoWorkerMergeMixin {

    private static final int BATCH = 64;

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("server-optimize");

    @Shadow
    private SequencedMap<ChunkPos, Object> pendingWrites;

    @Invoker("tellStorePending")
    protected abstract void serverOptimize$invokeTellStorePending();

    @Unique
    private static Method RUN_STORE;

    @Inject(method = "storePendingChunk", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$mergedStorePending(CallbackInfo ci) throws Exception {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.mergedWrite) {
            return;
        }
        // Resolve runStore BEFORE cancelling: if it cannot be located the
        // vanilla single-write path must run (this pending must be consumed).
        Method runStore;
        try {
            runStore = serverOptimize$runStore();
        } catch (Exception e) {
            return;
        }
        ci.cancel();

        List<Map.Entry<ChunkPos, Object>> batch = new ArrayList<>(BATCH);
        int budget = BATCH;
        while (budget-- > 0 && !this.pendingWrites.isEmpty()) {
            batch.add(this.pendingWrites.pollFirstEntry());
        }
        if (batch.isEmpty()) {
            return;
        }
        // Group by 32x32 region so same-file writes stay adjacent.
        batch.sort(Comparator.comparingLong(e -> regionKey(e.getKey())));
        for (Map.Entry<ChunkPos, Object> e : batch) {
            try {
                runStore.invoke(this, e.getKey(), e.getValue());
            } catch (Throwable t) {
                // Never drop a pending write: put it back so it is retried
                // (and persisted on shutdown). Losing it here means the chunk
                // disappears from the world (void) after the next restart.
                this.pendingWrites.putIfAbsent(e.getKey(), e.getValue());
                LOGGER.warn("server-optimize: merged chunk write failed for {}, requeued", e.getKey(), t);
            }
        }
        // Drain the rest on a fresh executor task (vanilla recursion).
        if (!this.pendingWrites.isEmpty()) {
            this.serverOptimize$invokeTellStorePending();
        }
    }

    /**
     * Resolve IOWorker.runStore(ChunkPos, PendingStore) by exact name, not by
     * signature heuristics. The previous "match by signature shape" approach
     * depended on getDeclaredMethods() order and the ambient method set, so
     * in the singleplayer client (extra mods / different loader) it picked a
     * different private (ChunkPos, Object) method and every
     * {@code runStore.invoke} threw IllegalArgumentException: argument type
     * mismatch — spamming IO-Worker threads until they exploded (IO-Worker-103)
     * and breaking the world (void + long load) on the next entry.
     * <p>
     * Names: yarn {@code runStore} / intermediary {@code method_23701};
     * PendingStore: yarn {@code IOWorker$PendingStore} / intermediary
     * {@code class_4698$class_4699}.
     */
    @Unique
    private static Method serverOptimize$runStore() throws Exception {
        if (RUN_STORE != null) {
            return RUN_STORE;
        }
        Class<?> pendingType;
        try {
            pendingType = Class.forName("net.minecraft.class_4698$class_4699");
        } catch (ClassNotFoundException e) {
            pendingType = Class.forName("net.minecraft.world.level.chunk.storage.IOWorker$PendingStore");
        }
        try {
            RUN_STORE = IOWorker.class.getDeclaredMethod("method_23701", ChunkPos.class, pendingType);
        } catch (NoSuchMethodException e) {
            RUN_STORE = IOWorker.class.getDeclaredMethod("runStore", ChunkPos.class, pendingType);
        }
        RUN_STORE.setAccessible(true);
        return RUN_STORE;
    }

    private static long regionKey(ChunkPos pos) {
        long rx = (long) (pos.x >> 5);
        long rz = (long) (pos.z >> 5);
        return (rx << 32) ^ (rz & 0xFFFFFFFFL);
    }
}