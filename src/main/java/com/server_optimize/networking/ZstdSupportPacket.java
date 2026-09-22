package com.server_optimize.networking;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ZSTD compression capability negotiation (net.useZstd / forceUseZSTD).
 * <p>
 * Vanilla installs the zlib codecs during the login phase, before any
 * post-join handshake, so ZSTD cannot be negotiated in advance. Instead the
 * algorithm is switched at play time with a three-message handshake:
 * <ol>
 *   <li>Client joins → sends {@link ZstdRequest} (play phase)</li>
 *   <li>Server replies {@link ZstdResponse} (supported? + level)</li>
 *   <li>On supported: client swaps its decoder to the universal one and its
 *       encoder to ZSTD, then sends {@link ZstdAck}</li>
 *   <li>Server receives the ack → swaps its encoder to ZSTD (its decoder is
 *       already universal from {@link ConnectionCompressionMixin})</li>
 * </ol>
 * A client without the mod never sends a request/ack, so it always stays on
 * vanilla zlib; a vanilla server never responds, so the client never swaps.
 */
public final class ZstdSupportPacket {

    public static final int PROTOCOL_VERSION = 1;

    public static final Identifier SUPPORT_ID = Identifier.fromNamespaceAndPath("server-optimize", "zstd_support");
    public static final Identifier ACK_ID = Identifier.fromNamespaceAndPath("server-optimize", "zstd_ack");

    public static final CustomPacketPayload.Type<ZstdRequest> REQUEST_TYPE = new CustomPacketPayload.Type<>(SUPPORT_ID);
    public static final CustomPacketPayload.Type<ZstdResponse> RESPONSE_TYPE = new CustomPacketPayload.Type<>(SUPPORT_ID);
    public static final CustomPacketPayload.Type<ZstdAck> ACK_TYPE = new CustomPacketPayload.Type<>(ACK_ID);

    private ZstdSupportPacket() {
    }

    /** Client → server: "I support ZSTD, do you?" */
    public record ZstdRequest(int version) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, ZstdRequest> CODEC = StreamCodec.ofMember(
            ZstdRequest::write, ZstdRequest::read);

        private static ZstdRequest read(RegistryFriendlyByteBuf buf) {
            return new ZstdRequest(buf.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(this.version);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return REQUEST_TYPE;
        }
    }

    /** Server → client: "Yes/No I support/use ZSTD" + compression level. */
    public record ZstdResponse(boolean supported, int compressionLevel) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, ZstdResponse> CODEC = StreamCodec.ofMember(
            ZstdResponse::write, ZstdResponse::read);

        private static ZstdResponse read(RegistryFriendlyByteBuf buf) {
            return new ZstdResponse(buf.readBoolean(), buf.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBoolean(this.supported);
            buf.writeVarInt(this.compressionLevel);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return RESPONSE_TYPE;
        }
    }

    /** Client → server: "decoder swapped to universal; you may switch your encoder". */
    public record ZstdAck(int version) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, ZstdAck> CODEC = StreamCodec.ofMember(
            ZstdAck::write, ZstdAck::read);

        private static ZstdAck read(RegistryFriendlyByteBuf buf) {
            return new ZstdAck(buf.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(this.version);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ACK_TYPE;
        }
    }

    /** Negotiation result cached from the server response (client side). */
    private static volatile boolean zstdServerSupportsZstd = false;
    private static volatile boolean zstdNegotiated = false;
    /** Compression threshold received at login (client side, for the swap). */
    private static volatile int lastThreshold = -1;

    /** Server side: players whose connection is currently on ZSTD. */
    private static final Set<ServerPlayer> ZSTD_PLAYERS = ConcurrentHashMap.newKeySet();

    public static void register() {
        PayloadTypeRegistry.playC2S().register(REQUEST_TYPE, ZstdRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(RESPONSE_TYPE, ZstdResponse.CODEC);
        PayloadTypeRegistry.playC2S().register(ACK_TYPE, ZstdAck.CODEC);
    }

    /** Called from ConnectionCompressionMixin when the threshold is known. */
    public static void noteThreshold(int threshold) {
        lastThreshold = threshold;
    }

    /** The compression threshold this side received at login (-1 = disabled). */
    public static int lastThreshold() {
        return lastThreshold;
    }

    /** Cache result from server response (called by client-side handler). */
    public static void cacheResponse(boolean supported, int compressionLevel) {
        zstdNegotiated = true;
        zstdServerSupportsZstd = supported;
        com.server_optimize.util.NetworkStatus.setZstdActive(
            supported && com.server_optimize.config.ModConfig.INSTANCE != null
                && com.server_optimize.config.ModConfig.INSTANCE.net.useZstd);
        if (supported) {
            if (logEnabled()) {
                com.server_optimize.ServerOptimize.LOGGER.info(
                    "ZSTD compression enabled - server supports it (level {})", compressionLevel);
            }
        } else if (logEnabled()) {
            com.server_optimize.ServerOptimize.LOGGER.info(
                "ZSTD negotiation failed - falling back to vanilla zlib");
        }
    }

    /** Server side: record a connection that switched to ZSTD. */
    public static void markZstd(ServerPlayer player) {
        ZSTD_PLAYERS.add(player);
        com.server_optimize.util.NetworkStatus.setZstdActive(true);
    }

    public static void unmarkZstd(ServerPlayer player) {
        ZSTD_PLAYERS.remove(player);
        if (ZSTD_PLAYERS.isEmpty()) {
            com.server_optimize.util.NetworkStatus.setZstdActive(false);
        }
    }

    /** Number of connections currently using ZSTD (server side). */
    public static int zstdConnectionCount() {
        return ZSTD_PLAYERS.size();
    }

    /** Server side: is this player's connection currently using ZSTD? */
    public static boolean isZstdActive(ServerPlayer player) {
        return ZSTD_PLAYERS.contains(player);
    }

    /** [log].zstdConnectionLog: routine ZSTD connection INFO lines. */
    public static boolean logEnabled() {
        com.server_optimize.config.ModConfig cfg = com.server_optimize.config.ModConfig.INSTANCE;
        return cfg != null && cfg.log.zstdConnectionLog;
    }

    /** Reset negotiation state when disconnecting from a server. */
    public static void resetNegotiation() {
        zstdNegotiated = false;
        zstdServerSupportsZstd = false;
    }
}