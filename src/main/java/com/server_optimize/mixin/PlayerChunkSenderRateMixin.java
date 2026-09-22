package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerChunkSender send rate boost (net.chunkSendMaxPerTick).
 * <p>
 * Vanilla {@link PlayerChunkSender} processes chunks at a low rate
 * (START=4, max ~8-20 per tick) to avoid overwhelming slow clients.
 * This limit is appropriate for remote players, but for localhost
 * connections (127.0.0.1) or fast networks it artificially caps the
 * chunk loading speed.
 * <p>
 * With this mixin, the {@code desiredChunksPerTick} field is raised to
 * the configured value at the start of each {@code sendNextChunks} call,
 * so more chunks are sent per tick. The packets are standard
 * {@code ClientboundLevelChunkWithLightPacket} -  100% compatible with
 * vanilla clients, no handshake needed.
 * <p>
 * The client's own backpressure ({@code chunk_batch_received}) still
 * limits the rate if the client falls behind, so this is safe.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderRateMixin {

    @Shadow
    private float desiredChunksPerTick;

    @Shadow
    private float batchQuota;

    @Shadow
    private int unacknowledgedBatches;

    @Shadow
    private int maxUnacknowledgedBatches;

    /**
     * Boost the per-tick chunk send rate at the start of each send cycle.
     */
    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void serverOptimize$boostRate(ServerPlayer player, CallbackInfo ci) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || cfg.net.chunkSendMaxPerTick <= 0) {
            return;
        }
        // Raise the desired rate to the configured value.
        // The vanilla ACCELERATE/DECELERATE mechanism in
        // onChunkBatchReceivedByClient can still adjust this,
        // but we re-apply at the start of every tick so the
        // configured rate dominates.
        int target = cfg.net.chunkSendMaxPerTick;
        if (this.desiredChunksPerTick < target) {
            this.desiredChunksPerTick = target;
        }
        // Also increase the max unacknowledged batches quota so the
        // higher rate isn't blocked by pending client confirmations.
        if (this.maxUnacknowledgedBatches < 8) {
            this.maxUnacknowledgedBatches = 8;
        }
    }
}