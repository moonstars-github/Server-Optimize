package com.server_optimize.scheduling;

import com.server_optimize.config.ModConfig;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared per-player scheduling state for the movement aggregation mixins
 * (tweak.aggregateMovementScheduling).
 *
 * Plain helper class, deliberately NOT in the mixin package: classes inside
 * a defined mixin package (com.server_optimize.mixin.*) cannot be referenced
 * from transformed bytecode (IllegalClassLoadError). Only touched on the
 * server thread.
 */
public final class PlayerScheduleState {

    private PlayerScheduleState() {
    }

    public static final class State {
        public long lastSubmittedChunk; // position currently registered in DistanceManager, 0 = unknown
        public long lastSubmitMs;
        public long pendingChunk;       // newest deferred position, 0 = none
        public boolean owedRemove;      // a remove of lastSubmittedChunk is owed
        public double lastPosX;
        public double lastPosZ;
        public long lastPosMs;
        public double speed;            // m/s, position-delta based
        public boolean jump;            // position jump detected -> flush now
    }

    public static final Map<UUID, State> STATES = new HashMap<>();

    public static SectionPos sectionOf(long chunkKey) {
        return SectionPos.of(ChunkPos.getX(chunkKey), 0, ChunkPos.getZ(chunkKey));
    }

    /** Position-delta speed (m/s); sets the jump flag on teleports. */
    public static void measure(State st, ServerPlayer player) {
        long now = System.currentTimeMillis();
        net.minecraft.world.phys.Vec3 pos = player.position();
        if (st.lastPosMs != 0) {
            double dx = pos.x - st.lastPosX;
            double dz = pos.z - st.lastPosZ;
            long dt = now - st.lastPosMs;
            if (dt > 0) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 64.0) {
                    st.jump = true;
                }
                st.speed = d / dt * 1000.0;
            }
        }
        st.lastPosX = pos.x;
        st.lastPosZ = pos.z;
        st.lastPosMs = now;
    }

    public static boolean due(State st, ModConfig cfg) {
        if (st.jump) {
            st.jump = false;
            return true;
        }
        int interval;
        if (st.speed < cfg.chunk.movementSchedulingSpeedThreshold) {
            interval = 1;
        } else {
            interval = (int) (st.speed / cfg.chunk.movementSchedulingSpeedThreshold);
            interval = Math.max(2, Math.min(cfg.chunk.movementSchedulingMaxInterval, interval));
        }
        return System.currentTimeMillis() - st.lastSubmitMs >= interval * 50L;
    }

    /**
     * Apply the deferred swap atomically: remove the last submitted position
     * (if owed) and register the newest one. The state is temporarily removed
     * from the map while the real calls run, so DistanceManagerThrottleMixin
     * passes them through instead of re-deferring them.
     */
    public static void flush(DistanceManager dm, State st, ServerPlayer player, SectionPos newSec) {
        long newChunk = newSec.chunk().toLong();
        boolean needRemove = st.owedRemove && st.lastSubmittedChunk != 0 && st.lastSubmittedChunk != newChunk;
        boolean needAdd = st.lastSubmittedChunk != newChunk;
        if (needRemove || needAdd) {
            STATES.remove(player.getUUID());
            try {
                if (needRemove) {
                    dm.removePlayer(sectionOf(st.lastSubmittedChunk), player);
                }
                if (needAdd) {
                    dm.addPlayer(newSec, player);
                }
            } finally {
                STATES.put(player.getUUID(), st);
            }
        }
        st.owedRemove = false;
        st.lastSubmittedChunk = newChunk;
        st.pendingChunk = 0;
        st.lastSubmitMs = System.currentTimeMillis();
    }
}

