package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.util.EntityPacketBatcher;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Batch entity position packets (net.entityPacketBatching).
 * <p>
 * Every server→client packet funnels through
 * {@link ServerCommonPacketListenerImpl#send(Packet)}. Position/teleport
 * packets (ClientboundMoveEntityPacket subclasses, EntityPositionSync) are
 * diverted into the per-listener queue of {@link EntityPacketBatcher}
 * instead of hitting the wire immediately; the queue is flushed once per
 * tick as a single vanilla ClientboundBundlePacket. Everything else (data,
 * effects, chunks, sounds...) still goes out immediately, preserving
 * vanilla semantics and ordering for non-position packets.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD"), cancellable = true)
    private void serverOptimize$batchEntityPosition(Packet<?> packet, CallbackInfo ci) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.net.entityPacketBatching) {
            return;
        }
        if (EntityPacketBatcher.isBatchable(packet)) {
            EntityPacketBatcher.offer((ServerCommonPacketListenerImpl) (Object) this, packet);
            ci.cancel();
        }
    }
}