package com.server_optimize.networking;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -> server hello: declares that this client understands
 * server-optimize compressed chunk packets. Sent when
 * chunk.sectionCulling is enabled (server-preference rule: the client
 * always decodes compressed chunks if the server sends them, but only
 * announces support when its own config wants the optimization).
 */
public final class CompressionHello {
    public static final Identifier HELLO_ID = Identifier.fromNamespaceAndPath("server-optimize", "hello");
    public static final CustomPacketPayload.Type<HelloPayload> TYPE = new CustomPacketPayload.Type<>(HELLO_ID);

    private CompressionHello() {
    }

    public record HelloPayload(int version) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> CODEC = new StreamCodec<>() {
        @Override
        public HelloPayload decode(RegistryFriendlyByteBuf buf) {
            return new HelloPayload(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, HelloPayload value) {
            buf.writeVarInt(value.version());
        }
    };

    public static final int PROTOCOL_VERSION = 1;

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
