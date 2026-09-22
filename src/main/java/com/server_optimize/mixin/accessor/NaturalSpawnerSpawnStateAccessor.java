package com.server_optimize.mixin.accessor;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Construction and read access to the per-tick spawn state ([thread.multithread.regionbased]):
 * the package-private constructor takes the census map, the spawn-potential table and the
 * local-cap calculator, which the region apply uses to build one independent SpawnState per
 * region so the spawn passes share no mutable cap state.
 */
@Mixin(NaturalSpawner.SpawnState.class)
public interface NaturalSpawnerSpawnStateAccessor {

    @Invoker("<init>")
    static NaturalSpawner.SpawnState serverOptimize$newSpawnState(int spawnableChunkCount,
        Object2IntOpenHashMap<MobCategory> mobCategoryCounts,
        PotentialCalculator spawnPotential, LocalMobCapCalculator localMobCapCalculator) {
        throw new AssertionError();
    }

    @Accessor("spawnPotential")
    PotentialCalculator serverOptimize$getSpawnPotential();
}