package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.ChunkMapAccessor;
import com.server_optimize.thread.ChunkSavePass;
import com.server_optimize.util.ChunkIOCounters;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Off-thread chunk save serialization (chunk.asyncChunkSaving).
 *
 * ChunkMap.save() calls SerializableChunkData.copyOf(level, chunk) on the
 * server thread, deep-copying every section (palette containers + light
 * data). While a player flies, the chunks left behind are unloaded and
 * saved every tick, which was the single largest server-thread cost in the
 * chunk-loading JFR (SerializableChunkData.copyOf chain, ~120 samples).
 *
 * Two-step redirect:
 * 1. The copyOf() call is replaced with a dummy instance (so the vanilla
 *    null-check and supplier plumbing keep working) when the chunk is no
 *    longer tracked by the ChunkMap (i.e. it is being unloaded and its data
 *    is immutable - the safe case for off-thread reading).
 * 2. The following CompletableFuture.supplyAsync() is redirected to run the
 *    real copyOf() on a small daemon pool instead of returning the dummy.
 *
 * Chunks that are still tracked (periodic auto-save of live chunks) keep the
 * vanilla synchronous path, because the server thread may be writing to them
 * while the copy runs.
 */
@Mixin(ChunkMap.class)
public abstract class AsyncChunkSaveMixin {

    @Unique
    private static final ExecutorService SAVE_EXECUTOR = new ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64),
        r -> {
            Thread t = new Thread(() -> {
                com.server_optimize.thread.AffinityManager.pinPoolThread("chunk-save",
                    (int) (Thread.currentThread().threadId() % 2));
                r.run();
            }, "server-optimize-chunk-save");
            t.setDaemon(true);
            return t;
        },
        // Bounded queue + caller-runs backpressure: when the save pool is
        // full the save falls back to the submitting (server) thread instead
        // of piling up unbounded tasks, each holding a full chunk copy. This
        // caps the queue memory; the unload rate simply throttles itself.
        new ThreadPoolExecutor.CallerRunsPolicy());

    @Unique
    private static final sun.misc.Unsafe UNSAFE = unsafe();

    @Unique
    private static final SerializableChunkData DUMMY;

    static {
        try {
            DUMMY = (SerializableChunkData) UNSAFE.allocateInstance(SerializableChunkData.class);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Inject(method = "save", at = @At("HEAD"))
    private void serverOptimize$onSave(ChunkAccess chunk,
        CallbackInfoReturnable<java.util.concurrent.CompletableFuture<net.minecraft.nbt.CompoundTag>> cir) {
        // Chunk save logging ([log] group). Always runs so the periodic
        // summary has the count; only the log output is gated.
        if (ModConfig.INSTANCE == null) return;
        try {
            ServerLevel level = ((ChunkMapAccessor) (Object) this).serverOptimize$getLevel();
            String dim = level != null ? level.dimension().toString() : "unknown";
            // light 标志+类型随日志输出：定位写回 chunk 的真实来源（磁盘 Imposter/Proto/LevelChunk）
            ChunkIOCounters.onSave(dim, chunk.getPos().x, chunk.getPos().z, chunk.isLightCorrect(), chunk.getClass().getSimpleName());
        } catch (Exception ignored) {
        }
    }

    @Unique
    private static boolean serverOptimize$shouldAsync(ChunkMap self, ChunkAccess chunk) {
        if (ModConfig.INSTANCE == null || !ModConfig.INSTANCE.chunk.asyncChunkSaving) {
            return false;
        }
        long pos = chunk.getPos().toLong();
        ChunkMapAccessor acc = (ChunkMapAccessor) (Object) self;
        // During a save-all pass (auto-save / /save-all) the server thread is the only
        // thing touching chunks and it is busy saving, not mutating: every dirty chunk is
        // quiescent, so its snapshot copy may run on the save pool too. The pass wrapper
        // (ChunkMapSaveFlushMixin) joins all of those copies before the next tick starts.
        if (ChunkSavePass.inPass()) {
            return true;
        }
        // Still tracked -> live chunk, periodic save, must stay synchronous.
        // The vanilla visible map is a per-tick snapshot of the updating map (the
        // noMapClone alias was removed because the loading screen reads it from the
        // render thread, which corrupted the aliased map), so a chunk counts as
        // untracked only when it is in neither of the two.
        java.util.Map<Long, net.minecraft.server.level.ChunkHolder> updating =
            acc.serverOptimize$getUpdatingChunkMap();
        java.util.Map<Long, net.minecraft.server.level.ChunkHolder> visible =
            acc.serverOptimize$getVisibleChunkMap();
        if (updating == visible) {
            return !updating.containsKey(pos);
        }
        return !updating.containsKey(pos) && !visible.containsKey(pos);
    }

    @Redirect(
        method = "save",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/class_2852;method_61793(Lnet/minecraft/class_3218;Lnet/minecraft/class_2791;)Lnet/minecraft/class_2852;",
            remap = false
        )
    )
    private SerializableChunkData serverOptimize$skipCopy(
        ServerLevel level, ChunkAccess copyChunk, ChunkAccess saveChunk
    ) {
        // The real (unloading) case: vanilla would deep-copy all sections
        // here on the server thread. We hand the copy to the save pool via
        // the supplyAsync redirect and only keep a dummy for the null-check.
        if (serverOptimize$shouldAsync((ChunkMap) (Object) this, saveChunk)) {
            return DUMMY;
        }
        return SerializableChunkData.copyOf(level, copyChunk);
    }

    @Redirect(
        method = "save",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            remap = false
        )
    )
    private CompletableFuture<?> serverOptimize$asyncCopy(
        Supplier<?> original, Executor background, ChunkAccess chunk
    ) {
        if (!serverOptimize$shouldAsync((ChunkMap) (Object) this, chunk)) {
            return CompletableFuture.supplyAsync(original, background);
        }
        ServerLevel level = ((ChunkMapAccessor) (Object) this).serverOptimize$getLevel();
        // Vanilla: supplyAsync(() -> data.write()) - the deep copy AND the
        // NBT serialization both happen off the server thread. We keep the
        // same shape but run the copy on the save pool; the future must
        // complete with the serialized CompoundTag, because the vanilla
        // follow-up does () -> future.join() and feeds the result straight
        // to the IOWorker (which casts to CompoundTag).
        CompletableFuture<?> future = CompletableFuture.supplyAsync(
            () -> SerializableChunkData.copyOf(level, chunk).write(), SAVE_EXECUTOR);
        if (ChunkSavePass.inPass()) {
            // The save-all wrapper waits for this snapshot at the end of the pass, so the
            // next tick never mutates a chunk whose copy is still being taken.
            ChunkSavePass.track(future);
        }
        return future;
    }
}
