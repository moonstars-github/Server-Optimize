package com.server_optimize.hopper;

/**
 * Attaches per-world optimization state directly to ServerLevel so the
 * per-tick hot path does not do a synchronized global map lookup.
 */
public interface ServerOptimizeLevelAccess {
    HopperTimeWheel serverOptimize$getHopperTimeWheel();

    void serverOptimize$setHopperTimeWheel(HopperTimeWheel wheel);
}
