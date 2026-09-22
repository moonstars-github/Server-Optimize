package com.server_optimize.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Optional;

/**
 * Per-explosion caches shared by the explosion mixins ([explosion] group).
 * <p>
 * Lifecycle follows one {@code ServerExplosion.explode()} call:
 * {@link #begin} at HEAD, {@link #end()} at RETURN. Nested explosions (TNT
 * chain ignited mid-blast) race the caches, so every lookup re-validates the
 * blast center key before trusting a cached value; a mismatch falls back to
 * vanilla.
 * <p>
 * P4: the caches are DENSE arrays indexed by the explosion's swept bounding box
 * instead of position-keyed hash maps. An explosion touches at most a
 * {@code (2*radius + 4)³} box of blocks (radius 4 -&gt; ~12³ entries ~ 7 KB per
 * array, L1/L2 resident), so the lookup becomes an index computation and an
 * array read with no hashing and no pointer chasing - which is what the TNT
 * profile needs (IPC 1.46, 50% L3 misses, DRAM 15.6 GB/s while hashing long
 * keys). Staleness is handled with a generation stamp per slot, so nothing has
 * to be cleared between explosions; the arrays are reused and grow as needed.
 * <p>
 * The caches hold only per-explosion facts that cannot change during the ray
 * phase (blocks are written only afterwards in interactWithBlocks and the
 * server tick is single-threaded), so hits are exact:
 * <ul>
 *   <li>{@link #hasClip}/{@link #clipOr}/{@link #cacheClip} - vanilla
 *       getSeenPercent ray result per entity block (densityBlockCache);</li>
 *   <li>{@link #hasResistance}/{@link #getResistance}/{@link #cacheResistance}
 *       - getBlockExplosionResistance per block (resistanceCache);</li>
 *   <li>{@link #getCachedState}/{@link #cacheState} - getBlockState per block.</li>
 * </ul>
 * Explosions whose box would need more than {@link #MAX_ENTRIES} slots (very
 * large blast radii) simply disable caching for that explosion and behave
 * exactly like vanilla.
 */
public final class ExplosionCaches {

    /** Upper bound for one explosion's dense box (2^18 slots = 1 MB of stamps). */
    private static final int MAX_ENTRIES = 1 << 18;
    /** Sentinel for "no cached state" in the Object array. */
    private static final Object NO_STATE = new Object();

    private ExplosionCaches() {
    }

    private static long centerKey = Long.MIN_VALUE;
    private static boolean caching;

    private static int minX;
    private static int minY;
    private static int minZ;
    private static int sizeX;
    private static int sizeY;
    private static int sizeZ;

    private static int generation = 1;
    private static int[] stamps = new int[0];
    private static float[] clips = new float[0];
    private static float[] resistances = new float[0];
    private static Object[] states = new Object[0];
    private static Optional<Float>[] resistanceOptionals = newOptionalArray(0);

    /**
     * Starts a new explosion's caches. The box covers every block any phase can
     * touch: rays reach at most {@code radius * 4/3 * 1.3 + 1} blocks and the
     * entity scan covers {@code radius * 2 + 1} blocks.
     */
    public static void begin(long centerBlockKey, double centerX, double centerY, double centerZ,
                             float radius) {
        centerKey = centerBlockKey;
        double reach = (double) radius * 2.0D + 2.0D;
        minX = floor(centerX - reach);
        minY = floor(centerY - reach);
        minZ = floor(centerZ - reach);
        sizeX = floor(centerX + reach) - minX + 1;
        sizeY = floor(centerY + reach) - minY + 1;
        sizeZ = floor(centerZ + reach) - minZ + 1;
        long entries = (long) sizeX * sizeY * sizeZ;
        if (entries <= 0 || entries > MAX_ENTRIES) {
            caching = false;
            return;
        }
        caching = true;
        if (stamps.length < entries) {
            int capacity = (int) entries;
            stamps = new int[capacity];
            clips = new float[capacity];
            resistances = new float[capacity];
            states = new Object[capacity];
            resistanceOptionals = newOptionalArray(capacity);
            generation = 1;
            return;
        }
        if (++generation == Integer.MAX_VALUE) {
            Arrays.fill(stamps, 0);
            generation = 1;
        }
    }

    /** Release the caches (end of explosion). */
    public static void end() {
        centerKey = Long.MIN_VALUE;
        caching = false;
    }

    public static boolean active(long centerBlockKey) {
        return centerKey == centerBlockKey;
    }

    private static int index(BlockPos pos) {
        if (!caching) {
            return -1;
        }
        int x = pos.getX() - minX;
        int y = pos.getY() - minY;
        int z = pos.getZ() - minZ;
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return -1;
        }
        return (x * sizeZ + z) * sizeY + y;
    }

    private static boolean valid(int index) {
        return index >= 0 && stamps[index] == generation;
    }

    public static boolean hasClip(BlockPos pos) {
        return valid(index(pos));
    }

    public static float clipOr(BlockPos pos, float fallback) {
        int i = index(pos);
        return valid(i) ? clips[i] : fallback;
    }

    public static void cacheClip(BlockPos pos, float value) {
        int i = index(pos);
        if (i >= 0) {
            stamps[i] = generation;
            clips[i] = value;
        }
    }

    public static boolean hasResistance(BlockPos pos) {
        return valid(index(pos));
    }

    public static float getResistance(BlockPos pos, float fallback) {
        int i = index(pos);
        return valid(i) ? resistances[i] : fallback;
    }

    public static void cacheResistance(BlockPos pos, float value) {
        int i = index(pos);
        if (i >= 0) {
            stamps[i] = generation;
            resistances[i] = value;
            // P5: keep the boxed result as well. The damage calculator's
            // contract is Optional<Float>, so every hit would otherwise
            // allocate a fresh Optional + Float (~4.6 GB of Integer/Optional
            // in the 15.8.4 profile, of which this path is the part we own).
            resistanceOptionals[i] = Optional.of(value);
        }
    }

    /** Cached {@code Optional<Float>} for a block, or {@code null} when absent. */
    public static Optional<Float> getResistanceOptional(BlockPos pos) {
        int i = index(pos);
        return valid(i) ? resistanceOptionals[i] : null;
    }

    public static void cacheState(BlockPos pos, BlockState state) {
        int i = index(pos);
        if (i >= 0) {
            stamps[i] = generation;
            states[i] = state == null ? NO_STATE : state;
        }
    }

    public static BlockState getCachedState(BlockPos pos) {
        int i = index(pos);
        if (!valid(i)) {
            return null;
        }
        Object value = states[i];
        return value == NO_STATE ? null : (BlockState) value;
    }

    @SuppressWarnings("unchecked")
    private static Optional<Float>[] newOptionalArray(int size) {
        return (Optional<Float>[]) new Optional<?>[size];
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }
}
