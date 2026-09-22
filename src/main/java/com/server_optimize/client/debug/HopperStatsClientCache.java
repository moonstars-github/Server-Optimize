package com.server_optimize.client.debug;

import com.server_optimize.hopper.HopperTimeWheel;
import com.server_optimize.networking.HopperStatsSyncPacket;

/**
 * Client-side cache of the whole-server hopper stats pushed by a dedicated
 * server. Filled by the {@link HopperStatsSyncPacket.HopperStatsPayload}
 * receiver, cleared on JOIN (no stale data from a previous server) and on
 * DISCONNECT. Singleplayer never uses this path.
 */
public final class HopperStatsClientCache {
    private static volatile HopperTimeWheel.DebugStats latest;

    public static void update(HopperStatsSyncPacket.HopperStatsPayload payload) {
        latest = HopperTimeWheel.DebugStats.from(
            payload.bucketCounts(), payload.checkedByBucket(), payload.wokeByBucket(),
            payload.sleptByBucket(), payload.sleepingCount(), payload.currentBucket(),
            payload.totalActive(), payload.checkedCount(), payload.wokeCount(), payload.sleptCount());
    }

    public static HopperTimeWheel.DebugStats latest() {
        return latest;
    }

    public static void clear() {
        latest = null;
    }

    private HopperStatsClientCache() {
    }
}
