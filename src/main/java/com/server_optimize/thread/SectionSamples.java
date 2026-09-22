package com.server_optimize.thread;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * One section's sample positions, rebuilt and read on a worker and applied on the server
 * thread.
 *
 * <p>The positions are not stored: the counter value of the ones that turned out to be
 * randomly ticking is, which is all the apply needs to rebuild the same position, plus the
 * state that was found there. That is what keeps a high random tick speed from turning the
 * sampling into an allocation per sample on either side.
 */
public final class SectionSamples {
    private final LevelChunkSection section;
    private final int minX;
    private final int sectionBlockY;
    private final int minZ;
    /** Counter value before this section's first position; each position steps it once. */
    private final int firstCounter;
    /** Positions to draw, i.e. the random tick speed. */
    final int count;
    private int[] tickableCounters = new int[0];
    private BlockState[] tickableStates = new BlockState[0];

    public SectionSamples(LevelChunkSection section, int minX, int sectionBlockY, int minZ,
                          int firstCounter, int count) {
        this.section = section;
        this.minX = minX;
        this.sectionBlockY = sectionBlockY;
        this.minZ = minZ;
        this.firstCounter = firstCounter;
        this.count = count;
    }

    /** Read-only: runs on a worker. */
    public void sample() {
        int[] foundCounters = new int[count];
        BlockState[] foundStates = new BlockState[count];
        int found = 0;
        int counter = firstCounter;
        for (int i = 0; i < count; i++) {
            counter = counter * 3 + 1013904223;
            int r = counter >> 2;
            // getBlockRandomPos(minX, sectionBlockY, minZ, 15) would return
            // (minX + (r & 15), sectionBlockY + ((r >> 16) & 15), minZ + ((r >> 8) & 15)),
            // and both minX and minZ are multiples of 16, so the section-local indices the
            // state has to be read at are the offsets themselves.
            BlockState state = section.getBlockState(r & 15, (r >> 16) & 15, (r >> 8) & 15);
            if (state.isRandomlyTicking()) {
                foundCounters[found] = counter;
                foundStates[found] = state;
                found++;
            }
        }
        tickableCounters = java.util.Arrays.copyOf(foundCounters, found);
        tickableStates = java.util.Arrays.copyOf(foundStates, found);
    }

    /** Mutating: runs on the server thread, in chunk order. */
    public void applyRandomTicks(ServerLevel level) {
        for (int i = 0; i < tickableCounters.length; i++) {
            int r = tickableCounters[i] >> 2;
            BlockPos pos = new BlockPos(minX + (r & 15), sectionBlockY + ((r >> 16) & 15),
                minZ + ((r >> 8) & 15));
            tickableStates[i].randomTick(level, pos, level.random);
        }
    }

    /** Mutating: runs on a worker, in chunk order within its region. */
    public void applyRandomTicks(ServerLevel level, RandomSource random) {
        for (int i = 0; i < tickableCounters.length; i++) {
            int r = tickableCounters[i] >> 2;
            BlockPos pos = new BlockPos(minX + (r & 15), sectionBlockY + ((r >> 16) & 15),
                minZ + ((r >> 8) & 15));
            tickableStates[i].randomTick(level, pos, random);
        }
    }
}