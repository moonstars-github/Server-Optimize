package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.config.ModConfig;
import com.server_optimize.util.ExplosionCaches;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity damage-density block cache ([explosion] densityBlockCache).
 * <p>
 * Applied ON TOP of the vanilla grid-ray algorithm - destruction behavior is
 * untouched (the vanilla funnel-shaped blast is preserved). Vanilla
 * {@code ServerExplosion.getSeenPercent} casts one clip ray per entity per
 * sampling point; this caches the result per BLOCK of the entity (keyed by the
 * blast center, per explosion via {@link ExplosionCaches}), so dense crowds
 * on the same blocks share one ray instead of paying one each. Slight
 * approximation: all entities on one block share the density of the first one
 * sampled, which only affects the boundary of shielded areas.
 * <p>
 * Also brackets the per-explosion cache lifecycle (clip density + resistance
 * + block state) around {@code explode()}.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

    /**
     * Entity damage density: per-block cache over the vanilla ray. First
     * entity on a block pays the vanilla ray; the rest of the block shares it.
     */
    @WrapMethod(method = "getSeenPercent(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;)F")
    private static float serverOptimize$cachedDensity(Vec3 center, Entity entity, Operation<Float> original) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.explosion.densityBlockCache) {
            return original.call(center, entity);
        }
        long centerKey = BlockPos.containing(center).asLong();
        if (!ExplosionCaches.active(centerKey)) {
            return original.call(center, entity);
        }
        BlockPos blockPos = entity.blockPosition();
        if (ExplosionCaches.hasClip(blockPos)) {
            return ExplosionCaches.clipOr(blockPos, 0.0f);
        }
        float v = original.call(center, entity);
        ExplosionCaches.cacheClip(blockPos, v);
        return v;
    }

    /** Start the per-explosion caches (clip + resistance + block state). */
    @Inject(method = "explode()I", at = @At("HEAD"))
    private void serverOptimize$initExplosionState(CallbackInfoReturnable<Integer> cir) {
        ServerExplosion self = (ServerExplosion) (Object) this;
        ExplosionCaches.begin(BlockPos.containing(self.center()).asLong(),
            self.center().x, self.center().y, self.center().z, self.radius());
    }

    /** Release the per-explosion caches (no stale/retained state). */
    @Inject(method = "explode()I", at = @At("RETURN"))
    private void serverOptimize$clearExplosionState(CallbackInfoReturnable<Integer> cir) {
        ExplosionCaches.end();
    }
}