package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;

/**
 * RegionFile read-buffer reuse (chunk.ioBufferReuse).
 *
 * RegionFile.getChunkDataInputStream() allocates a fresh
 * ByteBuffer.allocate(sectors * 4096) for every chunk read to hold the
 * compressed chunk header/data. The buffer is consumed entirely inside the
 * method (FileChannel.read + header fields), so a per-thread reused buffer
 * is safe. The method is synchronized per RegionFile instance, and IO
 * workers each have their own ThreadLocal, so there is no aliasing.
 */
@Mixin(net.minecraft.world.level.chunk.storage.RegionFile.class)
public abstract class RegionFileMixin {

    @Unique
    private static final ThreadLocal<ByteBuffer> READ_BUF =
        ThreadLocal.withInitial(() -> ByteBuffer.allocate(8192));

    @Redirect(
        method = "getChunkDataInputStream",
        at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;allocate(I)Ljava/nio/ByteBuffer;", remap = false)
    )
    private static ByteBuffer serverOptimize$reuseReadBuffer(int size) {
        if (ModConfig.INSTANCE == null || !ModConfig.INSTANCE.chunk.ioBufferReuse) {
            return ByteBuffer.allocate(size);
        }
        ByteBuffer buf = READ_BUF.get();
        if (buf.capacity() < size) {
            buf = ByteBuffer.allocate(size);
            READ_BUF.set(buf);
        }
        buf.clear();
        return buf;
    }
}
