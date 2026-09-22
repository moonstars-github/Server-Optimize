package com.server_optimize.networking;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * UDP transport capability negotiation (net.useUDP).
 * <p>
 * Phase 1 of the UDP transport: both sides announce UDP support during the
 * TCP config phase. The client sends {@link UdpHello}; the server replies
 * with {@link UdpHelloAck} carrying the UDP port and the negotiated state.
 * UDP is used ONLY when both sides run this mod with UDP enabled; otherwise
 * (client without the mod / disabled / version mismatch) the connection
 * stays on the vanilla TCP transport.
 * <p>
 * The reliable UDP frame layer (phase 2) is not implemented yet, so the
 * handshake only records capability; {@code /serveroptimize status net}
 * keeps reporting {@code connect: vanilla} until the transport is wired.
 */
public final class UdpSupportPacket {

    public static final int PROTOCOL_VERSION = 1;

    public static final Identifier HELLO_ID = Identifier.fromNamespaceAndPath("server-optimize", "udp_hello");
    public static final Identifier HELLO_ACK_ID = Identifier.fromNamespaceAndPath("server-optimize", "udp_hello_ack");
    public static final Identifier READY_ID = Identifier.fromNamespaceAndPath("server-optimize", "udp_ready");
    public static final Identifier READY_ACK_ID = Identifier.fromNamespaceAndPath("server-optimize", "udp_ready_ack");

    public static final CustomPacketPayload.Type<UdpHello> HELLO_TYPE = new CustomPacketPayload.Type<>(HELLO_ID);
    public static final CustomPacketPayload.Type<UdpHelloAck> HELLO_ACK_TYPE = new CustomPacketPayload.Type<>(HELLO_ACK_ID);
    public static final CustomPacketPayload.Type<UdpReady> READY_TYPE = new CustomPacketPayload.Type<>(READY_ID);
    public static final CustomPacketPayload.Type<UdpReadyAck> READY_ACK_TYPE = new CustomPacketPayload.Type<>(READY_ACK_ID);

    /** C2S: client announces UDP support. */
    public record UdpHello(int version) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, UdpHello> CODEC =
            StreamCodec.ofMember(UdpHello::write, UdpHello::read);

        private static UdpHello read(RegistryFriendlyByteBuf buf) {
            return new UdpHello(buf.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(this.version);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return HELLO_TYPE;
        }
    }

    /** S2C: server replies with UDP port + support state + session key/token. */
    public record UdpHelloAck(boolean supported, int udpPort, int version, byte[] sessionKey, long sessionToken)
            implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, UdpHelloAck> CODEC =
            StreamCodec.ofMember(UdpHelloAck::write, UdpHelloAck::read);

        private static UdpHelloAck read(RegistryFriendlyByteBuf buf) {
            boolean supported = buf.readBoolean();
            int udpPort = buf.readVarInt();
            int version = buf.readVarInt();
            byte[] key = null;
            long token = 0;
            if (supported) {
                int keyLen = buf.readVarInt();
                key = new byte[Math.min(keyLen, 32)];
                buf.readBytes(key);
                token = buf.readLong();
            }
            return new UdpHelloAck(supported, udpPort, version, key, token);
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBoolean(this.supported);
            buf.writeVarInt(this.udpPort);
            buf.writeVarInt(this.version);
            if (this.supported && this.sessionKey != null) {
                buf.writeVarInt(this.sessionKey.length);
                buf.writeBytes(this.sessionKey);
                buf.writeLong(this.sessionToken);
            } else if (this.supported) {
                buf.writeVarInt(0);
                buf.writeLong(0);
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return HELLO_ACK_TYPE;
        }
    }

    /** C2S (TCP): client confirms UDP connectivity and requests the transport switch. */
    public record UdpReady(int version) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, UdpReady> CODEC =
            StreamCodec.ofMember(UdpReady::write, UdpReady::read);

        private static UdpReady read(RegistryFriendlyByteBuf buf) {
            return new UdpReady(buf.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(this.version);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return READY_TYPE;
        }
    }

    /** S2C (TCP): server confirms the switch; client activates its tunnel now. */
    public record UdpReadyAck(boolean ok) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, UdpReadyAck> CODEC =
            StreamCodec.ofMember(UdpReadyAck::write, UdpReadyAck::read);

        private static UdpReadyAck read(RegistryFriendlyByteBuf buf) {
            return new UdpReadyAck(buf.readBoolean());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBoolean(this.ok);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return READY_ACK_TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(HELLO_TYPE, UdpHello.CODEC);
        PayloadTypeRegistry.playS2C().register(HELLO_ACK_TYPE, UdpHelloAck.CODEC);
        PayloadTypeRegistry.playC2S().register(READY_TYPE, UdpReady.CODEC);
        PayloadTypeRegistry.playS2C().register(READY_ACK_TYPE, UdpReadyAck.CODEC);
    }

    private UdpSupportPacket() {
    }
}
