package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Non-blocking natural spawning (tweak.skipSpawningOnUnreadyChunks).
 *
 * NaturalSpawner.spawnCategoryForChunk picks random candidate positions and
 * evaluates them via mobsAt/getBiome, which can force-load the candidate
 * chunk (or a neighbour, for edge candidates) with the blocking
 * ServerChunkCache.getChunkBlocking. While a player flies fast, chunks in
 * front of the player are still generating, so the server thread blocks
 * waiting for them - a direct tick-drop amplifier (~9% of server thread time
 * plus the blocking stalls in the round-5 JFR).
 *
 * When the chunk or any of its four horizontal neighbours is not fully
 * loaded, the whole chunk's natural spawning for this tick is skipped.
 * Spawning is best-effort anyway; fully loaded areas (spawn farms) are
 * completely unaffected because all neighbours are full there.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    @Inject(method = "spawnCategoryForChunk", at = @At("HEAD"), cancellable = true)
    private static void serverOptimize$skipIfNotReady(
        MobCategory category, ServerLevel level, LevelChunk chunk,
        NaturalSpawner.SpawnPredicate predicate, NaturalSpawner.AfterSpawnCallback callback,
        CallbackInfo ci
    ) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.skipSpawningOnUnreadyChunks) {
            return;
        }
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        if (!serverOptimize$ready(level, x, z)
            || !serverOptimize$ready(level, x + 1, z)
            || !serverOptimize$ready(level, x - 1, z)
            || !serverOptimize$ready(level, x, z + 1)
            || !serverOptimize$ready(level, x, z - 1)) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean serverOptimize$ready(ServerLevel level, int x, int z) {
        // Pure map lookup - never blocks. The 4-arg getChunk(x, z, status,
        // false) resolves to a cache-miss -> getChunkBlocking path on
        // 1.21.11, which would defeat the whole purpose.
        net.minecraft.server.level.ChunkMap chunkMap = level.getChunkSource().chunkMap;
        net.minecraft.server.level.ChunkHolder holder =
            chunkMap.getUpdatingChunkIfPresent(net.minecraft.world.level.ChunkPos.asLong(x, z));
        if (holder == null) {
            return false;
        }
        // getNow()-based, non-blocking; non-null only when fully ticking.
        return holder.getTickingChunk() != null;
    }
}
