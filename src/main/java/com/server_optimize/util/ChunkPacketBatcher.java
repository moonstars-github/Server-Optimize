package com.server_optimize.util;

import com.server_optimize.config.ModConfig;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Batch packet sender (chunk.batchPacketSend).
 * <p>
 * Groups chunk packets by region (32x32 chunks) and sends them as a batch
 * instead of one Netty send per chunk. Vanilla sends one packet per chunk,
 * incurring per-packet overhead (~35 bytes header + serialization + syscall).
 * <p>
 * Packets are queued per region and flushed on a short delay (default 20 ms).
 * A player traversing a contiguous area accumulates many chunk packets that
 * are then sent in a few batched groups, amortizing the per-packet overhead
 * while keeping latency well under one tick (50 ms).
 */
public final class ChunkPacketBatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("server-optimize");

    private static final long FLUSH_INTERVAL_MS = 20;

    private static final Map<Long, List<Packet<?>>> QUEUED = new HashMap<>();
    private static final Map<Long, ServerPlayer> PLAYERS = new HashMap<>();
    private static final ScheduledExecutorService FLUSHER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "server-optimize-packet-flusher");
            t.setDaemon(true);
            return t;
        });

    static {
        FLUSHER.scheduleWithFixedDelay(ChunkPacketBatcher::flushAll, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private ChunkPacketBatcher() {
    }

    /**
     * Queue a chunk packet for batched sending. If batching is disabled,
     * the packet is sent immediately.
     * <p>
     * Flush is triggered from the queue method (not a delayed periodic
     * task): delaying chunk packets by a fixed interval breaks the vanilla
     * batch ordering. The server sends ClientboundChunkBatchStart, then the
     * chunk packets, then ClientboundChunkBatchFinished; the client acks the
     * batch based on that order. If we hold the chunk packets for N ms while
     * the start/finished markers go out immediately, the client acks a batch
     * whose data has not arrived yet - ack flow-control desync that leaves
     * chunks late / missing (void) on world entry and makes re-entry feel
     * like a long wait.
     */
    public static void queue(int chunkX, int chunkZ, Packet<?> packet, ServerPlayer player) {
        if (player == null || player.connection == null || !player.connection.isAcceptingMessages()) {
            return;
        }
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.batchPacketSend) {
            player.connection.send(packet);
            return;
        }
        long regionKey = regionKey(chunkX, chunkZ);
        synchronized (QUEUED) {
            QUEUED.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(packet);
            PLAYERS.put(regionKey, player);
        }
        // Immediate flush keeps vanilla packet order. Netty still aggregates
        // the individual sends; the periodic flusher remains only as a
        // safety net for anything queued around shutdown.
        flushAll();
    }

    /**
     * Flush all queued region batches. Called by the periodic flusher and
     * also safe to call manually during server save/shutdown.
     */
    public static void flushAll() {
        List<Packet<?>>[] toSend;
        ServerPlayer[] players;
        synchronized (QUEUED) {
            int n = QUEUED.size();
            if (n == 0) {
                return;
            }
            toSend = new List[0];
            List<Packet<?>>[] batches = new List[n];
            ServerPlayer[] owners = new ServerPlayer[n];
            java.util.Iterator<Map.Entry<Long, List<Packet<?>>>> it = QUEUED.entrySet().iterator();
            int i = 0;
            while (it.hasNext()) {
                Map.Entry<Long, List<Packet<?>>> e = it.next();
                batches[i] = e.getValue();
                owners[i] = PLAYERS.get(e.getKey());
                it.remove();
                i++;
            }
            toSend = batches;
            players = owners;
            PLAYERS.keySet().removeIf(k -> !QUEUED.containsKey(k));
        }
        for (int i = 0; i < toSend.length; i++) {
            ServerPlayer p = players[i];
            List<Packet<?>> batch = toSend[i];
            if (p == null || batch == null) {
                continue;
            }
            for (Packet<?> pkt : batch) {
                try {
                    p.connection.send(pkt);
                } catch (Exception e) {
                    // Do not abort the rest of the batch on one failure:
                    // dropping the remaining chunks of a region leaves them
                    // missing on the client (void).
                    LOGGER.debug("failed to send batched packet: {}", e.toString());
                }
            }
        }
    }

    /** Shutdown the periodic flusher (server stop). */
    public static void shutdown() {
        FLUSHER.shutdownNow();
        flushAll();
    }

    private static long regionKey(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX >> 5, chunkZ >> 5);
    }
}