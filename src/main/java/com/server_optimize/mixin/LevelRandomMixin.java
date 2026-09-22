package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.server_optimize.thread.RegionAwareRandomSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Installs the thread-aware {@link RegionAwareRandomSource} as {@code Level.random}
 * ([thread.multithread.regionbased]): every {@code RandomSource.create()} in the Level
 * constructor (the position-counter seed and the {@code random} field) is wrapped, so any
 * later {@code level.random} access on a region-apply worker resolves to that worker's own
 * RNG instead of the single-thread-asserted vanilla source, and the server thread keeps the
 * exact vanilla sequence.
 */
@Mixin(Level.class)
public abstract class LevelRandomMixin {

    @ModifyExpressionValue(
        method = "<init>",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;")
    )
    private static RandomSource serverOptimize$wrapLevelRandom(RandomSource created) {
        return new RegionAwareRandomSource(created);
    }
}