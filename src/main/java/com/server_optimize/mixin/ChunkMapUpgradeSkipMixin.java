package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.ChunkMapAccessor;
import com.server_optimize.util.ChunkIOCounters;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DataFixer upgrade skip (chunk.skipDataFixerUpgrade) + chunk load logging
 * ([log] group).
 *
 * Every chunk read goes through ChunkMap.upgradeChunkTag, which builds a
 * DataFixer context tag and runs the full DataFixer pass even when the chunk
 * is already at the current DataVersion (the normal case for a pre-generated
 * 1.21.11 world). The no-op pass still walks the whole NBT tree - a
 * measurable read-path cost on the IOWorker chain.
 *
 * When the chunk's DataVersion already equals the current version, the tag is
 * returned untouched. Any older version keeps the vanilla upgrade path.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapUpgradeSkipMixin {

    @Inject(method = "upgradeChunkTag", at = @At("HEAD"))
    private void serverOptimize$onUpgradeChunkTag(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        // Chunk load logging (always runs, regardless of skipDataFixerUpgrade).
        if (ModConfig.INSTANCE == null) return;
        if (!ModConfig.INSTANCE.log.chunkSummary && !ModConfig.INSTANCE.log.chunkIO) return;
        try {
            int x = tag.getIntOr("xPos", 0);
            int z = tag.getIntOr("zPos", 0);
            String dim = "unknown";
            if (this instanceof ChunkMapAccessor acc) {
                ServerLevel level = acc.serverOptimize$getLevel();
                if (level != null) {
                    dim = level.dimension().toString();
                }
            }
            ChunkIOCounters.onLoad(dim, x, z, 0);
        } catch (Exception ignored) {
        }
    }

    @Inject(method = "upgradeChunkTag", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$skipUpgrade(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.skipDataFixerUpgrade) {
            return;
        }
        try {
            int dataVersion = tag.getIntOr("DataVersion", -1);
            if (dataVersion == net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()) {
                cir.setReturnValue(tag);
            }
        } catch (Exception ignored) {
        }
    }
}
