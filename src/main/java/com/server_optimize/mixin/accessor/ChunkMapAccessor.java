package com.server_optimize.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Accessor("updatingChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> serverOptimize$getUpdatingChunkMap();

    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> serverOptimize$getVisibleChunkMap();

    @Accessor("level")
    ServerLevel serverOptimize$getLevel();

    /** The chunk pipeline's single event loop: the ServerChunkCache hands its own
     *  mainThreadProcessor to the ChunkMap, which stores it here. Pumping it drives
     *  distance-manager updates, chunk loads, generation and light scheduling. */
    @Accessor("mainThreadExecutor")
    net.minecraft.util.thread.BlockableEventLoop<Runnable> serverOptimize$getMainThreadExecutor();

    /** Package-private ChunkMap.collectSpawningChunks (the per-tick spawning-chunk list
     *  build), reachable for the region spawn offload that skips it on the server thread. */
    @Invoker("collectSpawningChunks")
    void serverOptimize$callCollectSpawningChunks(
        java.util.List<net.minecraft.world.level.chunk.LevelChunk> target);

    /** Package-private ChunkMap.forEachBlockTickingChunk (the per-tick block-ticking list
     *  walk), reachable for the region mode that skips it on the server thread. */
    @Invoker("forEachBlockTickingChunk")
    void serverOptimize$callForEachBlockTickingChunk(java.util.function.Consumer consumer);

    /** entityId -> ChunkMap$TrackedEntity (private field). Used by the
     *  entity stacker to check whether an entity currently has a tracker. */
    @Accessor("entityMap")
    it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> serverOptimize$getEntityMap();

    /** Protected ChunkMap.addEntity(Entity) - used by entity stacking to
     *  re-track a representative / expanded member. */
    @Invoker("addEntity")
    void serverOptimize$callAddEntity(Entity entity);

    /** Protected ChunkMap.removeEntity(Entity) - used by entity stacking to
     *  untrack a hidden member (sends the vanilla remove packet). */
    @Invoker("removeEntity")
    void serverOptimize$callRemoveEntity(Entity entity);
}
