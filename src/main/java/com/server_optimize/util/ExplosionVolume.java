package com.server_optimize.util;

/**
 * Air-volume fast path for explosions ([explosion] airVolumeFastPath, "P1").
 * <p>
 * Both ray phases of a vanilla {@code ServerExplosion} only consult the world
 * through block-state / fluid-state lookups. When everything a phase can touch
 * is air, those lookups all answer the same thing:
 * <ul>
 *   <li>{@code getBlockExplosionResistance} is {@code Optional.empty()} for air
 *       with empty fluid in every vanilla calculator (the entity-based one
 *       keeps it empty through {@code Optional.map}), so a ray's remaining
 *       power only loses the fixed per-step constant {@code 0.22500001F};</li>
 *   <li>the destroy decision still has to be taken by the real calculator, but
 *       it only needs the air state and the remaining power - no world
 *       lookup.</li>
 * </ul>
 * The position set of {@code calculateExplodedPositions} is therefore
 * computable without touching a single block, and bit-exactly: the ray
 * directions, the per-ray float power roll (one {@code nextFloat()} per shell
 * ray, in the same order) and the double stepping are mirrored from the vanilla
 * bytecode below.
 * <p>
 * This class is deliberately free of Minecraft types (positions are plain ints
 * and the world access is injected as callbacks) so the arithmetic can be
 * verified against a transcription of the vanilla loop without a game
 * classpath, and so a future SIMD/auto-vectorised kernel can replace the inner
 * march without touching game code.
 */
public final class ExplosionVolume {

    /** Vanilla walks a 16³ grid but skips the interior (indices 0/15 only):
     *  16³ - 14³ = 1352 shell rays, each consuming one {@code nextFloat()} call
     *  BEFORE the march (so the world RNG sequence must stay identical). */
    public static final int SHELL_RAYS = 16 * 16 * 16 - 14 * 14 * 14;

    /** Vanilla's per-step distance and power drain (float/double constants taken
     *  from the bytecode of ServerExplosion.calculateExplodedPositions). */
    private static final double STEP = 0.30000001192092896D;
    private static final float POWER_DRAIN = 0.22500001F;

    /** Source of the per-ray power roll - {@code level.random::nextFloat}. */
    public interface FloatSupplier {
        float nextFloat();
    }

    /** Block position predicate - {@code level::isInWorldBounds}. */
    public interface BoundsTest {
        boolean test(int x, int y, int z);
    }

    /**
     * Receives every position vanilla would add to the destruction set, with
     * the remaining power vanilla would pass to the damage calculator.
     * Deduplication is the caller's job (vanilla collects into a HashSet).
     */
    public interface PositionConsumer {
        void accept(int x, int y, int z, float remainingPower);
    }

    private static final java.util.concurrent.atomic.AtomicLong seenPercentSkips =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong positionRebuilds =
        new java.util.concurrent.atomic.AtomicLong();

    private ExplosionVolume() {
    }

    public static long seenPercentSkips() {
        return seenPercentSkips.get();
    }

    public static long positionRebuilds() {
        return positionRebuilds.get();
    }

    /** Marks one entity-ray phase skipped (statistics only). */
    public static void countSeenPercentSkip() {
        seenPercentSkips.incrementAndGet();
    }

    /** Marks one block-ray phase rebuilt analytically (statistics only). */
    public static void countPositionRebuild() {
        positionRebuilds.incrementAndGet();
    }

    /**
     * Rebuilds the position set of {@code calculateExplodedPositions()} for an
     * all-air swept volume. Consumes exactly {@link #SHELL_RAYS}
     * {@code nextFloat()} calls in vanilla's order.
     *
     * @return the number of positions handed to {@code out} (before dedup)
     */
    public static int collectAirPositions(double centerX, double centerY, double centerZ, float radius,
                                          FloatSupplier random, BoundsTest inWorldBounds,
                                          PositionConsumer out) {
        int emitted = 0;
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                for (int k = 0; k < 16; k++) {
                    if ((i != 0 && i != 15) && (j != 0 && j != 15) && (k != 0 && k != 15)) {
                        continue;
                    }
                    float dirX = (float) i / 15.0F * 2.0F - 1.0F;
                    float dirY = (float) j / 15.0F * 2.0F - 1.0F;
                    float dirZ = (float) k / 15.0F * 2.0F - 1.0F;
                    float length = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
                    dirX /= length;
                    dirY /= length;
                    dirZ /= length;
                    // vanilla rolls the power once per shell ray, before the march
                    float remaining = radius * (0.7F + random.nextFloat() * 0.6F);
                    double dx = dirX;
                    double dy = dirY;
                    double dz = dirZ;
                    double x = centerX;
                    double y = centerY;
                    double z = centerZ;
                    while (remaining > 0.0F) {
                        int blockX = floor(x);
                        int blockY = floor(y);
                        int blockZ = floor(z);
                        if (!inWorldBounds.test(blockX, blockY, blockZ)) {
                            break;
                        }
                        out.accept(blockX, blockY, blockZ, remaining);
                        emitted++;
                        x += dx * STEP;
                        y += dy * STEP;
                        z += dz * STEP;
                        remaining -= POWER_DRAIN;
                    }
                }
            }
        }
        return emitted;
    }

    /** {@code Mth.floor} for doubles (BlockPos.containing uses it). */
    private static int floor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }
}
