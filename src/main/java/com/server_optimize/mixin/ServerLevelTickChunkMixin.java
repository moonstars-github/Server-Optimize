package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.config.ModConfig;
import com.server_optimize.thread.ChunkSamples;
import com.server_optimize.thread.RandomTickPass;
import com.server_optimize.thread.RegionUnitBuilder;
import com.server_optimize.thread.SectionSamples;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;

import java.util.ArrayList;
import java.util.List;

/**
 * Section-parallel random ticking ([thread.multithread] randomTickParallel).
 *
 * <p>The work unit is the section, which is also vanilla's own unit: tickChunk walks
 * chunk.getSections() and skips the ones whose isRandomlyTicking() is false. What is
 * expensive there is reading the sampled block states - PalettedContainer.get was the
 * single hottest method in the random-tick profile at 41% - and those reads are pure.
 * The tick calls themselves are not: they mutate the world.
 *
 * <p>So the loop is split in three:
 * <ol>
 *   <li>server thread: the ice/snow pass verbatim, then advance the position counter by the
 *       number of positions the pass will draw, handing each section the counter value its
 *       first position uses (getBlockRandomPos is one step of that counter per call, so the
 *       counter - not any RNG state - is what has to come out where vanilla left it);</li>
 *   <li>workers: for each section, rebuild those positions from the counter and read the
 *       sampled block states, keeping the ones that report isRandomlyTicking() - read-only, no
 *       world state touched;</li>
 *   <li>server thread: apply the collected randomTick calls in chunk order, so the
 *       RNG draws inside randomTick happen in the same order as vanilla.</li>
 * </ol>
 *
 * <p>Steps one and two are separated in time: a chunk only queues its work in
 * {@link com.server_optimize.thread.RandomTickPass}, and the whole pass is sampled and
 * applied at once when ServerChunkCacheTickChunksMixin flushes the queue. See that class
 * for why the hand-out waits for the end of the pass.
 *
 * <p>In region mode (regionbased on, randomTickParallel off) this method is not reached at
 * all - the server thread never walks the block-ticking chunks, and the pass units come
 * from the player simulation squares (see {@link com.server_optimize.thread.RegionUnitBuilder}).
 *
 * <p>Equivalence: same RNG sequence (positions drawn on the server thread, tick draws
 * applied serially in order), same mutations on the same thread, same visiting order.
 * The only thing that moves off the server thread is the reading of block states.
 *
 * <p>The workers are the pool this mod installs (AffinityManager.workerPool()), sized from
 * the allowed CPU set - no new threads are created, and in single-player it is the same
 * pool the command reports.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTickChunkMixin {

    @WrapMethod(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V")
    private void serverOptimize$randomTickParallel(LevelChunk chunk, int randomTickSpeed,
                                                   Operation<Void> original) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || (!cfg.thread.randomTickParallel
            && !cfg.thread.regionbased.enableRegionBasedMultithreadTicking) || randomTickSpeed <= 0) {
            original.call(chunk, randomTickSpeed);
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        ProfilerFiller profiler = Profiler.get();

        // The server thread's slice of this phase is every part of it that it does itself: the
        // draws and the counter bookkeeping, and applying the collected ticks at the flush.
        //
        // The ice and snow pass: in region mode (regionbased on, randomTickParallel off, i.e.
        // the one-worker-per-connected-region path) the whole pass - the 1/48 draws AND the
        // precipitation - runs on the region workers with the region RNG, so the server thread
        // does not consume the level RNG or the position counter for it at all. This removes
        // the O(ticking chunks x random tick speed) serial cost that made the phase grow with
        // the number of regions. With randomTickParallel on (inside-region split / non-region
        // modes) the draws stay here as before, keeping the level RNG and counter exact.
        boolean regionConnected = cfg.thread.regionbased.enableRegionBasedMultithreadTicking
            && !cfg.thread.randomTickParallel;
        long serverStart = System.nanoTime();
        profiler.push("iceandsnow");
        LevelRandValueAccessor counter = (LevelRandValueAccessor) (Object) level;
        int counterValue = counter.serverOptimize$getRandValue();
        int[] precipitation = regionConnected ? null : new int[0];
        int precipitationCount = 0;
        if (!regionConnected) {
            for (int i = 0; i < randomTickSpeed; i++) {
                if (level.random.nextInt(48) == 0) {
                    // One step of getBlockRandomPos(minX, 0, minZ, 15); the position is rebuilt
                    // from this value on the worker, so no BlockPos is allocated here.
                    counterValue = counterValue * 3 + 1013904223;
                    if (precipitation.length == precipitationCount) {
                        precipitation = java.util.Arrays.copyOf(precipitation,
                            Math.max(4, precipitationCount * 2));
                    }
                    precipitation[precipitationCount++] = counterValue;
                }
            }
            // Leave the counter where vanilla's draws would have left it before the tick draws.
            counter.serverOptimize$setRandValue(counterValue);
        }
        long iceSnowNanos = System.nanoTime() - serverStart;
        profiler.popPush("tickBlocks");

        // Vanilla draws one position per tick of randomTickSpeed for every randomly ticking
        // section. Each of those draws is one step of the level's position counter plus a
        // BlockPos, and since the coordinates are a pure function of the counter (see
        // LevelRandValueAccessor) there is nothing to decide here: the counter is advanced
        // without allocating anything, each section is handed the value its first position
        // uses, and the workers rebuild the positions from it. The counter is left exactly
        // where vanilla would have left it, so every later draw of this tick - in this level or
        // in another one - sees the value it would have seen.
        int chunkStart = counter.serverOptimize$getRandValue();
        // One section's worth of stepping, as the affine map v -> v * mult + add (mod 2^32):
        // the counter advances once per draw, so stepping it once per section is the same as
        // stepping it randomTickSpeed times. Computing this pair once per chunk keeps the
        // server-thread cost at O(1) per section instead of the O(log speed) exponentiation
        // the earlier version ran twice for every section.
        long[] step = RegionUnitBuilder.stepFor(randomTickSpeed);
        long mult = step[0];
        long add = step[1];
        int value = chunkStart;
        LevelChunkSection[] sections = chunk.getSections();
        List<SectionSamples> tasks = new ArrayList<>(sections.length);
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || !section.isRandomlyTicking()) {
                continue;
            }
            // Vanilla passes sectionToBlockCoord(sectionY) as the y argument, so the position
            // handed to randomTick is a world position, even though the sampled state is read
            // at y & 15.
            int sectionBlockY = chunk.getSectionYFromSectionIndex(index) << 4;
            tasks.add(new SectionSamples(section, minX, sectionBlockY, minZ, value, randomTickSpeed));
            value = (int) ((value & 0xFFFFFFFFL) * mult + add);
        }
        // The counter is left exactly where stepping it once per draw would have left it.
        counter.serverOptimize$setRandValue(value);

        if (!regionConnected && tasks.isEmpty() && precipitationCount == 0) {
            // Nothing tickable here, but the pass is still running: keep it open so its report
            // and the reported thread count stay in step with the chunks that did tick.
            RandomTickPass.addServerOnly(level, System.nanoTime() - serverStart, iceSnowNanos);
        } else {
            // The whole chunk is one unit of work, sampled and applied on a worker. In the
            // connected-region path every chunk is queued (the precipitation of a chunk with
            // no tickable section is still drawn by its region worker).
            RandomTickPass.addChunk(level, new ChunkSamples(tasks, precipitation,
                precipitationCount, minX, minZ, randomTickSpeed, regionConnected),
                System.nanoTime() - serverStart, iceSnowNanos);
        }
        profiler.pop();
    }
}