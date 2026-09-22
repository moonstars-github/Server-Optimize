package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.config.ModConfig;
import com.server_optimize.util.FastManyHitTest;
import com.server_optimize.util.ProjectileRegionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * Fast many-hit projectile test (entity.projectile.fastArrowHitTest).
 * <p>
 * Intercepts {@code ProjectileUtil.getManyEntityHitResult} - the hot path of
 * arrow travel and of the melee/attack sweep - and replaces it with the
 * zero-allocation {@link FastManyHitTest}. When the section storage is not
 * reachable, or the config is off, the vanilla implementation runs.
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {

    @Inject(
        method = "getManyEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;FLnet/minecraft/world/level/ClipContext$Block;Z)Ljava/util/Collection;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void serverOptimize$fastManyHit(Level level, Entity entity,
                                                   Vec3 from, Vec3 to, AABB box,
                                                   Predicate<Entity> predicate, float margin,
                                                   ClipContext.Block blockClip, boolean checkContainment,
                                                   CallbackInfoReturnable<Collection<EntityHitResult>> cir) {
        if (ModConfig.INSTANCE == null || !ModConfig.INSTANCE.entity.projectile.fastArrowHitTest) {
            return;
        }
        Collection<EntityHitResult> result = FastManyHitTest.getManyEntityHitResult(
            level, entity, from, to, box, predicate, margin, blockClip, checkContainment);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    /**
     * Fast projectile block scan (entity.projectile.fastProjectileBlockScan).
     * Wraps {@code ProjectileUtil.getHitResultOnMoveVector} - the swept hit
     * test every projectile runs on its move tick (the 2-arg overload
     * delegates here with COLLIDER). When the entity is a Projectile, the
     * level is a ServerLevel and the config is on, the swept box is checked
     * against the per-tick region-state cache:
     * <ul>
     *   <li>SubSection (4^3) granularity by default - preferred;</li>
     *   <li>whole 16^3 Section granularity when the horizontal or vertical
     *       speed exceeds blockScanSubSectionSpeed (m/s);</li>
     *   <li>every covered region must be all-air to skip the block sweep
     *       (cross-region: one non-air region anywhere falls back to
     *       vanilla);</li>
     *   <li>the per-tick cache collapses repeated queries for one region into
     *       a single lookup.</li>
     * </ul>
     * When the whole swept volume is air, the block-collision part of the
     * vanilla hit test (which would traverse every block in the volume) is
     * skipped and only entity hits are computed - same outcome as the vanilla
     * path for an all-air volume (block MISS, nearest entity hit wins). On any
     * failure, or for non-projectile / client-side callers, the vanilla
     * implementation runs.
     */
    @WrapMethod(method = "getHitResultOnMoveVector(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;Lnet/minecraft/world/level/ClipContext$Block;)Lnet/minecraft/world/phys/HitResult;")
    private static HitResult serverOptimize$fastProjectileMoveHit(Entity entity, Predicate<Entity> predicate,
                                                                  ClipContext.Block blockClip,
                                                                  Operation<HitResult> original) {
        if (!(entity instanceof Projectile)) {
            return original.call(entity, predicate, blockClip);
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return original.call(entity, predicate, blockClip);
        }
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.entity.projectile.fastProjectileBlockScan) {
            return original.call(entity, predicate, blockClip);
        }
        try {
            Vec3 dm = entity.getDeltaMovement();
            double subSpeed = cfg.entity.projectile.blockScanSubSectionSpeed;
            boolean useSections = Math.abs(dm.x()) * 20.0 > subSpeed
                || Math.abs(dm.y()) * 20.0 > subSpeed
                || Math.abs(dm.z()) * 20.0 > subSpeed;
            Vec3 from = entity.position();
            Vec3 to = from.add(dm);
            AABB box = entity.getBoundingBox().expandTowards(dm).inflate(1.0);
            if (serverOptimize$sweptAllAir(level, box, useSections)) {
                // Vanilla block sweep would traverse this whole volume and miss
                // (all air): run only the entity part, same outcome.
                EntityHitResult ehr = ProjectileUtil.getEntityHitResult(
                    level, (Projectile) entity, from, to, box, predicate);
                if (ehr != null) {
                    return ehr;
                }
                return BlockHitResult.miss(to, Direction.getNearest(BlockPos.containing(to), null), BlockPos.containing(to));
            }
        } catch (Throwable t) {
            // Fall back to the vanilla sweep on any unexpected failure.
        }
        return original.call(entity, predicate, blockClip);
    }

    /**
     * True when every region (16^3 sections, or 4^3 sub-regions) covered by
     * {@code box} is all-air. Cross-region: the box's block span uses the
     * same floor/ceil convention as the vanilla clip traversal, so partial
     * edge blocks are included; any non-air region anywhere aborts with
     * false.
     */
    @Unique
    private static boolean serverOptimize$sweptAllAir(ServerLevel level, AABB box, boolean useSections) {
        int minBX = Mth.floor(box.minX);
        int maxBX = Mth.ceil(box.maxX) - 1;
        int minBY = Mth.floor(box.minY);
        int maxBY = Mth.ceil(box.maxY) - 1;
        int minBZ = Mth.floor(box.minZ);
        int maxBZ = Mth.ceil(box.maxZ) - 1;
        if (minBX > maxBX || minBY > maxBY || minBZ > maxBZ) {
            return true; // empty span: nothing to collide with
        }
        int minSX = SectionPos.blockToSectionCoord(minBX);
        int maxSX = SectionPos.blockToSectionCoord(maxBX);
        int minSY = SectionPos.blockToSectionCoord(minBY);
        int maxSY = SectionPos.blockToSectionCoord(maxBY);
        int minSZ = SectionPos.blockToSectionCoord(minBZ);
        int maxSZ = SectionPos.blockToSectionCoord(maxBZ);

        for (int sx = minSX; sx <= maxSX; sx++) {
            int secOriginX = sx << 4;
            int lminX = Math.max(0, minBX - secOriginX);
            int lmaxX = Math.min(15, maxBX - secOriginX);
            for (int sy = minSY; sy <= maxSY; sy++) {
                int secOriginY = sy << 4;
                int lminY = Math.max(0, minBY - secOriginY);
                int lmaxY = Math.min(15, maxBY - secOriginY);
                for (int sz = minSZ; sz <= maxSZ; sz++) {
                    if (useSections) {
                        if (!ProjectileRegionCache.isSectionAllAir(level, sx, sy, sz)) {
                            return false;
                        }
                        continue;
                    }
                    int secOriginZ = sz << 4;
                    int lminZ = Math.max(0, minBZ - secOriginZ);
                    int lmaxZ = Math.min(15, maxBZ - secOriginZ);
                    // SubSection granularity: only the 4^3 sub-regions the box
                    // actually touches in this section.
                    for (int l2x = lminX >> 2; l2x <= lmaxX >> 2; l2x++) {
                        for (int l2y = lminY >> 2; l2y <= lmaxY >> 2; l2y++) {
                            for (int l2z = lminZ >> 2; l2z <= lmaxZ >> 2; l2z++) {
                                if (!ProjectileRegionCache.isSubSectionAllAir(level, sx, sy, sz, l2x, l2y, l2z)) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
    }
}