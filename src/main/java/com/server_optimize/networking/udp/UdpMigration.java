package com.server_optimize.networking.udp;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.ConnectionAccessor;
import com.server_optimize.mixin.accessor.ServerCommonPacketListenerAccessor;
import com.server_optimize.util.NetworkStatus;
import com.server_optimize.util.UdpTransport;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * UDP transport migration (net.useUDP phase 2): switches an established
 * play-phase connection from TCP to the reliable UDP layer.
 * <p>
 * Handshake (ordered): the UDP hello/ack (over TCP) delivers a session key
 * and token; the client opens a datagram channel and probes connectivity with
 * a reliable SYN/SYN_ACK exchange over UDP. Once connectivity is confirmed the
 * client requests the switch over TCP ({@code UdpReady}); the server replies
 * {@code UdpReadyAck} and installs its {@link UdpTunnel}; the client installs
 * its tunnel on receiving the ack. No application data crosses the boundary
 * on the wrong transport: everything before the ack is TCP, everything after
 * is UDP, and the reliable layer buffers frames until the tunnel is armed.
 * <p>
 * Fallback: if the UDP probe fails, the connection stays on vanilla TCP.
 */
public final class UdpMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger("server-optimize-udp");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Dedicated daemon event loop for datagram channels. */
    private static final NioEventLoopGroup UDP_EVENTS = new NioEventLoopGroup(1, r -> {
        Thread t = new Thread(r, "server-optimize-udp");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------
    // Server state
    // ------------------------------------------------------------------
    private static MinecraftServer SERVER;
    private static DatagramChannel serverChannel;
    private static volatile boolean serverActive = false;
    /** token -> player/key awaiting its UDP probe (set from UdpHello). */
    private static final Map<Long, PendingSession> PENDING = new ConcurrentHashMap<>();
    /** token -> established session. */
    private static final Map<Long, ServerSession> SESSIONS = new ConcurrentHashMap<>();
    /** Players whose connection currently runs on the UDP transport. */
    private static final java.util.Set<ServerPlayer> UDP_ACTIVE = ConcurrentHashMap.newKeySet();

    private record PendingSession(ServerPlayer player, byte[] key) {
    }

    private static final class ServerSession {
        final ServerPlayer player;
        final ReliableUdpTransport transport;
        final InetSocketAddress clientAddr;
        volatile boolean ready;

        ServerSession(ServerPlayer player, ReliableUdpTransport transport, InetSocketAddress clientAddr) {
            this.player = player;
            this.transport = transport;
            this.clientAddr = clientAddr;
        }
    }

    // ------------------------------------------------------------------
    // Client state
    // ------------------------------------------------------------------
    private static DatagramChannel clientChannel;
    private static ReliableUdpTransport clientTransport;
    private static Connection clientConnection;
    private static boolean clientSynAcked = false;

    private UdpMigration() {
    }

    /** [log].udpConnectionLog: routine UDP connection INFO lines. */
    public static boolean logEnabled() {
        ModConfig cfg = ModConfig.INSTANCE;
        return cfg != null && cfg.log.udpConnectionLog;
    }

    private static void info(String msg, Object... args) {
        if (logEnabled()) {
            LOGGER.info(msg, args);
        }
    }

    // ------------------------------------------------------------------
    // Server: listener + session management
    // ------------------------------------------------------------------

    /** Remember the server. The UDP listener bind is deferred until the first
     *  hello: {@code MinecraftServer.getPort()} returns -1 during
     *  SERVER_STARTING (the network listener does not exist yet), and
     *  udpPort=0 relies on it. */
    public static void startServer(MinecraftServer server) {
        SERVER = server;
        serverActive = true;
    }

    /** Bind the UDP listener now (called on the first hello; port is valid). */
    public static synchronized void ensureListener() {
        if (serverChannel != null) return;
        if (SERVER == null || !SERVER.isDedicatedServer()) {
            serverActive = false;
            return;
        }
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !(cfg.net.useUDP || cfg.net.forceUseUDP)) {
            serverActive = false;
            return;
        }
        int port = cfg.net.udpPort > 0 ? cfg.net.udpPort : SERVER.getPort();
        if (port <= 0 || port > 65535) {
            serverActive = false;
            LOGGER.warn("Invalid UDP port {} - UDP transport disabled", port);
            return;
        }
        try {
            DatagramChannel ch = new NioDatagramChannel();
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    DatagramPacket pkt = (DatagramPacket) msg;
                    try {
                        ByteBuf content = pkt.content();
                        if (content.readableBytes() < 8 + 12 + 16) return;
                        long token = content.readLong();
                        content.readerIndex(0); // transport re-reads+validates the token
                        ServerSession session = SESSIONS.get(token);
                        if (session != null) {
                            session.transport.receive(content);
                            return;
                        }
                        PendingSession pending = PENDING.remove(token);
                        if (pending != null) {
                            InetSocketAddress from = pkt.sender();
                            ReliableUdpTransport t = new ReliableUdpTransport(
                                ctx.channel().eventLoop(), serverSink(), from, pending.key(), token, false);
                            ServerSession s = new ServerSession(pending.player(), t, from);
                            t.setControlSink((type, body) -> onServerControl(s, type));
                            // Install the tunnel when the client's first UDP
                            // frame arrives; until then its TCP flows normally.
                            t.onFirstAppFrame(() -> {
                                installTunnel(s.player, t);
                                UDP_ACTIVE.add(s.player);
                                NetworkStatus.setUdpActive(true);
                                UdpTransport.setServerActive(true);
                                info("UDP transport active for {}", s.player.getGameProfile().name());
                            });
                            SESSIONS.put(token, s);
                            // drop the pending session if the client never commits
                            ctx.channel().eventLoop().schedule(() -> {
                                if (!s.ready) closeSession(token);
                            }, 5, TimeUnit.SECONDS);
                            t.receive(content);
                        }
                    } finally {
                        pkt.release();
                    }
                }
            });
            UDP_EVENTS.register(ch).syncUninterruptibly();
            ch.bind(new InetSocketAddress(port)).syncUninterruptibly();
            serverChannel = ch;
            serverActive = true;
            info("UDP transport listener bound on UDP port {}", port);
        } catch (Exception e) {
            LOGGER.warn("Failed to bind UDP listener on port {}; staying on TCP", port, e);
            serverActive = false;
            serverChannel = null;
        }
    }

    public static boolean isServerActive() {
        return serverActive && serverChannel != null && serverChannel.isOpen();
    }

    /** Called from the UDP hello receiver: generate key+token for a probe. */
    public static com.server_optimize.networking.UdpSupportPacket.UdpHelloAck prepareHelloAck(
            ServerPlayer player, boolean supported, int port, int version) {
        ensureListener();
        if (!supported || !isServerActive()) {
            return new com.server_optimize.networking.UdpSupportPacket.UdpHelloAck(
                false, port, version, null, 0);
        }
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        long token = RANDOM.nextLong();
        PENDING.put(token, new PendingSession(player, key));
        return new com.server_optimize.networking.UdpSupportPacket.UdpHelloAck(
            true, port, version, key, token);
    }

    public static void stopServer() {
        SERVER = null;
        serverActive = false;
        for (ServerSession s : SESSIONS.values()) {
            s.transport.stop();
        }
        SESSIONS.clear();
        PENDING.clear();
        if (serverChannel != null) {
            try {
                serverChannel.close().syncUninterruptibly();
            } catch (Exception ignored) {
            }
            serverChannel = null;
        }
    }

    /** Player disconnect: drop pending/session state. */
    public static void removePlayer(ServerPlayer player) {
        UDP_ACTIVE.remove(player);
        PENDING.entrySet().removeIf(e -> e.getValue().player() == player);
        for (Map.Entry<Long, ServerSession> e : SESSIONS.entrySet()) {
            if (e.getValue().player == player) {
                closeSession(e.getKey());
                break;
            }
        }
    }

    private static void closeSession(long token) {
        ServerSession s = SESSIONS.remove(token);
        if (s != null) {
            s.transport.stop();
            UDP_ACTIVE.remove(s.player);
            if (SESSIONS.isEmpty()) {
                NetworkStatus.setUdpActive(false);
            }
        }
    }

    /** Server side: is this player's connection currently on UDP? */
    public static boolean isUdpActive(ServerPlayer player) {
        return UDP_ACTIVE.contains(player);
    }

    private static void onServerControl(ServerSession session, int type) {
        if (type == ReliableUdpTransport.CTRL_SYN) {
            info("UDP probe received from {} - connectivity OK", session.player.getGameProfile().name());
            session.transport.sendControl(ReliableUdpTransport.CTRL_SYN_ACK, new byte[0]);
        }
    }

    /** Server side: the client requested the switch over TCP (UdpReady). */
    public static void activateServer(ServerPlayer player) {
        for (ServerSession s : SESSIONS.values()) {
            if (s.player == player) {
                s.ready = true;
                // Reply over TCP; the server's tunnel is installed on the
                // first UDP app frame (onFirstAppFrame), so the client's TCP
                // bytes keep flowing into the pipeline until its switch.
                net.minecraft.server.network.ServerGamePacketListenerImpl conn = player.connection;
                conn.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                    new com.server_optimize.networking.UdpSupportPacket.UdpReadyAck(true)));
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Client: probe + tunnel install
    // ------------------------------------------------------------------

    /** Start the UDP probe after the hello ack (client side). */
    public static void startClient(Connection connection, int udpPort, byte[] key, long token) {
        stopClient();
        if (connection == null || key == null) return;
        clientConnection = connection;
        clientSynAcked = false;
        try {
            SocketAddress remote = connection.getRemoteAddress();
            if (!(remote instanceof InetSocketAddress inetRemote)) {
                info("No real remote address - UDP migration skipped");
                stopClient();
                return;
            }
            InetSocketAddress serverAddr = new InetSocketAddress(inetRemote.getHostString(), udpPort);
            DatagramChannel ch = new NioDatagramChannel();
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    DatagramPacket pkt = (DatagramPacket) msg;
                    try {
                        if (clientTransport != null) {
                            clientTransport.receive(pkt.content());
                        }
                    } finally {
                        pkt.release();
                    }
                }
            });
            UDP_EVENTS.register(ch).syncUninterruptibly();
            ch.bind(new InetSocketAddress(0)).syncUninterruptibly();
            ch.connect(serverAddr).syncUninterruptibly();
            clientChannel = ch;
            clientTransport = new ReliableUdpTransport(
                ch.eventLoop(), clientSink(), serverAddr, key, token, true);
            clientTransport.setControlSink((type, body) -> {
                if (type == ReliableUdpTransport.CTRL_SYN_ACK) {
                    clientSynAcked = true;
                    info("UDP connectivity confirmed - requesting transport switch");
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                        new com.server_optimize.networking.UdpSupportPacket.UdpReady(
                            com.server_optimize.networking.UdpSupportPacket.PROTOCOL_VERSION));
                }
            });
            clientTransport.sendControl(ReliableUdpTransport.CTRL_SYN, new byte[0]);
            // Fallback: if the probe is not answered in 2 s, stay on TCP.
            ch.eventLoop().schedule(() -> {
                if (!clientSynAcked) {
                    info("UDP probe timed out - staying on TCP");
                    stopClient();
                }
            }, 2, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("UDP transport start failed; staying on TCP", e);
            stopClient();
        }
    }

    /** Client side: the server confirmed over TCP; activate the tunnel now. */
    public static void activateClient() {
        if (clientTransport == null || clientConnection == null) {
            return;
        }
        installTunnel(clientConnection, clientTransport);
        UdpTransport.setClientNegotiated(true);
        info("UDP transport active");
    }

    public static void stopClient() {
        if (clientTransport != null) {
            clientTransport.stop();
            clientTransport = null;
        }
        if (clientChannel != null) {
            try {
                clientChannel.close().syncUninterruptibly();
            } catch (Exception ignored) {
            }
            clientChannel = null;
        }
        clientSynAcked = false;
    }

    // ------------------------------------------------------------------
    // Shared: tunnel install
    // ------------------------------------------------------------------

    private static void installTunnel(ServerPlayer player, ReliableUdpTransport transport) {
        try {
            Connection connection =
                ((ServerCommonPacketListenerAccessor) player.connection).serverOptimize$getConnection();
            installTunnel(connection, transport);
        } catch (Exception e) {
            LOGGER.warn("Failed to install UDP tunnel for {}", player.getGameProfile().name(), e);
        }
    }

    static void installTunnel(Connection connection, ReliableUdpTransport transport) {
        if (connection == null) return;
        try {
            Channel ch = ((ConnectionAccessor) connection).serverOptimize$getChannel();
            if (ch == null) return;
            ch.eventLoop().execute(() -> {
                if (ch.isActive() && ch.pipeline().get("server-optimize-udp") == null) {
                    ch.pipeline().addFirst("server-optimize-udp", new UdpTunnel(transport));
                    info("UDP tunnel installed");
                }
            });
        } catch (Exception e) {
            LOGGER.warn("Failed to install UDP tunnel", e);
        }
    }

    private static BiConsumer<ByteBuf, SocketAddress> serverSink() {
        return (buf, to) -> {
            if (serverChannel != null) serverChannel.writeAndFlush(new DatagramPacket(buf, (InetSocketAddress) to));
        };
    }

    private static BiConsumer<ByteBuf, SocketAddress> clientSink() {
        return (buf, to) -> {
            if (clientChannel != null) clientChannel.writeAndFlush(new DatagramPacket(buf, (InetSocketAddress) to));
        };
    }
}