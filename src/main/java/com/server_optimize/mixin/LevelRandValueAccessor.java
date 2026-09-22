package com.server_optimize.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The counter {@code Level.getBlockRandomPos} advances.
 *
 * <p>That counter is the whole of the state the method carries between calls: the k-th call in
 * a level is the k-th step of {@code randValue = randValue * 3 + 1013904223}, and the returned
 * coordinates are a pure function of that step -
 * {@code x + (r & 15), y + ((r >> 16) & yMask), z + ((r >> 8) & 15)} with
 * {@code r = randValue >> 2} - so two callers that step it the same number of times produce
 * the same positions wherever they run.
 *
 * <p>That is what lets the section-parallel random ticking rebuild vanilla's sample positions
 * on its workers instead of paying for a call and a BlockPos allocation per sample on the
 * server thread, which is millions of allocations per tick at a high random tick speed. The
 * field is only read and written by the server thread, and nothing reads it between the draws
 * of one chunk, so snapshotting it, advancing it by the same number of steps and writing it
 * back leaves every later caller - in this level, in this tick - exactly where vanilla would
 * have left it.
 */
@Mixin(Level.class)
public interface LevelRandValueAccessor {

    @Accessor("randValue")
    int serverOptimize$getRandValue();

    @Accessor("randValue")
    void serverOptimize$setRandValue(int value);
}
