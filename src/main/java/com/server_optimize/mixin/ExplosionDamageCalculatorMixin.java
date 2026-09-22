package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.config.ModConfig;
import com.server_optimize.util.ExplosionCaches;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Optional;

/**
 * Per-explosion block-resistance cache ([explosion] resistanceCache).
 * <p>
 * Vanilla {@code calculateExplodedPositions} casts ~1352 grid rays stepping
 * 0.3 blocks; every step calls {@code getBlockExplosionResistance} (an
 * Optional-allocating call through the damage calculator). Overlapping rays
 * re-evaluate the SAME block many times. This caches the resistance per block
 * (keyed by the blast center, per explosion) so each block pays the
 * resistance computation once per explosion. Semantics are identical: the
 * resistance of a block does not change during one explosion. Only non-empty
 * Optional results are cached (empty = air/fluid default, cheap anyway).
 */
@Mixin(ExplosionDamageCalculator.class)
public abstract class ExplosionDamageCalculatorMixin {

    @WrapMethod(method = "getBlockExplosionResistance(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Ljava/util/Optional;")
    private Optional<Float> serverOptimize$cachedResistance(Explosion explosion, BlockGetter level,
                                                            BlockPos pos, BlockState state,
                                                            FluidState fluid,
                                                            Operation<Optional<Float>> original) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.explosion.resistanceCache
            || !ExplosionCaches.active(BlockPos.containing(explosion.center()).asLong())) {
            return original.call(explosion, level, pos, state, fluid);
        }
        Optional<Float> cached = ExplosionCaches.getResistanceOptional(pos);
        if (cached != null) {
            return cached;
        }
        Optional<Float> v = original.call(explosion, level, pos, state, fluid);
        if (v.isPresent()) {
            ExplosionCaches.cacheResistance(pos, v.get().floatValue());
        }
        return v;
    }
}