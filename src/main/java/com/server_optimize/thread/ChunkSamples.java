package com.server_optimize.thread;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * One chunk's sections as a single unit of work.
 *
 * <p>Per section would be the finer unit, and vanilla's own, but a section is a few
 * microseconds of sampling: handing those out one by one gives the pool no backlog to
 * steal from, so it ends up using one thread. A chunk is still small enough to balance
 * across a pool - a pass visits hundreds of them - and coarse enough that the hand-out
 * costs less than the sampling.
 */
public final class ChunkSamples implements RandomTickPass.Work {
    private final SectionSamples[] sections;
    /** Counter values of this chunk's ice and snow positions, in draw order (empty in
     *  the connected-region path, where the draws happen on the region worker). */
    private final int[] precipitation;
    private final int precipitationCount;
    private final int minX;
    private final int minZ;
    /** Random tick speed; used when this chunk's precipitation is drawn from an RNG. */
    private final int speed;
    /** True when the server thread skipped the ice draws and the worker must do them. */
    private final boolean drawPrecipitation;

    public ChunkSamples(List<SectionSamples> sections, int[] precipitation, int precipitationCount,
                        int minX, int minZ, int speed, boolean drawPrecipitation) {
        this.sections = sections.toArray(new SectionSamples[0]);
        this.precipitation = precipitation;
        this.precipitationCount = precipitationCount;
        this.minX = minX;
        this.minZ = minZ;
        this.speed = speed;
        this.drawPrecipitation = drawPrecipitation;
    }

    /**
     * Vanilla's ice and snow work for this chunk: one tickPrecipitation per draw that
     * passed the 1/48 check, before the random ticks of the same chunk. Two shapes:
     * <ul>
     *   <li>positions recorded by the server thread (randomTickParallel on): rebuilt from
     *       the counter values, so the counter and the level RNG end up exactly where
     *       vanilla left them;</li>
     *   <li>drawn here from the passed RNG (connected-region path): the server thread does
     *       not consume the level RNG or the position counter for ice and snow at all, the
     *       region RNG decides the 1/48 gates and the in-chunk positions - the accepted
     *       random-sequence divergence that takes the whole pass off the server thread.</li>
     * </ul>
     * Either way the precipitation itself - heightmap and biome lookups, block reads, up to
     * three setBlocks - runs wherever this unit runs (a region worker, with setBlock
     * deferred).
     */
    private void applyPrecipitation(ServerLevel level, RandomSource random) {
        if (precipitationCount > 0) {
            for (int i = 0; i < precipitationCount; i++) {
                int r = precipitation[i] >> 2;
                level.tickPrecipitation(new BlockPos(minX + (r & 15), (r >> 16) & 15,
                    minZ + (r >> 8) & 15));
            }
            return;
        }
        if (drawPrecipitation) {
            for (int i = 0; i < speed; i++) {
                if (random.nextInt(48) == 0) {
                    level.tickPrecipitation(new BlockPos(
                        minX + random.nextInt(16), random.nextInt(16),
                        minZ + random.nextInt(16)));
                }
            }
        }
    }

    /** Read-only: runs on a worker. */
    @Override
    public void sample() {
        for (SectionSamples section : sections) {
            section.sample();
        }
    }

    /** How many sample positions this chunk will read; the parallel decision counts them. */
    @Override
    public int positionCount() {
        int positions = 0;
        for (SectionSamples section : sections) {
            positions += section.count;
        }
        return positions;
    }

    /** 8x8-chunk region id, from the chunk's block origin. */
    @Override
    public int regionId() {
        return (minX >> 7) * 4096 + (minZ >> 7);
    }

    /** Chunk x, so the region apply can pre-resolve the chunks it may read. */
    @Override
    public int chunkX() {
        return minX >> 4;
    }

    /** Chunk z, so the region apply can pre-resolve the chunks it may read. */
    @Override
    public int chunkZ() {
        return minZ >> 4;
    }

    /** Mutating: runs on the server thread, in chunk order. */
    @Override
    public void applyRandomTicks(ServerLevel level) {
        applyPrecipitation(level, level.random);
        for (SectionSamples section : sections) {
            section.applyRandomTicks(level);
        }
    }

    /** Mutating: runs on a worker, in chunk order within its region. */
    @Override
    public void applyRandomTicks(ServerLevel level, RandomSource random) {
        applyPrecipitation(level, random);
        for (SectionSamples section : sections) {
            section.applyRandomTicks(level, random);
        }
    }
}