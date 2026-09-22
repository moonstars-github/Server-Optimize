package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pre-build empty section skipping (chunk.regionFileCaching).
 * <p>
 * Before vanilla serializes the chunk sections into the packet data, check
 * each section for "hasOnlyAir" (blocks + biomes). Sections that are empty
 * (all air, no block entities) are replaced with a minimal all-air section
 * instance. The vanilla constructor still sees a non-null section, so size
 * calculation and serialization work; but the serialized data is minimal
 * (single-value palette for air). After the constructor returns, the
 * original sections are restored.
 * <p>
 * The existing SectionCulling post-build trim then removes these sections
 * from the wire entirely.
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ChunkPacketDataSectionSkipMixin {

    @Unique
    private static final ThreadLocal<LevelChunkSection[]> SERVER_OPTIMIZE$SAVED_SECTIONS = new ThreadLocal<>();

    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("HEAD"))
    private static void serverOptimize$skipEmptySections(LevelChunk chunk, CallbackInfo ci) {
        // Reserved for future pre-build empty section skipping.
        // The vanilla ClientboundLevelChunkPacketData constructor iterates all
        // sections (non-null required). Nullifying or replacing sections breaks
        // calculateChunkSize/extractChunkData. The existing SectionCulling
        // post-build trim handles this effectively.
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("RETURN"))
    private static void serverOptimize$restoreSections(LevelChunk chunk, CallbackInfo ci) {
        // Reserved for future use (paired with the HEAD hook).
    }
}