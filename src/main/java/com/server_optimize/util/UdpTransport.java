package com.server_optimize.util;

import com.server_optimize.config.ModConfig;

/**
 * UDP transport state (net.useUDP), phase 1.
 * <p>
 * Holds the UDP port and the negotiated flag. Phase 1 only exchanges the
 * capability handshake ({@link com.server_optimize.networking.UdpSupportPacket})
 * on the TCP config channel; the reliable UDP frame layer (sequence/ACK/
 * retransmit/fragment + per-frame AES-GCM) is phase 2 and not implemented
 * yet, so {@code isActive()} stays false and connections keep the vanilla
 * TCP transport. The flag is reserved for the phase-2 switch-on.
 */
public final class UdpTransport {

    /** True once the server has an active UDP listener. */
    private static volatile boolean serverActive = false;

    /** True once this client negotiated UDP with the server. */
    private static volatile boolean clientNegotiated = false;

    private UdpTransport() {
    }

    /** The UDP port the server listens on (config, default 25566). */
    public static int udpPort() {
        ModConfig cfg = ModConfig.INSTANCE;
        return (cfg != null && cfg.net.udpPort > 0) ? cfg.net.udpPort : 25566;
    }

    /** Whether this side should advertise/accept UDP (config + capability). */
    public static boolean enabled() {
        ModConfig cfg = ModConfig.INSTANCE;
        return cfg != null && cfg.net.useUDP;
    }

    public static boolean isServerActive() {
        return serverActive;
    }

    public static void setServerActive(boolean active) {
        serverActive = active;
    }

    public static boolean isClientNegotiated() {
        return clientNegotiated;
    }

    public static void setClientNegotiated(boolean negotiated) {
        clientNegotiated = negotiated;
    }
}
