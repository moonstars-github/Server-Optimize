package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.networking.SectionCulling;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla chunk-with-light packets with the culled variant for
 * clients that announced support.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerPacketSendMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$replaceChunkPacket(Packet<?> packet, CallbackInfo ci) {
        if (ModConfig.INSTANCE == null || !ModConfig.INSTANCE.chunk.sectionCulling) {
            return;
        }
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket)) {
            return;
        }
        if (!ServerGamePacketListenerImpl.class.isInstance(this)) {
            return;
        }
        ServerGamePacketListenerImpl gameListener = (ServerGamePacketListenerImpl) (Object) this;
        if (!SectionCulling.isSupported(gameListener.player)) {
            return;
        }
        SectionCulling.CulledChunkPayload payload = SectionCulling.getCached(chunkPacket.getX(), chunkPacket.getZ());
        if (payload == null) {
            return; // cache miss: fall back to vanilla packet
        }
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(gameListener.player, payload);
        ci.cancel();
    }
}
