package com.server_optimize.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Environment detection.
 * <p>
 * A dedicated dedicated-server runs as {@link EnvType#SERVER}. The client
 * (including the integrated singleplayer server inside it) runs as
 * {@link EnvType#CLIENT}. Data-integrity-critical optimizations (async
 * chunk save, merged writes, write-back cache, palette codec shortcuts,
 * CompoundTag map replacement, clean-save skipping) are only safe on a
 * dedicated server where the full vanilla write path is known-good; in the
 * client they are disabled and the vanilla write path is used, keeping
 * singleplayer worlds intact. Read-side accelerations (parallel region
 * reads, region-cache locking) stay active everywhere.
 */
public final class ServerOptimizeEnv {

    private ServerOptimizeEnv() {
    }

    /** True when running inside a client (integrated server included). */
    public static boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }
}
