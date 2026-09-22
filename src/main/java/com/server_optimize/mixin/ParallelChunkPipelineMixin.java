package com.server_optimize.mixin;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Parallel chunk pipeline (chunk.parallelChunkPipeline) — reserved.
 * <p>
 * The vanilla {@link ChunkMap#promoteChunkMap()} is an O(1) operation
 * (clone updatingChunkMap into visibleChunkMap). Offloading it to the
 * common pool adds synchronization + join overhead for no gain, and a
 * reflection-based field access breaks in the remapped production jar
 * (field names are obfuscated). Left as a safe no-op: the real parallel
 * wins are already delivered by asyncPacketBuild (off-thread packet
 * build) and batchPacketSend (region-grouped batched sends).
 */
@Mixin(ChunkMap.class)
public abstract class ParallelChunkPipelineMixin {
}