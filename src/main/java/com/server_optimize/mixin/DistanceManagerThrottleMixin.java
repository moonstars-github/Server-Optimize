package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.scheduling.PlayerScheduleState;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Deferred player-ticket updates (tweak.aggregateMovementScheduling).
 *
 * DistanceManager.addPlayer/removePlayer are where the player's chunk
 * tickets are actually re-registered (PLAYER_SIMULATION ticket + both
 * distance trackers) and where the per-tick scheduling recompute is fed.
 * This mixin targets the public base class, so no private-nested-type
 * problem arises (unlike redirecting the calls inside ChunkMap.move).
 *
 * Semantics:
 *  - removePlayer with tracked state: always deferred (the addPlayer call of
 *    the same vanilla branch batches with it); the state's lastSubmittedChunk
 *    is seeded from the removed section on first use.
 *  - addPlayer with tracked state: when the per-player submission interval
 *    elapsed (slow players: essentially every section change, i.e. vanilla),
 *    the batched swap is flushed atomically (remove last submitted, register
 *    the newest) via PlayerScheduleState.flush; otherwise only the newest
 *    deferred position is recorded and the vanilla call is cancelled.
 *  - No state (login, logout after cleanup, flush-in-progress): pass through.
 *  - Teleports (position jump > 64 blocks) always flush immediately.
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerThrottleMixin {

    @Inject(method = "addPlayer", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$throttleAdd(SectionPos sec, ServerPlayer player, CallbackInfo ci) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.aggregateMovementScheduling) {
            return;
        }
        PlayerScheduleState.State st = PlayerScheduleState.STATES.get(player.getUUID());
        if (st == null) {
            return; // login / logout cleanup / flush-in-progress -> vanilla
        }
        PlayerScheduleState.measure(st, player);
        if (PlayerScheduleState.due(st, cfg)) {
            PlayerScheduleState.flush((DistanceManager) (Object) this, st, player, sec);
            ci.cancel();
        } else {
            st.pendingChunk = sec.chunk().toLong();
            ci.cancel();
        }
    }

    @Inject(method = "removePlayer", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$throttleRemove(SectionPos sec, ServerPlayer player, CallbackInfo ci) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.aggregateMovementScheduling) {
            return;
        }
        PlayerScheduleState.State st = PlayerScheduleState.STATES.get(player.getUUID());
        if (st == null) {
            return; // vanilla
        }
        if (st.lastSubmittedChunk == 0) {
            st.lastSubmittedChunk = sec.chunk().toLong();
        }
        st.owedRemove = true;
        ci.cancel();
    }
}
