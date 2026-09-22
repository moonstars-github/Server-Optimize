package com.server_optimize.mixin.accessor;

import net.minecraft.TracingExecutor;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Static-field accessor for Util's background executor. The worker-pool
 * rebuild ([thread].pinWorkers) swaps Util.BACKGROUND_EXECUTOR at server
 * start; mixin maps the field name at runtime (plain reflection with mojmap
 * names fails on the remapped production jar). Only the GETTER is usable -
 * a setter cannot write a static final field (JVM rejects putstatic from
 * another class), so the write goes through sun.misc.Unsafe.
 */
@Mixin(Util.class)
public interface UtilAccessor {

    @Accessor("BACKGROUND_EXECUTOR")
    static TracingExecutor serverOptimize$getBackgroundExecutor() {
        throw new AssertionError();
    }
}