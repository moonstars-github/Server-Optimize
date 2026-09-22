package com.server_optimize.mixin.accessor;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the protected {@code connection} field of
 * {@link ServerCommonPacketListenerImpl} (the netty {@link Connection} backing
 * a player's session), used by
 * {@link com.server_optimize.networking.ZstdCodecSwitcher}.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerAccessor {

    @Accessor("connection")
    Connection serverOptimize$getConnection();
}