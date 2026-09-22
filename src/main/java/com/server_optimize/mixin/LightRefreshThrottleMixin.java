package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Light propagation throttling (chunk.noLightClone + lightSkipQueueThreshold).
 *
 * Vanilla LightEngine.runLightUpdates() processes every pending light change
 * batch and then publishes it via LayerLightSectionStorage.swapSectionMap(),
 * which clones the whole light data map (Long2ObjectOpenHashMap) so readers
 * see a stable snapshot. During fast chunk loading that clone was the
 * dominant allocation on the light thread (~0.4 GB/s on the production
 * server).
 *
 * The decision signal is the pending light queue size, not player speed:
 *  - queue > threshold (chunk-loading flood, typically from a fast flying
 *    player) -> the whole batch is skipped. The queues keep their entries,
 *    so nothing is lost; light simply converges a few ticks later and no
 *    full-map clone is made.
 *  - queue <= threshold (small block changes near a slow player) -> the
 *    batch always processes. One flying player no longer delays light
 *    updates for everyone.
 * Publishing is additionally capped at 20/s via the 50 ms floor, which
 * bounds the full-map clone rate when small updates trickle in.
 *
 * When the server is shutting down the throttle is bypassed so the light
 * queues drain and the worker threads can exit (without this, a pending
 * queue kept ThreadedLevelLightEngine's shutdown wait alive forever).
 */
@Mixin(LightEngine.class)
public abstract class LightRefreshThrottleMixin {

    @Shadow
    private it.unimi.dsi.fastutil.longs.LongOpenHashSet blockNodesToCheck;

    @Shadow
    private it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue decreaseQueue;

    @Shadow
    private it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue increaseQueue;

    @Unique
    private long serverOptimize$lastPublishMs;

    /**
     * Tick of the last light batch that was actually processed (not skipped).
     * The throttle may defer batches while the pending queue stays above the
     * threshold, but never for longer than
     * {@link #serverOptimize$MAX_DEFER_TICKS}: a continuous edit flood (e.g.
     * Axiom block moves pushing the queue past the threshold every tick) must
     * not stall light propagation forever. runLightUpdates is dispatched once
     * per tick by the light engine, so the call counter below is a tick proxy.
     */
    @Unique
    private long serverOptimize$lastProcessedTick;

    /** Light propagation may be deferred for at most this many ticks. */
    @Unique
    private static final int serverOptimize$MAX_DEFER_TICKS = 20;

    /** Call count of runLightUpdates (approximates the game tick). */
    @Unique
    private long serverOptimize$callCounter;

    @Unique
    private long serverOptimize$lastLog;

    @Unique
    private long serverOptimize$lastSpeedTick;

    @Unique
    private double serverOptimize$cachedSpeed;

    @Unique
    private java.util.UUID serverOptimize$lastPlayer;

    @Unique
    private net.minecraft.world.phys.Vec3 serverOptimize$lastPos;

    @Unique
    private long serverOptimize$lastPosTime;

    /**
     * Real traversal speed from position deltas (works for teleport-based
     * flight, where getDeltaMovement() stays near zero while the player
     * crosses 200 m/s). Used for the log line only; the throttle decision
     * is queue-size based.
     */
    @Unique
    private double serverOptimize$positionSpeed(net.minecraft.server.level.ServerPlayer p) {
        long now = System.currentTimeMillis();
        net.minecraft.world.phys.Vec3 pos = p.position();
        if (p.getUUID().equals(this.serverOptimize$lastPlayer)
            && this.serverOptimize$lastPos != null
            && this.serverOptimize$lastPosTime != 0L) {
            double dx = pos.x - this.serverOptimize$lastPos.x;
            double dz = pos.z - this.serverOptimize$lastPos.z;
            long dt = now - this.serverOptimize$lastPosTime;
            this.serverOptimize$lastPos = pos;
            this.serverOptimize$lastPosTime = now;
            if (dt > 0) {
                return Math.sqrt(dx * dx + dz * dz) / dt * 1000.0;
            }
            return 0.0;
        }
        this.serverOptimize$lastPlayer = p.getUUID();
        this.serverOptimize$lastPos = pos;
        this.serverOptimize$lastPosTime = now;
        return 0.0;
    }

    /** Pending light work count (all three engine queues). */
    @Unique
    private int serverOptimize$queueSize() {
        return this.blockNodesToCheck.size()
            + this.decreaseQueue.size()
            + this.increaseQueue.size();
    }

    @Inject(method = "runLightUpdates", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$throttleUpdates(CallbackInfoReturnable<Integer> cir) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.noLightClone) {
            return;
        }
        // Bypass during shutdown: the light queues must drain or the worker
        // executor never finishes and the server cannot stop.
        net.minecraft.server.MinecraftServer server = com.server_optimize.networking.SectionCulling.SERVER;
        if (server != null && !server.isRunning()) {
            return;
        }
        long now = System.currentTimeMillis();
        int queueSize = serverOptimize$queueSize();
        long tick = this.serverOptimize$callCounter++;
        // Hard cap: above this the queues must drain or they grow unbounded
        // while flying (only new work arrives, nothing is ever processed),
        // which pins the heap and cannot be GC'd. The cap bounds memory.
        if (queueSize >= 8192) {
            this.serverOptimize$lastPublishMs = now;
            this.serverOptimize$lastProcessedTick = tick;
        } else if ((queueSize > cfg.chunk.lightSkipQueueThreshold
            || now - this.serverOptimize$lastPublishMs < 50)
            // Never defer longer than MAX_DEFER_TICKS: a sustained edit flood
            // (queue stays above the threshold every tick) must still process
            // light periodically, otherwise Axiom-style block edits never
            // refresh light. First call (lastProcessedTick == 0) always runs.
            && (this.serverOptimize$lastProcessedTick == 0L
                || tick - this.serverOptimize$lastProcessedTick < serverOptimize$MAX_DEFER_TICKS)) {
            // Flood (defer the whole batch) or publish floor (bounds the
            // full-map clone rate). Queues keep their entries either way.
            cir.setReturnValue(0);
        } else {
            this.serverOptimize$lastPublishMs = now;
            this.serverOptimize$lastProcessedTick = tick;
        }
        serverOptimize$logThrottle(queueSize);
    }

    /**
     * Fastest moving player's horizontal speed in m/s (blocks/s).
     * Client: local player; server: fastest online player. Recomputed at
     * most once per tick. Log-only.
     */
    @Unique
    private double serverOptimize$currentSpeed() {
        long now = System.currentTimeMillis();
        if (now - this.serverOptimize$lastSpeedTick < 50) {
            return this.serverOptimize$cachedSpeed;
        }
        this.serverOptimize$lastSpeedTick = now;
        double speed = 0.0;
        try {
            if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.CLIENT) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player != null) {
                    net.minecraft.world.phys.Vec3 v = mc.player.getDeltaMovement();
                    speed = v.horizontalDistance() * 20.0;
                }
            } else {
                net.minecraft.server.MinecraftServer srv = com.server_optimize.networking.SectionCulling.SERVER;
                if (srv != null) {
                    for (net.minecraft.server.level.ServerPlayer p : srv.getPlayerList().getPlayers()) {
                        double s = serverOptimize$positionSpeed(p);
                        if (s > speed) {
                            speed = s;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        this.serverOptimize$cachedSpeed = speed;
        return speed;
    }

    @Unique
    private void serverOptimize$logThrottle(int queueSize) {
        long now = System.currentTimeMillis();
        if (now - this.serverOptimize$lastLog < 10000) {
            return;
        }
        this.serverOptimize$lastLog = now;
        if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.log.lightThrottle) {
            com.server_optimize.ServerOptimize.LOGGER.info(
                "light-throttle: speed={} m/s queue={} threshold={} (noLightClone={})",
                serverOptimize$currentSpeed(), queueSize,
                ModConfig.INSTANCE.chunk.lightSkipQueueThreshold,
                ModConfig.INSTANCE.chunk.noLightClone);
        }
    }
}
