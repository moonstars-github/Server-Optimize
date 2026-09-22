package com.server_optimize.networking.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCounted;

/**
 * Migrates an established Minecraft connection from TCP to the reliable UDP
 * transport without touching any of MC's codec handlers.
 * <p>
 * Added at the HEAD of the existing pipeline:
 * <ul>
 *   <li>Outbound: intercepts the final wire bytes (after the frame /
 *       compression / encryption codecs) and hands them to the reliable UDP
 *       transport instead of writing them to the TCP socket.</li>
 *   <li>Inbound: drains the (now idle) TCP socket and injects frames
 *       reassembled by the UDP transport back into the pipeline, so the
 *       frame / decryption / decompression codecs run unchanged.</li>
 * </ul>
 */
public final class UdpTunnel extends ChannelDuplexHandler {

    private final ReliableUdpTransport transport;
    private ChannelHandlerContext ctx;
    /** True once the first UDP frame arrived: from then on the (idle) TCP
     *  socket is drained instead of forwarded, so late TCP bytes never reach
     *  the frame decoder twice. */
    private volatile boolean migrated = false;

    public UdpTunnel(ReliableUdpTransport transport) {
        this.transport = transport;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        // Arm the transport's app sink: delivered frames now flow into the
        // pipeline (on this channel's event loop) instead of being buffered.
        transport.setAppSink(bytes -> ctx.executor().execute(() -> {
            migrated = true;
            if (ctx.channel().isActive()) {
                ctx.fireChannelRead(Unpooled.wrappedBuffer(bytes));
            }
        }));
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf buf) {
            byte[] wire = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), wire);
            buf.release();
            transport.sendApp(wire);
            promise.setSuccess();
            return;
        }
        super.write(ctx, msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // The peer stopped using TCP once its first UDP frame arrived; before
        // that, forward TCP bytes normally (the peer still sends on TCP).
        if (!migrated) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (msg instanceof ReferenceCounted rc) {
            rc.release();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        transport.stop();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        transport.stop();
        ctx.fireChannelInactive();
    }
}