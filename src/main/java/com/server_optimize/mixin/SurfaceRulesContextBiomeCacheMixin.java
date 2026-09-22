package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.server_optimize.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/**
 * Surface-build biome cache per column ([chunk.generate] genSurfaceBiomeColumnCache).
 *
 * <p>Vanilla rebuilds a memoized biome supplier (and its lambda) on every {@code updateY},
 * so a surface-rule biome condition evaluated at several y levels of one column re-samples
 * the biome source once per y. The vanilla overworld / nether / end biome sources are 2D
 * (the y is ignored), so one sample per column is equivalent. Besides the redundant samples,
 * the per-y {@code Suppliers.memoize} wrapper and its lambda showed up as two of the largest
 * worldgen allocation sites in the generation JFR (hundreds of allocation samples across the
 * workers); the column cache allocates one lambda + one cached value per column instead.
 * A 3D biome source from another mod would read the column's y=0 biome instead of its own
 * per-y values (the accepted divergence; the option can be turned off).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public abstract class SurfaceRulesContextBiomeCacheMixin {

    @Shadow
    @Final
    private Function<BlockPos, Holder<Biome>> biomeGetter;

    @Unique
    private Holder<Biome> serverOptimize$columnBiome;

    /** Refreshes the per-column biome; the 2D vanilla biome sources ignore the y. */
    @Inject(method = "updateXZ(II)V", at = @At("HEAD"))
    private void serverOptimize$cacheColumnBiome(int x, int z, CallbackInfo ci) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg != null && cfg.chunk.genSurfaceBiomeColumnCache) {
            this.serverOptimize$columnBiome = this.biomeGetter.apply(new BlockPos(x, 0, z));
        }
    }

    /** Replaces the per-y memoized supplier (and its lambda) with the column's cached biome. */
    @ModifyExpressionValue(
        method = "updateY(IIIIII)V",
        at = @At(value = "INVOKE",
            target = "Lcom/google/common/base/Suppliers;memoize(Lcom/google/common/base/Supplier;)Lcom/google/common/base/Supplier;")
    )
    private com.google.common.base.Supplier<Holder<Biome>> serverOptimize$columnBiomeSupplier(
        com.google.common.base.Supplier<Holder<Biome>> vanillaMemoized) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.genSurfaceBiomeColumnCache) {
            return vanillaMemoized;
        }
        return () -> this.serverOptimize$columnBiome;
    }
}