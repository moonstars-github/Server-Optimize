package com.server_optimize.networking;

import com.server_optimize.ServerOptimize;
import com.server_optimize.util.ChunkPacketBatcher;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Off-thread chunk packet building (chunk.asyncPacketBuild).
 *
 * Vanilla builds ClientboundLevelChunkWithLightPacket (131 sections of
 * palette serialization + light data copy + block entities) on the server
 * thread inside PlayerChunkSender.sendChunk; under a chunk-loading storm that
 * was the dominant server-thread cost and the main cause of tick drops.
 *
 * The whole build+send step is moved to a small daemon thread pool. The
 * packet constructor reads chunk/light data that is stable for freshly
 * loaded chunks, and SectionCulling's hooks use ThreadLocal buffers +
 * ConcurrentHashMap caches, so the off-thread build is safe. Connection.send
 * is thread-safe. If the queue is full the task runs on the submitting
 * (server) thread as a fallback.
 */
public final class ChunkPacketBuilder {

    private static final int THREADS = configuredThreads();

    private static int configuredThreads() {
        com.server_optimize.config.ModConfig cfg = com.server_optimize.config.ModConfig.INSTANCE;
        if (cfg != null && cfg.chunk.packetBuilderThreads > 0) {
            return cfg.chunk.packetBuilderThreads;
        }
        return Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
    }

    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
        THREADS, THREADS, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1024),
        r -> {
            Thread t = new Thread(() -> {
                com.server_optimize.thread.AffinityManager.pinPoolThread("packet-builder", 0);
                r.run();
            }, "server-optimize-packet-builder");
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.CallerRunsPolicy());

    public static void submit(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
        EXECUTOR.execute(() -> buildAndSend(player, level, chunk));
    }

    private static void buildAndSend(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
        try {
            if (player == null || player.connection == null || !player.connection.isAcceptingMessages()) {
                return;
            }
            ClientboundLevelChunkWithLightPacket packet =
                new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            // SectionCulling.onChunkPacketBuilt runs automatically via the
            // packet <init> mixin hook, now on this worker thread.
            // Send via batch packet batcher (chunk.batchPacketSend)
            ChunkPacketBatcher.queue(chunk.getPos().x, chunk.getPos().z, packet, player);
        } catch (Throwable t) {
            ServerOptimize.LOGGER.warn("Async chunk packet build/send failed for player {}", player != null ? player.getName().getString() : "?", t);
        }
    }

    private ChunkPacketBuilder() {
    }
}
