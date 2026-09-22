package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.networking.ChunkPacketBuilder;
import com.server_optimize.util.ChunkIOCounters;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Off-thread chunk packet building (chunk.asyncPacketBuild).
 *
 * PlayerChunkSender.sendChunk() builds the level-chunk packet and sends it,
 * all on the server thread. The whole step is handed to
 * ChunkPacketBuilder's thread pool; the vanilla path is skipped entirely.
 */
@Mixin(net.minecraft.server.network.PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {

    @Inject(method = "sendChunk", at = @At("HEAD"), cancellable = true)
    private static void serverOptimize$asyncBuildAndSend(
        ServerGamePacketListenerImpl listener, ServerLevel level, LevelChunk chunk, CallbackInfo ci
    ) {
        // Count every chunk sent for load-rate tracking (always, regardless of config).
        ChunkIOCounters.onChunkSent();

        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.asyncPacketBuild) {
            return;
        }
        ServerPlayer player = listener.getPlayer();
        if (player == null) {
            return;
        }
        ChunkPacketBuilder.submit(player, level, chunk);
        ci.cancel();
    }
}
