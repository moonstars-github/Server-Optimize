package com.server_optimize.util;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity position packet batching (net.entityPacketBatching).
 * <p>
 * Vanilla sends one {@code move_entity_pos} / {@code entity_position_sync}
 * packet per moving entity per tick. With many entities (armor-stand or mob
 * farms) that is a packet storm (~150k packets/s in the armor-stand
 * benchmark) that floods every connection and tanks client FPS (avg 2.4).
 * <p>
 * Position-only packets are queued per listener and flushed once per tick as
 * a single vanilla {@link ClientboundBundlePacket}. The vanilla client
 * decodes bundles natively, so there is no custom protocol and no handshake.
 * Non-position packets are never delayed; a singleton batch is sent as a
 * plain packet (no bundle wrapper) so the common small-entity case is
 * byte-identical to vanilla.
 */
public final class EntityPacketBatcher {

    private static final Map<ServerCommonPacketListenerImpl, List<Packet<?>>> QUEUES =
        new ConcurrentHashMap<>();

    private EntityPacketBatcher() {
    }

    /** True for position/teleport packets that are safe to batch. */
    public static boolean isBatchable(Packet<?> packet) {
        return packet instanceof ClientboundMoveEntityPacket
            || packet instanceof ClientboundEntityPositionSyncPacket;
    }

    /** Queue a position packet for the per-tick bundle flush. */
    public static void offer(ServerCommonPacketListenerImpl listener, Packet<?> packet) {
        QUEUES.computeIfAbsent(listener, k -> new ArrayList<>()).add(packet);
    }

    /** Vanilla rejects bundles over this many packets ("Too many packets in a bundle"). */
    private static final int MAX_BUNDLE_PACKETS = 4096;

    /** Send each listener's queued position packets (called at tick end). */
    public static void flushAll() {
        if (QUEUES.isEmpty()) {
            return;
        }
        for (Map.Entry<ServerCommonPacketListenerImpl, List<Packet<?>>> e : QUEUES.entrySet()) {
            List<Packet<?>> batch = e.getValue();
            if (batch.isEmpty()) {
                continue;
            }
            e.setValue(new ArrayList<>());
            ServerCommonPacketListenerImpl listener = e.getKey();
            if (batch.size() == 1) {
                // Single packet: send plainly, identical to vanilla.
                listener.send(batch.get(0));
            } else {
                // Vanilla caps bundles at 4096 packets; huge entity worlds
                // (e.g. 16384 armor stands) exceed that in one tick, so split
                // into multiple bundles.
                for (int i = 0; i < batch.size(); i += MAX_BUNDLE_PACKETS) {
                    int end = Math.min(i + MAX_BUNDLE_PACKETS, batch.size());
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> wrapped =
                        (List) batch.subList(i, end);
                    listener.send(new ClientboundBundlePacket(wrapped));
                }
            }
        }
    }

    /** Drop a disconnected listener's queue. */
    public static void remove(ServerCommonPacketListenerImpl listener) {
        QUEUES.remove(listener);
    }
}
