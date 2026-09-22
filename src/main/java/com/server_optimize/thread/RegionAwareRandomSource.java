package com.server_optimize.thread;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * The thread-aware stand-in for {@code Level.random} ([thread.multithread.regionbased]).
 *
 * <p>The natural-spawn path and the precipitation code draw positions and types straight from
 * the {@code Level.random} field in several places, and the vanilla source asserts single-
 * thread access (LegacyRandomSource), so a worker touching it crashes. {@link LevelRandomMixin}
 * installs this wrapper as the field value at construction time: every read of
 * {@code level.random} - spawn positions, mob-type selection, the mob's own RNG, precipitation
 * internals - resolves to the worker's own RNG while a region apply is active and to the real
 * source otherwise, so the server thread's RNG sequence is untouched and the workers never
 * share it.
 */
public final class RegionAwareRandomSource implements RandomSource {

    private final RandomSource main;

    public RegionAwareRandomSource(RandomSource main) {
        this.main = main;
    }

    private RandomSource rng() {
        return RegionScene.active() ? RegionSpawn.threadRandom() : this.main;
    }

    @Override
    public RandomSource fork() {
        return this.rng().fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return this.rng().forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        this.rng().setSeed(seed);
    }

    @Override
    public int nextInt() {
        return this.rng().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return this.rng().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return this.rng().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return this.rng().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return this.rng().nextFloat();
    }

    @Override
    public double nextDouble() {
        return this.rng().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return this.rng().nextGaussian();
    }
}