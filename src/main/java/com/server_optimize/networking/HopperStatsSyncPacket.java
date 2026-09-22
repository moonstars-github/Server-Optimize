package com.server_optimize.networking;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * F3 hopper-debug synchronization (dedicated server only).
 * <p>
 * The client announces itself with {@link HopperStatsHello} on JOIN; the
 * server replies by periodically pushing a {@link HopperStatsPayload} holding
 * the whole-server hopper wheel stats (aggregated across every loaded
 * dimension). No extra config toggles this: it activates whenever both sides
 * run the mod. A client without the mod never sends the hello, so it never
 * receives the payload and is unaffected.
 * <p>
 * Singleplayer keeps using the local integrated server directly; this channel
 * is only used when a client connects to a dedicated server.
 */
public final class HopperStatsSyncPacket {

    public static final int PROTOCOL_VERSION = 1;

    public static final Identifier HELLO_ID = Identifier.fromNamespaceAndPath("server-optimize", "hopper_stats_hello");
    public static final Identifier PAYLOAD_ID = Identifier.fromNamespaceAndPath("server-optimize", "hopper_stats");

    public static final CustomPacketPayload.Type<HopperStatsHello> HELLO_TYPE = new CustomPacketPayload.Type<>(HELLO_ID);
    public static final CustomPacketPayload.Type<HopperStatsPayload> PAYLOAD_TYPE = new CustomPacketPayload.Type<>(PAYLOAD_ID);

    /** C2S: client announces the mod is present. */
    public record HopperStatsHello(int version) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, HopperStatsHello> CODEC =
            StreamCodec.ofMember(HopperStatsHello::write, HopperStatsHello::read);

        private static HopperStatsHello read(RegistryFriendlyByteBuf buf) {
            return new HopperStatsHello(buf.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(this.version);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return HELLO_TYPE;
        }
    }

    /** S2C: whole-server hopper wheel stats snapshot. */
    public record HopperStatsPayload(
            int[] bucketCounts, int[] checkedByBucket, int[] wokeByBucket, int[] sleptByBucket,
            int sleepingCount, int currentBucket, int totalActive,
            int checkedCount, int wokeCount, int sleptCount) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, HopperStatsPayload> CODEC =
            StreamCodec.ofMember(HopperStatsPayload::write, HopperStatsPayload::read);

        private static HopperStatsPayload read(RegistryFriendlyByteBuf buf) {
            return new HopperStatsPayload(
                readInts(buf), readInts(buf), readInts(buf), readInts(buf),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            writeInts(buf, this.bucketCounts);
            writeInts(buf, this.checkedByBucket);
            writeInts(buf, this.wokeByBucket);
            writeInts(buf, this.sleptByBucket);
            buf.writeVarInt(this.sleepingCount);
            buf.writeVarInt(this.currentBucket);
            buf.writeVarInt(this.totalActive);
            buf.writeVarInt(this.checkedCount);
            buf.writeVarInt(this.wokeCount);
            buf.writeVarInt(this.sleptCount);
        }

        private static int[] readInts(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            int[] a = new int[n];
            for (int i = 0; i < n; i++) {
                a[i] = buf.readVarInt();
            }
            return a;
        }

        private static void writeInts(FriendlyByteBuf buf, int[] a) {
            buf.writeVarInt(a.length);
            for (int v : a) {
                buf.writeVarInt(v);
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PAYLOAD_TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(HELLO_TYPE, HopperStatsHello.CODEC);
        PayloadTypeRegistry.playS2C().register(PAYLOAD_TYPE, HopperStatsPayload.CODEC);
    }

    private HopperStatsSyncPacket() {
    }
}
