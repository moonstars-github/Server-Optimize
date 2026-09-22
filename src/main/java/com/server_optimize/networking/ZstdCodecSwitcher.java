package com.server_optimize.networking;

import com.server_optimize.mixin.accessor.ConnectionAccessor;
import com.server_optimize.mixin.accessor.ServerCommonPacketListenerAccessor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Play-time codec swap (zlib → ZSTD) on the netty pipeline.
 * <p>
 * The encoder is swapped independently on each side after the ZSTD handshake;
 * the decoders are universal ({@link ZstdCompressionDecoder} handles both
 * zlib and ZSTD), so no cross-side synchronization is required beyond the
 * handshake ordering. All pipeline mutations run on the channel's event loop.
 */
public final class ZstdCodecSwitcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("server-optimize");

    private ZstdCodecSwitcher() {
    }

    /** Swap this connection's encoder from vanilla zlib to ZSTD. */
    public static void enableZstd(Connection connection, int threshold) {
        if (connection == null || threshold < 0) return;
        try {
            Channel channel = ((ConnectionAccessor) connection).serverOptimize$getChannel();
            if (channel == null) return;
            channel.eventLoop().execute(() -> {
                try {
                    ChannelPipeline pipeline = channel.pipeline();
                    if (pipeline.get("compress") instanceof ZstdCompressionEncoder) return;
                    if (pipeline.get("compress") == null) return;
                    pipeline.remove("compress");
                    pipeline.addAfter("prepender", "compress", new ZstdCompressionEncoder(threshold));
                } catch (Exception e) {
                    LOGGER.warn("Failed to swap encoder to ZSTD; staying on zlib", e);
                }
            });
        } catch (Exception e) {
            LOGGER.warn("Failed to access connection channel for ZSTD swap", e);
        }
    }

    /** Swap this connection's decoder to the universal (zlib + ZSTD) one. */
    public static void enableUniversalDecoder(Connection connection, int threshold) {
        if (connection == null || threshold < 0) return;
        try {
            Channel channel = ((ConnectionAccessor) connection).serverOptimize$getChannel();
            if (channel == null) return;
            channel.eventLoop().execute(() -> {
                try {
                    ChannelPipeline pipeline = channel.pipeline();
                    if (pipeline.get("decompress") instanceof ZstdCompressionDecoder) return;
                    if (pipeline.get("decompress") == null) return;
                    pipeline.remove("decompress");
                    pipeline.addAfter("splitter", "decompress", new ZstdCompressionDecoder(threshold, false));
                } catch (Exception e) {
                    LOGGER.warn("Failed to swap decoder to universal; staying on zlib", e);
                }
            });
        } catch (Exception e) {
            LOGGER.warn("Failed to access connection channel for decoder swap", e);
        }
    }

    /** Server side: swap a player's encoder to ZSTD (after receiving ZstdAck). */
    public static void enableServerZstd(ServerPlayer player, int threshold) {
        if (player == null || player.connection == null) return;
        Connection connection =
            ((ServerCommonPacketListenerAccessor) player.connection).serverOptimize$getConnection();
        enableZstd(connection, threshold);
    }
}
