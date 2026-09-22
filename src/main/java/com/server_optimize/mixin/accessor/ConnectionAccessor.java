package com.server_optimize.mixin.accessor;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the private {@code channel} field of {@link Connection}, used by
 * {@link com.server_optimize.networking.ZstdCodecSwitcher} to swap the
 * compression pipeline handler at play time.
 */
@Mixin(Connection.class)
public interface ConnectionAccessor {

    @Accessor("channel")
    Channel serverOptimize$getChannel();
}