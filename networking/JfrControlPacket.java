package com.server_optimize.networking;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → client: JFR control packet.
 */
public final class JfrControlPacket {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("server-optimize", "jfr_control");

    /** Server → client: tell local JVM to start or stop JFR recording. */
    public record JfrControl(String action) implements CustomPacketPayload {
        @Override public net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Packet type — used for registration and send(). */
    public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<JfrControl> TYPE = new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(ID);

    /** Packet codec — encodes action as varInt: 1=start, 0=stop. */
    public static final StreamCodec<RegistryFriendlyByteBuf, JfrControl> CODEC = new StreamCodec<>() {
        @Override
        public JfrControl decode(RegistryFriendlyByteBuf buf) {
            int mode = buf.readVarInt();
            return switch (mode) {
                case 1 -> new JfrControl("start");
                default -> new JfrControl("stop");
            };
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, JfrControl payload) {
            int mode = "start".equals(payload.action()) ? 1 : 0;
            buf.writeVarInt(mode);
        }
    };

    /** Register the server→client packet. */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
