package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Periodic fsync after chunk writes (tweak.softSaveFileWrite, region-file
 * part). Every 32 writes to the same RegionFile instance, force()s the file
 * channel so data + metadata are on disk before the next batch. This bounds
 * the data-at-risk window to ~32 chunks without fsync-per-chunk overhead.
 */
@Mixin(RegionFile.class)
public abstract class RegionFileSafeWriteMixin {

    @Shadow
    private FileChannel file;

    @Unique
    private int serverOptimize$writeCount;

    @Inject(method = "write", at = @At("TAIL"))
    private void serverOptimize$onWriteChunk(ChunkPos pos, ByteBuffer data,
        CallbackInfo ci) throws IOException {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.safety.softSaveFileWrite) {
            return;
        }
        // With write-back region caching (chunk.regionFileCaching) the writes
        // land in the memory mirror; fsync is performed once per flush
        // (auto-save/save-all/close) instead of every 32 chunk writes.
        if (cfg.chunk.regionFileCaching) {
            return;
        }
        // Throttled: fsync every 32 writes.
        if (++this.serverOptimize$writeCount >= 32) {
            this.serverOptimize$writeCount = 0;
            this.file.force(true);
        }
    }
}