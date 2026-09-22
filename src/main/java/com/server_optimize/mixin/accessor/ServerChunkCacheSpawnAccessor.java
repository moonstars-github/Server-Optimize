package com.server_optimize.mixin.accessor;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Surface for the region spawn offload ([thread.multithread.regionbased]): the per-tick
 * mob-cap state and the enemy-spawning flag of the chunk cache, read by the region apply on
 * the server thread to hand a consistent snapshot of the spawn settings to the region tasks.
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheSpawnAccessor {

    @Accessor("lastSpawnState")
    NaturalSpawner.SpawnState serverOptimize$getLastSpawnState();

    @Accessor("spawnEnemies")
    boolean serverOptimize$isSpawnEnemies();
}