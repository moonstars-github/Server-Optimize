package com.server_optimize.networking;

import com.server_optimize.config.ModConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionEncoder;

/**
 * Replaces the vanilla zlib compression with ZSTD (net.useZstd).
 * <p>
 * Wire format per packet (same framing as vanilla, only the algorithm field
 * differs):
 * <pre>
 *   varInt(originalLen)
 *     0        -&gt; uncompressed: rest of the packet is raw bytes
 *     &gt;0       -&gt; ZSTD frame bytes (the remaining bytes are one ZSTD frame)
 * </pre>
 * The decoder distinguishes ZSTD from vanilla zlib by the ZSTD frame magic.
 * <p>
 * Installed at play time via {@link ZstdCodecSwitcher} after the capability
 * handshake; both decoders are universal (understand both formats), so each
 * side can switch its own encoder independently.
 */
public class ZstdCompressionEncoder extends CompressionEncoder {

    private final int threshold;
    private final int level;

    /** Reused per netty IO thread: input copy + compression output. */
    private static final ThreadLocal<byte[]> INPUT = ThreadLocal.withInitial(() -> new byte[8192]);
    private static final ThreadLocal<byte[]> OUTPUT = ThreadLocal.withInitial(() -> new byte[8192]);

    public ZstdCompressionEncoder(int threshold) {
        super(threshold);
        this.threshold = threshold;
        this.level = ModConfig.INSTANCE != null ? ModConfig.INSTANCE.net.zstdLevel : 4;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) {
        int len = in.readableBytes();
        if (len > 8388608) {
            throw new IllegalArgumentException("Unable to compress packet of size " + len);
        }
        if (len < threshold) {
            // Same as vanilla: varInt(0) marks an uncompressed packet.
            writeVarInt(out, 0);
            out.writeBytes(in);
            return;
        }
        byte[] data = INPUT.get();
        if (data.length < len) {
            data = new byte[len];
            INPUT.set(data);
        }
        in.readBytes(data, 0, len);
        int bound = (int) com.github.luben.zstd.Zstd.compressBound(len);
        byte[] buf = OUTPUT.get();
        if (buf.length < bound) {
            buf = new byte[bound];
            OUTPUT.set(buf);
        }
        // compressByteArray with explicit lengths: the plain
        // compress(dst, src, level) treats src.length as the input size,
        // which is wrong for the reused ThreadLocal buffers.
        int n = (int) com.github.luben.zstd.Zstd.compressByteArray(
            buf, 0, buf.length, data, 0, len, this.level);
        if (n <= 0) {
            throw new IllegalArgumentException("ZSTD compression failed with code " + n);
        }
        writeVarInt(out, len);          // original length
        out.writeBytes(buf, 0, n);      // ZSTD frame (starts with its magic)
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}