package com.server_optimize;

import com.server_optimize.command.ServerOptimizeCommand;
import com.server_optimize.config.ModConfig;
import com.server_optimize.networking.CompressionHello;
import com.server_optimize.networking.HopperStatsSync;
import com.server_optimize.networking.HopperStatsSyncPacket;
import com.server_optimize.networking.SectionCulling;
import com.server_optimize.networking.UdpSupportPacket;
import com.server_optimize.networking.ZstdCodecSwitcher;
import com.server_optimize.networking.ZstdSupportPacket;
import com.server_optimize.util.ChunkPacketBatcher;
import com.server_optimize.util.EntityPacketBatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerOptimize implements ModInitializer {
    public static final String MOD_ID = "server-optimize";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModConfig.load();
        ServerOptimizeCommand.register();

        // Feature 1: incompatible-mod detection warning (C2ME / Accelerated
        // Recoiling). The overrides already happened inside ModConfig.load();
        // this surfaces a visible startup warning.
        String incompatible = ModConfig.compatibilityDetectedMods;
        if (!incompatible.isEmpty()) {
            LOGGER.warn("Detected incompatible/overlapping mod(s): {}. Conflicting optimizations have been "
                + "disabled at runtime (config file untouched). See README for details.", incompatible);
        }

        SectionCulling.register();
        CompressionHello.register();
        ZstdSupportPacket.register();
        UdpSupportPacket.register();
        HopperStatsSyncPacket.register();

        // Server side: always respond to ZSTD capability request.
        // If useZstd is enabled, tell client "yes" + compression level.
        // If useZstd is disabled or config missing, tell client "no".
        // This ensures the client can negotiate properly instead of guessing.
        // The server's own encoder is NOT swapped here: it waits for the
        // client's ZstdAck (which proves the client switched its decoder to
        // the universal one) and then swaps on the server side.
        ServerPlayNetworking.registerGlobalReceiver(ZstdSupportPacket.REQUEST_TYPE, (payload, context) -> {
            if (payload.version() < ZstdSupportPacket.PROTOCOL_VERSION) return;
            ModConfig zCfg = ModConfig.INSTANCE;
            // Server decides: use ZSTD when enabled OR forced (server-only
            // net.forceUseZSTD overrides a client that disabled it). A
            // client without the mod never sends a request, so it always
            // keeps vanilla zlib. Compression must actually be on (threshold
            // >= 0), otherwise there is nothing to swap.
            boolean supported = zCfg != null && (zCfg.net.useZstd || zCfg.net.forceUseZSTD)
                && context.server().getCompressionThreshold() >= 0;
            int level = zCfg != null ? zCfg.net.zstdLevel : 4;
            if (ZstdSupportPacket.logEnabled()) {
                ServerOptimize.LOGGER.info("ZSTD request from {} -> supported={} level={}",
                    context.player().getGameProfile().name(), supported, level);
            }
            ServerPlayNetworking.send(context.player(),
                new ZstdSupportPacket.ZstdResponse(supported, level));
        });

        // Client confirms it switched its decoder to the universal one and its
        // encoder to ZSTD; the server may now switch its own encoder to ZSTD.
        ServerPlayNetworking.registerGlobalReceiver(ZstdSupportPacket.ACK_TYPE, (payload, context) -> {
            if (payload.version() < ZstdSupportPacket.PROTOCOL_VERSION) return;
            int threshold = context.server().getCompressionThreshold();
            if (threshold < 0) return;
            ZstdCodecSwitcher.enableServerZstd(context.player(), threshold);
            ZstdSupportPacket.markZstd(context.player());
            if (ZstdSupportPacket.logEnabled()) {
                ServerOptimize.LOGGER.info("ZSTD ack from {} -> encoder switched to ZSTD (threshold {})",
                    context.player().getGameProfile().name(), threshold);
            }
        });

        // Client side: ZSTD support packet handlers registered in ServerOptimizeClient.

        // Server side: UDP capability handshake (net.useUDP / net.forceUseUDP).
        // Respond with the UDP port when this server runs the mod with UDP
        // enabled (or forced); otherwise reply unsupported so the client
        // stays on TCP. udpPort 0 = use the vanilla server-port (TCP and UDP
        // share the same port number).
        // UDP 能力握手:服务器开启(或强制)UDP 时响应端口,否则回 unsupported
        // 保持 TCP。udpPort 0 = 与原版 server-port 相同端口。
        ServerPlayNetworking.registerGlobalReceiver(UdpSupportPacket.HELLO_TYPE, (payload, context) -> {
            ModConfig netCfg = ModConfig.INSTANCE;
            boolean udpOn = netCfg != null && (netCfg.net.useUDP || netCfg.net.forceUseUDP);
            boolean supported = udpOn && payload.version() >= UdpSupportPacket.PROTOCOL_VERSION;
            int port = (netCfg != null && netCfg.net.udpPort > 0)
                ? netCfg.net.udpPort
                : context.server().getPort();
            if (com.server_optimize.networking.udp.UdpMigration.logEnabled()) {
                ServerOptimize.LOGGER.info("UDP hello from {} -> supported={} port={}",
                    context.player().getGameProfile().name(), supported, port);
            }
            ServerPlayNetworking.send(context.player(),
                com.server_optimize.networking.udp.UdpMigration.prepareHelloAck(
                    context.player(), supported, port, UdpSupportPacket.PROTOCOL_VERSION));
        });

        // Client confirmed UDP connectivity over the reliable probe and
        // requested the transport switch over TCP; migrate now.
        ServerPlayNetworking.registerGlobalReceiver(UdpSupportPacket.READY_TYPE, (payload, context) -> {
            if (payload.version() < UdpSupportPacket.PROTOCOL_VERSION) return;
            context.server().execute(() ->
                com.server_optimize.networking.udp.UdpMigration.activateServer(context.player()));
        });

        // Server side: hello handshake + cleanup on disconnect (section culling).
        ServerPlayNetworking.registerGlobalReceiver(CompressionHello.TYPE, (payload, context) -> {
            if (payload.version() >= CompressionHello.PROTOCOL_VERSION) {
                context.server().execute(() ->
                    SectionCulling.markSupported(context.player()));
            }
        });

        // Server side: F3 hopper-stats sync. A modded client announces itself
        // on JOIN; the server pushes whole-server hopper stats every 20 ticks.
        // No config gates this - it works whenever both sides run the mod.
        ServerPlayNetworking.registerGlobalReceiver(HopperStatsSyncPacket.HELLO_TYPE, (payload, context) -> {
            if (payload.version() >= HopperStatsSyncPacket.PROTOCOL_VERSION) {
                context.server().execute(() ->
                    HopperStatsSync.markModded(context.player()));
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SectionCulling.removePlayer(handler.player);
            EntityPacketBatcher.remove(handler);
            HopperStatsSync.removePlayer(handler.player);
            ZstdSupportPacket.unmarkZstd(handler.player);
            com.server_optimize.networking.udp.UdpMigration.removePlayer(handler.player);
        });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING
            .register(server -> {
                SectionCulling.SERVER = server;
                com.server_optimize.networking.udp.UdpMigration.startServer(server);
                // Feature 2: thread affinity - rebuild the worker pool with
                // pinned workers when [thread].enabled + pinWorkers are on.
                com.server_optimize.thread.AffinityManager.onServerStarting();
                // Register the command permission nodes with LuckPerms so
                // they are grantable/visible in /lp. Retried on the first
                // ticks if LuckPerms was not ready yet.
                com.server_optimize.util.LuckPermsNodes.registerAll();
            });
        // Feature 2 + 3: per-tick drivers - affinity re-apply at the tick
        // boundary (pending config reloads) and the entity-stacking sweep.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK
            .register(server -> {
                com.server_optimize.thread.AffinityManager.onServerTick();
                com.server_optimize.util.EntityStackTracker.onServerTick(server);
                // Retry LuckPerms node registration for a few seconds in
                // case the provider was not loaded at SERVER_STARTING.
                if (!com.server_optimize.util.LuckPermsNodes.isRegistered()
                    && server.getTickCount() < 200
                    && (server.getTickCount() & 19) == 0) {
                    com.server_optimize.util.LuckPermsNodes.registerAll();
                }
            });
        // Clear after save phase (SERVER_STOPPED fires after stopServer()
        // completes, so the LightRefreshThrottleMixin bypass that checks
        // SectionCulling.SERVER != null && !server.isRunning() works during
        // the save phase). Clearing too early (SERVER_STOPPING) would leave
        // the light throttle active during shutdown, preventing light engine
        // workers from draining their queues, which blocks the server thread
        // in ThreadedLevelLightEngine.close() and keeps the JVM alive.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED
            .register(server -> {
                SectionCulling.SERVER = null;
                ChunkPacketBatcher.shutdown();
                com.server_optimize.networking.udp.UdpMigration.stopServer();
            });

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            LOGGER.info("ServerOptimize initialized");
        }
    }
}
