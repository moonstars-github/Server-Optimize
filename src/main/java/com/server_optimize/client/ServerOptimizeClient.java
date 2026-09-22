package com.server_optimize.client;

import com.server_optimize.client.debug.HopperStatsClientCache;
import com.server_optimize.networking.HopperStatsSyncPacket;
import com.server_optimize.networking.SectionCulling;
import com.server_optimize.networking.UdpSupportPacket;
import com.server_optimize.networking.ZstdSupportPacket;
import com.server_optimize.util.UdpTransport;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side initialization.
 * <p>
 * Registers the culled-chunk S2C receiver (section culling), the ZSTD and UDP
 * capability responses, and re-announces the mod's capabilities until the
 * server answers (resilient to network delay / lost handshake packets). The
 * UDP transport is phase 2; for now the handshake only records capability.
 */
public class ServerOptimizeClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("server-optimize-client");

    /** Retry the capability hellos at these client ticks until answered. */
    private static final int[] RETRY_TICKS = {40, 100, 200}; // 2s / 5s / 10s

    private static boolean zstdAnswered = false;
    private static boolean udpAnswered = false;
    private static int sessionTick = -1; // -1 = not in a play session

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SectionCulling.PAYLOAD_TYPE, (payload, context) -> {
            context.client().execute(() -> {
                // 1.21.11: MinecraftClient.getConnection() returns the
                // ClientPacketListener directly.
                var listener = context.client().getConnection();
                if (listener != null) {
                    SectionCulling.dispatchCulled(payload, listener);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(UdpSupportPacket.HELLO_ACK_TYPE, (payload, context) -> {
            udpAnswered = true;
            UdpTransport.setClientNegotiated(payload.supported());
            if (payload.supported()) {
                if (com.server_optimize.networking.udp.UdpMigration.logEnabled()) {
                    LOGGER.info("Server supports UDP transport (port {}) - probing connectivity...",
                        payload.udpPort());
                }
                // Start the reliable-UDP probe + migration (phase 2 transport).
                var listener = context.client().getConnection();
                if (listener != null && payload.sessionKey() != null) {
                    com.server_optimize.networking.udp.UdpMigration.startClient(
                        listener.getConnection(), payload.udpPort(),
                        payload.sessionKey(), payload.sessionToken());
                }
            } else if (com.server_optimize.networking.udp.UdpMigration.logEnabled()) {
                LOGGER.info("Server does not support UDP transport - staying on vanilla TCP");
            }
        });

        // Server confirmed the switch over TCP; activate the client's tunnel.
        ClientPlayNetworking.registerGlobalReceiver(UdpSupportPacket.READY_ACK_TYPE, (payload, context) -> {
            if (payload.ok()) {
                com.server_optimize.networking.udp.UdpMigration.activateClient();
            }
        });

        // ZSTD negotiation: server replied. On "supported" the client swaps
        // its decoder to the universal (zlib+ZSTD) one, swaps its encoder to
        // ZSTD, then acks so the server can swap its own encoder. On "no"
        // (vanilla server / disabled) everything stays vanilla zlib.
        ClientPlayNetworking.registerGlobalReceiver(ZstdSupportPacket.RESPONSE_TYPE, (payload, context) -> {
            zstdAnswered = true;
            ZstdSupportPacket.cacheResponse(payload.supported(), payload.compressionLevel());
            if (!payload.supported()) return;
            int threshold = ZstdSupportPacket.lastThreshold();
            if (threshold < 0) return; // compression disabled - nothing to swap
            var listener = context.client().getConnection();
            if (listener == null) return;
            var connection = listener.getConnection();
            com.server_optimize.networking.ZstdCodecSwitcher.enableUniversalDecoder(connection, threshold);
            com.server_optimize.networking.ZstdCodecSwitcher.enableZstd(connection, threshold);
            ClientPlayNetworking.send(new ZstdSupportPacket.ZstdAck(ZstdSupportPacket.PROTOCOL_VERSION));
        });

        // F3 hopper stats: cache whole-server stats pushed by a dedicated
        // server. Cleared on DISCONNECT so stale data from a previous server
        // never leaks into a new session (singleplayer / vanilla server).
        ClientPlayNetworking.registerGlobalReceiver(HopperStatsSyncPacket.PAYLOAD_TYPE, (payload, context) -> {
            context.client().execute(() -> HopperStatsClientCache.update(payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            HopperStatsClientCache.clear();
            sessionTick = -1;
            com.server_optimize.networking.udp.UdpMigration.stopClient();
            // Prune incorrect commands from command_history.txt (client.CommandHistoryDropError).
            com.server_optimize.client.CommandHistoryManager.onExit();
        });

        // Announce the mod's capabilities whenever it is present; the server
        // decides whether to use them (it may force them via forceUseUDP /
        // forceUseZSTD even if the client disabled them locally). A client
        // without the mod never sends these, so it always stays on vanilla.
        // The hellos are re-sent on a schedule until the server answers, so a
        // delayed or dropped handshake packet still engages the features.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            sessionTick = 0;
            zstdAnswered = false;
            udpAnswered = false;
            sendCapabilityHellos();
            // Feature 2: when connected to a REMOTE server, the mod keeps its
            // own background workers off (thread.disableWorkersWhenConnected),
            // leaving the CPU to the server. Pure-remote clients never fire
            // SERVER_STARTING, so the worker pool is never rebuilt here; log
            // the state for clarity. Rendering-needed vanilla threads are
            // never touched.
            if (!client.isLocalServer()
                && com.server_optimize.thread.AffinityManager.remoteWorkersDisabled()) {
                LOGGER.info("Connected to a remote server: mod background workers "
                    + "disabled (thread.disableWorkersWhenConnected=true)");
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (sessionTick < 0) return;
            if (zstdAnswered && udpAnswered) {
                sessionTick = -1; // fully answered - stop retrying
                return;
            }
            sessionTick++;
            for (int retry : RETRY_TICKS) {
                if (sessionTick == retry) {
                    sendCapabilityHellos();
                    break;
                }
            }
        });

        LOGGER.info("ServerOptimizeClient initialized");
    }

    /** Send the capability announcements (idempotent; server handles dups). */
    private static void sendCapabilityHellos() {
        ClientPlayNetworking.send(new UdpSupportPacket.UdpHello(UdpSupportPacket.PROTOCOL_VERSION));
        ClientPlayNetworking.send(new ZstdSupportPacket.ZstdRequest(ZstdSupportPacket.PROTOCOL_VERSION));
        // Announce the mod for F3 hopper-stats sync (dedicated server only).
        // NOTE: CompressionHello (section culling) is deliberately NOT sent
        // yet - the client-side dispatch path has never been exercised end to
        // end, and enabling it untested risks the void-on-chunk-load bug.
        ClientPlayNetworking.send(new HopperStatsSyncPacket.HopperStatsHello(
            HopperStatsSyncPacket.PROTOCOL_VERSION));
    }
}