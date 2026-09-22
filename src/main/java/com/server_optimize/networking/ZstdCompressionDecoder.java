package com.server_optimize.networking;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.CompressionDecoder;

import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Universal network decoder: understands BOTH the vanilla zlib format and
 * this mod's ZSTD format on the same connection.
 * <p>
 * Per-packet wire format:
 * <pre>
 *   varInt(originalLen)
 *     0     -&gt; uncompressed: rest of the packet is raw bytes
 *     &gt;0    -&gt; compressed stream, auto-detected by magic:
 *           ZSTD frame magic (28 B5 2F FD) -&gt; ZSTD frame (remaining bytes)
 *           otherwise                     -&gt; vanilla zlib stream (remaining bytes)
 * </pre>
 * The vanilla zlib branch mirrors {@link CompressionDecoder#decode} exactly
 * (threshold enforcement, size caps, single Inflater call), so the server
 * side can install this decoder unconditionally and keep serving vanilla
 * clients while a modded client switches its outbound to ZSTD.
 */
public class ZstdCompressionDecoder extends CompressionDecoder {

    private final Inflater inflater = new Inflater();
    private int threshold;
    private boolean enforce;

    /** Reused per netty IO thread: compressed input + decompressed output. */
    private static final ThreadLocal<byte[]> INPUT = ThreadLocal.withInitial(() -> new byte[8192]);
    private static final ThreadLocal<byte[]> RAW = ThreadLocal.withInitial(() -> new byte[8192]);

    public ZstdCompressionDecoder(int threshold, boolean enforce) {
        super(threshold, enforce);
        this.threshold = threshold;
        this.enforce = enforce;
    }

    @Override
    public void setThreshold(int threshold, boolean enforce) {
        super.setThreshold(threshold, enforce);
        this.threshold = threshold;
        this.enforce = enforce;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int originalLen = readVarInt(in);
        if (originalLen == 0) {
            // Uncompressed packet (length marker = 0): pass raw bytes through.
            out.add(in.readBytes(in.readableBytes()));
            return;
        }
        if (this.enforce && originalLen < this.threshold) {
            throw new DecoderException("Badly compressed packet - size of " + originalLen
                + " is below server threshold of " + this.threshold);
        }
        if (originalLen > MAXIMUM_UNCOMPRESSED_LENGTH) {
            throw new DecoderException("Badly compressed packet - size of " + originalLen
                + " is larger than protocol maximum");
        }
        if (in.readableBytes() >= 4 && isZstdFrame(in)) {
            decodeZstd(in, out, originalLen);
        } else {
            decodeZlib(in, out, originalLen);
        }
    }

    /** ZSTD frame path: the remaining bytes are one ZSTD frame. */
    private void decodeZstd(ByteBuf in, List<Object> out, int originalLen) {
        int read = in.readableBytes();
        if (read > MAXIMUM_COMPRESSED_LENGTH) {
            throw new DecoderException("Badly compressed packet - size of " + read
                + " is larger than protocol maximum");
        }
        byte[] compressed = INPUT.get();
        if (compressed.length < read) {
            compressed = new byte[read];
            INPUT.set(compressed);
        }
        in.readBytes(compressed, 0, read);
        byte[] raw = RAW.get();
        if (raw.length < originalLen) {
            raw = new byte[originalLen];
            RAW.set(raw);
        }
        int n = (int) com.github.luben.zstd.Zstd.decompressByteArray(
            raw, 0, originalLen, compressed, 0, read);
        if (n != originalLen) {
            throw new DecoderException("ZSTD decompression produced " + n
                + " bytes, expected " + originalLen);
        }
        out.add(Unpooled.wrappedBuffer(raw, 0, n));
    }

    /** Vanilla zlib frame path: the remaining bytes are one zlib stream. */
    private void decodeZlib(ByteBuf in, List<Object> out, int originalLen) {
        int read = in.readableBytes();
        if (read > MAXIMUM_COMPRESSED_LENGTH) {
            throw new DecoderException("Badly compressed packet - size of " + read
                + " is larger than protocol maximum");
        }
        byte[] compressed = INPUT.get();
        if (compressed.length < read) {
            compressed = new byte[read];
            INPUT.set(compressed);
        }
        in.readBytes(compressed, 0, read);
        byte[] raw = RAW.get();
        if (raw.length < originalLen) {
            raw = new byte[originalLen];
            RAW.set(raw);
        }
        this.inflater.reset();
        this.inflater.setInput(compressed, 0, read);
        int n;
        try {
            n = this.inflater.inflate(raw, 0, originalLen);
        } catch (DataFormatException e) {
            throw new DecoderException("Badly compressed packet", e);
        }
        if (n != originalLen) {
            throw new DecoderException("Badly compressed packet - size of " + n
                + " is not equal to original size of " + originalLen);
        }
        out.add(Unpooled.wrappedBuffer(raw, 0, n));
    }

    /** True if the next 4 bytes (from the current reader index) are the ZSTD
     *  frame magic (28 B5 2F FD). */
    private boolean isZstdFrame(ByteBuf buf) {
        if (buf.readableBytes() < 4) return false;
        int idx = buf.readerIndex();
        return buf.getByte(idx) == (byte) 0x28
            && buf.getByte(idx + 1) == (byte) 0xB5
            && buf.getByte(idx + 2) == (byte) 0x2F
            && buf.getByte(idx + 3) == (byte) 0xFD;
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int bytes = 0;
        byte b;
        do {
            b = buf.readByte();
            value |= (b & 127) << bytes++ * 7;
            if (bytes > 5) {
                throw new DecoderException("VarInt too big");
            }
        } while ((b & 128) == 128);
        return value;
    }
}