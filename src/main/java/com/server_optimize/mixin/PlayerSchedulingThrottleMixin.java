package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.scheduling.PlayerScheduleState;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-player aggregation of chunk-scheduling updates (tweak.aggregateMovementScheduling).
 *
 * Vanilla ChunkMap.move(ServerPlayer) re-registers the player's chunk tickets
 * (DistanceManager.removePlayer + addPlayer, which re-mark the whole
 * view-distance graph as changed) on every chunk-section change. While
 * flying at 200+ m/s that fires every 1-2 ticks - a major server-thread
 * cost. Fast players instead submit their position every N ticks; slow
 * players keep the vanilla per-tick behavior. The actual deferral happens in
 * DistanceManagerThrottleMixin; this mixin provides the per-tick flush hook
 * (move() runs for every player every tick, catching the stopped-player
 * case) and drops the state on logout / dimension leave.
 */
@Mixin(ChunkMap.class)
public abstract class PlayerSchedulingThrottleMixin {

    @Unique
    private void serverOptimize$measureAndFlush(ServerPlayer player) {
        PlayerScheduleState.State st = PlayerScheduleState.STATES.computeIfAbsent(
            player.getUUID(), u -> new PlayerScheduleState.State());
        PlayerScheduleState.measure(st, player);
        if (st.pendingChunk == 0 && !st.owedRemove) {
            return;
        }
        if (st.pendingChunk == 0 && st.lastSubmittedChunk == 0) {
            st.owedRemove = false;
            st.lastSubmitMs = System.currentTimeMillis();
            return;
        }
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !PlayerScheduleState.due(st, cfg)) {
            return;
        }
        DistanceManager dm = ((ChunkMap) (Object) this).getDistanceManager();
        long target = st.pendingChunk != 0 ? st.pendingChunk : st.lastSubmittedChunk;
        PlayerScheduleState.flush(dm, st, player, PlayerScheduleState.sectionOf(target));
    }

    /**
     * Per-tick hook: flush a deferred position once the submission interval
     * elapsed (e.g. the player stopped moving, so no further section changes
     * will trigger the addPlayer redirect), or a remove that has no matching
     * add (spectator edge).
     */
    @Inject(method = "move", at = @At("HEAD"))
    private void serverOptimize$flushPending(ServerPlayer player, CallbackInfo ci) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.aggregateMovementScheduling) {
            return;
        }
        serverOptimize$measureAndFlush(player);
    }

    /**
     * Logout / dimension leave: the state is dropped first so the vanilla
     * removePlayer call below passes through and really removes the ticket.
     * If the player left with a deferred position (registered != current),
     * the registered one is removed explicitly first.
     */
    @Inject(method = "updatePlayerStatus", at = @At("HEAD"))
    private void serverOptimize$cleanup(ServerPlayer player, boolean flag, CallbackInfo ci) {
        if (flag) {
            return;
        }
        PlayerScheduleState.State st = PlayerScheduleState.STATES.remove(player.getUUID());
        if (st == null) {
            return;
        }
        long cur = net.minecraft.core.SectionPos.of(player).chunk().toLong();
        DistanceManager dm = ((ChunkMap) (Object) this).getDistanceManager();
        if (st.lastSubmittedChunk != 0 && st.lastSubmittedChunk != cur) {
            dm.removePlayer(PlayerScheduleState.sectionOf(st.lastSubmittedChunk), player);
        }
        if (st.pendingChunk != 0 && st.pendingChunk != cur) {
            dm.addPlayer(PlayerScheduleState.sectionOf(st.pendingChunk), player);
        }
    }
}
