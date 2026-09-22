package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.config.ModConfig;
import com.server_optimize.util.ExplosionVolume;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Air-volume fast path for explosions ([explosion] airVolumeFastPath, "P1").
 * <p>
 * Both ray phases of a vanilla {@code ServerExplosion} only read the world
 * through block-state / fluid-state lookups. When everything a phase can touch
 * is air, those lookups all answer the same thing, so the phase is computable
 * without touching a single block:
 * <ul>
 *   <li><b>getSeenPercent</b> (one ray grid per entity) only calls
 *       {@code level.clip(..., Block.COLLIDER, Fluid.NONE, ...)} and counts the
 *       rays that MISS - it never consults the damage calculator and consumes
 *       no RNG. With no block along the rays, every ray misses and the return
 *       value is exactly {@code 1.0F}.</li>
 *   <li><b>calculateExplodedPositions</b> (the 1352 shell rays building the
 *       destruction set) is rebuilt by
 *       {@link ExplosionVolume#collectAirPositions}: identical ray directions,
 *       identical float power roll (one {@code random.nextFloat()} per shell
 *       ray, same order), identical double stepping and world-bound checks. For
 *       air the resistance is {@code Optional.empty()} in every vanilla
 *       calculator, so the power only loses the fixed per-step constant, while
 *       the destroy decision still comes from the real calculator with the air
 *       state - subclass hooks such as {@code Entity.shouldBlockExplode} behave
 *       identically.</li>
 * </ul>
 * Everything else - any explosion that touches a non-air block - keeps running
 * the vanilla (or Lithium's) code: the pre-test is conservative and falls back
 * on unloaded chunks, missing sections, any non-air palette entry or a custom
 * damage calculator.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionFastPathMixin {

    private static final BlockState SERVER_OPTIMIZE$AIR = Blocks.AIR.defaultBlockState();

    /** One-off log so users can confirm the fast path is live. */
    private static final java.util.concurrent.atomic.AtomicBoolean SERVER_OPTIMIZE$ANNOUNCED =
        new java.util.concurrent.atomic.AtomicBoolean();

    private static void serverOptimize$announce() {
        if (SERVER_OPTIMIZE$ANNOUNCED.compareAndSet(false, true)) {
            com.server_optimize.ServerOptimize.LOGGER.info(
                "explosion air-volume fast path active ([explosion] airVolumeFastPath)");
        }
    }

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private Vec3 center;

    @Shadow
    @Final
    private float radius;

    @Shadow
    @Final
    private ExplosionDamageCalculator damageCalculator;

    /** Entity ray phase: no block can be hit, so every ray misses -> 1.0F. */
    @WrapMethod(method = "getSeenPercent(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;)F")
    private static float serverOptimize$airVolumeSeenPercent(Vec3 center, Entity entity,
                                                             Operation<Float> original) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.explosion.airVolumeFastPath
            || !(entity.level() instanceof ServerLevel serverLevel)) {
            return original.call(center, entity);
        }
        AABB box = entity.getBoundingBox();
        if (serverOptimize$isAllAir(serverLevel,
            Math.min(box.minX, center.x), Math.min(box.minY, center.y), Math.min(box.minZ, center.z),
            Math.max(box.maxX, center.x), Math.max(box.maxY, center.y), Math.max(box.maxZ, center.z))) {
            ExplosionVolume.countSeenPercentSkip();
            serverOptimize$announce();
            return 1.0F;
        }
        return original.call(center, entity);
    }

    /** Block ray phase: all-air sweep -> analytic rebuild of the position set. */
    @WrapMethod(method = "calculateExplodedPositions")
    private List<BlockPos> serverOptimize$airVolumePositions(Operation<List<BlockPos>> original) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.explosion.airVolumeFastPath
            || !serverOptimize$vanillaAirBehaviour(this.damageCalculator)) {
            return original.call();
        }
        // A ray walks at most power / 0.225 * 0.3 = power * 4/3 blocks and
        // power <= radius * 1.3, so this box covers every visited position.
        double reach = (double) this.radius * 1.3D * (4.0D / 3.0D) + 1.0D;
        if (!serverOptimize$isAllAir(this.level,
            this.center.x - reach, this.center.y - reach, this.center.z - reach,
            this.center.x + reach, this.center.y + reach, this.center.z + reach)) {
            return original.call();
        }
        ExplosionVolume.countPositionRebuild();
        serverOptimize$announce();
        ServerExplosion self = (ServerExplosion) (Object) this;
        Set<BlockPos> positions = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        ExplosionVolume.collectAirPositions(this.center.x, this.center.y, this.center.z, this.radius,
            this.level.random::nextFloat,
            (x, y, z) -> {
                cursor.set(x, y, z);
                return this.level.isInWorldBounds(cursor);
            },
            (x, y, z, remaining) -> {
                cursor.set(x, y, z);
                if (this.damageCalculator.shouldBlockExplode(self, this.level, cursor,
                    SERVER_OPTIMIZE$AIR, remaining)) {
                    positions.add(cursor.immutable());
                }
            });
        return new ObjectArrayList<>(positions);
    }

    /**
     * True for the calculators whose behaviour for an air position is verified:
     * the base one ({@code getBlockExplosionResistance} = empty for air + empty
     * fluid, {@code shouldBlockExplode} = true) and the entity-based subclass
     * (resistance kept empty through {@code Optional.map}, and its
     * {@code shouldBlockExplode} is still called by the rebuild, so entity
     * overrides are honored). Custom calculators keep the vanilla path.
     */
    private static boolean serverOptimize$vanillaAirBehaviour(ExplosionDamageCalculator calculator) {
        return calculator != null
            && (calculator.getClass() == ExplosionDamageCalculator.class
                || calculator instanceof EntityBasedExplosionDamageCalculator);
    }

    /**
     * Conservative "every block in this box is air" test: all covering chunks
     * must already be loaded and every covering section must report no non-air
     * palette entry ({@code maybeHas} reads the section palette). A fluid would
     * occupy a palette entry too, so fluids are covered as well.
     */
    private static boolean serverOptimize$isAllAir(ServerLevel level, double x0, double y0, double z0,
                                                   double x1, double y1, double z1) {
        int minX = floor(Math.min(x0, x1)) - 1;
        int maxX = floor(Math.max(x0, x1)) + 1;
        int minZ = floor(Math.min(z0, z1)) - 1;
        int maxZ = floor(Math.max(z0, z1)) + 1;
        int minY = floor(Math.min(y0, y1)) - 1;
        int maxY = floor(Math.max(y0, y1)) + 1;
        int minSection = level.getSectionIndex(minY);
        int maxSection = level.getSectionIndex(maxY);
        if (minSection < 0 || maxSection >= level.getSectionsCount()) {
            return false; // partly outside the world: those rays stop, do not guess
        }
        for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    return false; // not loaded - vanilla would look the block up
                }
                for (int section = minSection; section <= maxSection; section++) {
                    LevelChunkSection chunkSection = chunk.getSection(section);
                    if (chunkSection == null || chunkSection.maybeHas(state -> !state.isAir())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }
}
