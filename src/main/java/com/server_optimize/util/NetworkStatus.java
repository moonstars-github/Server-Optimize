package com.server_optimize.util;

/**
 * Runtime network status for `/serveroptimize status net`.
 * <p>
 * Tracks negotiated transport/compression state. Currently the UDP transport
 * is not yet implemented, so {@code udpActive} stays false and connections
 * report {@code vanilla}; the field is reserved for the upcoming UDP channel
 * (net.useUDP) and updated on successful handshake.
 */
public final class NetworkStatus {

    /** True once the server/client negotiated ZSTD compression. */
    private static volatile boolean zstdActive = false;

    /** True once a UDP transport is negotiated and in use. */
    private static volatile boolean udpActive = false;

    private NetworkStatus() {
    }

    public static boolean isZstdActive() {
        return zstdActive;
    }

    public static void setZstdActive(boolean active) {
        zstdActive = active;
    }

    public static boolean isUdpActive() {
        return udpActive;
    }

    public static void setUdpActive(boolean active) {
        udpActive = active;
    }
}
