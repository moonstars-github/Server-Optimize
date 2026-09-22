package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.nbt.NbtIo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * NBT compressed-stream buffer reuse (chunk.writeStreamReuse).
 *
 * NbtIo.createCompressorStream() / createDecompressorStream() wrap every
 * chunk write/read in a fresh BufferedOutputStream / BufferedInputStream
 * (each an 8 KiB allocation) around a fresh GZIP stream; chunk save/load
 * storms allocate one pair per chunk. The 8 KiB buffers are per-thread
 * reusable: the IO workers each own a fixed thread and fully consume the
 * stream, so a ThreadLocal reused buffer never aliases two in-flight
 * operations. The GZIP (compressor) stream is still fresh every time - only
 * the outer buffered object is reused, with its underlying stream re-pointed
 * at the fresh GZIP stream via Unsafe.
 *
 * The out / in fields live on different parents (FilterOutputStream vs
 * FilterInputStream), so each gets its own resolved offset.
 */
@Mixin(NbtIo.class)
public abstract class NbtIoStreamReuseMixin {

    @Unique
    private static final sun.misc.Unsafe UNSAFE = unsafe();

    @Unique
    private static final long OUT_OFFSET;

    @Unique
    private static final long IN_OFFSET;

    @Unique
    private static final long POS_OFFSET;

    @Unique
    private static final long COUNT_OFFSET;

    @Unique
    private static final long MARKPOS_OFFSET;

    @Unique
    private static final long BUF_OFFSET;   // BufferedInputStream.buf (byte[])

    @Unique
    private static final long CLOSED_OFFSET; // FilterOutputStream.closed

    static {
        try {
            // No setAccessible: java.base does not open java.io to the mod
            // (unnamed) module on JDK 24+, but Unsafe.objectFieldOffset works
            // without it.
            java.lang.reflect.Field of = FilterOutputStream.class.getDeclaredField("out");
            OUT_OFFSET = UNSAFE.objectFieldOffset(of);
            java.lang.reflect.Field inf = FilterInputStream.class.getDeclaredField("in");
            IN_OFFSET = UNSAFE.objectFieldOffset(inf);

            // BufferedInputStream reads keep stale data in the buffer;
            // resolve the position fields so we can reset them on reuse.
            Class<?> bis = BufferedInputStream.class;
            POS_OFFSET = UNSAFE.objectFieldOffset(bis.getDeclaredField("pos"));
            COUNT_OFFSET = UNSAFE.objectFieldOffset(bis.getDeclaredField("count"));
            MARKPOS_OFFSET = UNSAFE.objectFieldOffset(bis.getDeclaredField("markpos"));
            BUF_OFFSET = UNSAFE.objectFieldOffset(bis.getDeclaredField("buf"));

            // FilterOutputStream.close() sets closed = true; the reused
            // BufferedOutputStream must be re-opened or the next close() is
            // a no-op and the fresh GZIP footer is never written (truncated
            // level.dat / player data). JDK 25 stores it on FilterOutputStream.
            java.lang.reflect.Field cf = FilterOutputStream.class.getDeclaredField("closed");
            CLOSED_OFFSET = UNSAFE.objectFieldOffset(cf);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private static sun.misc.Unsafe unsafe() {
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private static final ThreadLocal<BufferedOutputStream> WRITE_BUF =
        ThreadLocal.withInitial(() -> new BufferedOutputStream(new java.io.ByteArrayOutputStream(), 8192));

    @Unique
    private static final ThreadLocal<byte[]> READ_BUF_ARRAY = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<BufferedInputStream> READ_BUF =
        ThreadLocal.withInitial(() -> {
            BufferedInputStream b = new BufferedInputStream(new java.io.ByteArrayInputStream(new byte[0]), 8192);
            // Save the internal buffer array so we can restore it after close()
            // clears it (BufferedInputStream.close() sets buf = null).
            READ_BUF_ARRAY.set((byte[]) UNSAFE.getObject(b, BUF_OFFSET));
            return b;
        });

    @Unique
    private static boolean serverOptimize$enabled() {
        return ModConfig.INSTANCE != null && ModConfig.INSTANCE.chunk.writeStreamReuse;
    }

    @Inject(
        method = "createCompressorStream",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void serverOptimize$reuseCompressorStream(OutputStream out,
        CallbackInfoReturnable<DataOutputStream> cir) throws java.io.IOException {
        if (!serverOptimize$enabled()) {
            return;
        }
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        BufferedOutputStream buf = WRITE_BUF.get();
        try {
            buf.flush();
        } catch (java.io.IOException ignored) {
        }
        // Re-open: FilterOutputStream.close() left closed = true, and the
        // next close() would be a no-op - the fresh GZIP stream would never
        // be closed and its footer (CRC + ISIZE) would be missing, producing
        // a truncated file (corrupted level.dat / player data).
        UNSAFE.putBoolean(buf, CLOSED_OFFSET, false);
        UNSAFE.putObject(buf, OUT_OFFSET, gzip);
        cir.setReturnValue(new DataOutputStream(buf));
    }

    @Inject(
        method = "createDecompressorStream",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void serverOptimize$reuseDecompressorStream(InputStream in,
        CallbackInfoReturnable<DataInputStream> cir) throws java.io.IOException {
        if (!serverOptimize$enabled()) {
            return;
        }
        GZIPInputStream gzip = new GZIPInputStream(in);
        BufferedInputStream buf = READ_BUF.get();
        // Restore the internal buffer array: BufferedInputStream.close()
        // sets buf = null, and the next fill() would throw "Stream closed".
        UNSAFE.putObject(buf, BUF_OFFSET, READ_BUF_ARRAY.get());
        // Reset internal buffer state: stale data from a previous read would
        // be served before the fresh GZIP stream, corrupting the NBT parse.
        UNSAFE.putInt(buf, POS_OFFSET, 0);
        UNSAFE.putInt(buf, COUNT_OFFSET, 0);
        UNSAFE.putInt(buf, MARKPOS_OFFSET, -1);
        UNSAFE.putObject(buf, IN_OFFSET, gzip);
        cir.setReturnValue(new DataInputStream(buf));
    }
}
