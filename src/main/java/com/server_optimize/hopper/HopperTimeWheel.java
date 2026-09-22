package com.server_optimize.hopper;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.LevelAccessor;
import com.server_optimize.mixin.accessor.LevelChunkAccessor;
import com.server_optimize.mixin.accessor.HopperBlockEntityAccessor;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HopperTimeWheel {
    private static final int WHEEL_SIZE = 8;
    private static final int DEFAULT_COOLDOWN = 8;
    private static final int QUICK_RETRY = 1;

    private final ServerLevel level;
    private final List<LongSet> wheel;
    private final LongSet wakeQueue = new LongOpenHashSet();
    private final LongSet cacheQueue = new LongOpenHashSet();
    private final LongSet sleepSet = new LongOpenHashSet();
    private final LongSet pendingTickQueue = new LongOpenHashSet();
    private final LongSet itemWokenThisTick = new LongOpenHashSet();
    private final LongSet currentTickQueue = new LongOpenHashSet();
    private final LongSet modifiedContainers = new LongOpenHashSet();
    private final LongSet processedThisTick = new LongOpenHashSet();
    private final LongSet didEjectThisTick = new LongOpenHashSet();
    private final LongSet didSuckThisTick = new LongOpenHashSet();
    private final LongSet didWorkThisTick = new LongOpenHashSet();
    private final LongSet vanillaTickedThisTick = new LongOpenHashSet();
    private final LongSet tickerActive = new LongOpenHashSet();
    private final Map<Long, Set<TickingBlockEntity>> pendingTickerRemoval = new HashMap<>();
    private final Map<Long, CooldownState> cooldowns = new ConcurrentHashMap<>();
    private final int[] tickCheckedByBucket = new int[WHEEL_SIZE];
    private final int[] tickSleptByBucket = new int[WHEEL_SIZE];
    private final int[] tickWokeByBucket = new int[WHEEL_SIZE];
    private final int[] lastWokeByBucket = new int[WHEEL_SIZE];
    private final int[] lastSleptByBucket = new int[WHEEL_SIZE];
    private final LongSet wokeThisTick = new LongOpenHashSet();
    private final int[] activeCountByBucket = new int[WHEEL_SIZE];
    private int totalActiveCount = 0;
    private int sleepingCount = 0;
    private int currentBucket = 0;
    private volatile long currentTick = 0;
    private boolean tickInProgress = false;
    private int tickCheckedCount = 0;
    private int tickSleptCount = 0;
    private int tickWokeCount = 0;
    private volatile DebugStats debugStats = DebugStats.empty();

    private static final class CooldownState {
        boolean active;
        long dueGameTime;
        int idleRaw;

        static CooldownState idle(int raw) {
            CooldownState state = new CooldownState();
            state.active = false;
            state.dueGameTime = Long.MIN_VALUE;
            state.idleRaw = raw;
            return state;
        }

        static CooldownState active(long dueGameTime) {
            CooldownState state = new CooldownState();
            state.active = true;
            state.dueGameTime = dueGameTime;
            state.idleRaw = 0;
            return state;
        }

        int remaining(long gameTime) {
            if (!active) return idleRaw;
            long delta = dueGameTime - gameTime;
            if (delta <= 0) return 0;
            return (int) Math.min(delta, Integer.MAX_VALUE);
        }
    }

    public HopperTimeWheel(ServerLevel level) {
        this.level = level;
        this.wheel = new ArrayList<>(WHEEL_SIZE);
        for (int i = 0; i < WHEEL_SIZE; i++) {
            wheel.add(new LongOpenHashSet());
        }
    }

    public static final class DebugStats {
        public final int[] bucketCounts;
        public final int[] checkedByBucket;
        public final int[] wokeByBucket;
        public final int[] sleptByBucket;
        public final int sleepingCount;
        public final int currentBucket;
        public final int totalActive;
        public final int checkedCount;
        public final int wokeCount;
        public final int sleptCount;

        DebugStats(int[] bucketCounts, int[] checkedByBucket,
                   int[] wokeByBucket, int[] sleptByBucket, int sleepingCount,
                   int currentBucket, int totalActive, int checkedCount,
                   int wokeCount, int sleptCount) {
            this.bucketCounts = bucketCounts;
            this.checkedByBucket = checkedByBucket;
            this.wokeByBucket = wokeByBucket;
            this.sleptByBucket = sleptByBucket;
            this.sleepingCount = sleepingCount;
            this.currentBucket = currentBucket;
            this.totalActive = totalActive;
            this.checkedCount = checkedCount;
            this.wokeCount = wokeCount;
            this.sleptCount = sleptCount;
        }

        static DebugStats empty() {
            return new DebugStats(
                new int[WHEEL_SIZE],
                new int[WHEEL_SIZE],
                new int[WHEEL_SIZE],
                new int[WHEEL_SIZE],
                0,
                0,
                0,
                0,
                0,
                0
            );
        }

        /** Rebuild a stats snapshot from raw counters (sync packet / aggregation). */
        public static DebugStats from(int[] bucketCounts, int[] checkedByBucket,
                int[] wokeByBucket, int[] sleptByBucket, int sleepingCount,
                int currentBucket, int totalActive, int checkedCount,
                int wokeCount, int sleptCount) {
            return new DebugStats(bucketCounts, checkedByBucket, wokeByBucket,
                sleptByBucket, sleepingCount, currentBucket, totalActive,
                checkedCount, wokeCount, sleptCount);
        }
    }

    /**
     * Whole-server aggregate: sum every loaded dimension's wheel stats. F3
     * should report the entire server, not just the player's own dimension
     * or loaded range. Returns null when no dimension has a live wheel.
     */
    public static DebugStats aggregateServer(MinecraftServer server) {
        if (server == null) return null;
        int[] bucketCounts = new int[WHEEL_SIZE];
        int[] checkedByBucket = new int[WHEEL_SIZE];
        int[] wokeByBucket = new int[WHEEL_SIZE];
        int[] sleptByBucket = new int[WHEEL_SIZE];
        int sleepingCount = 0;
        int totalActive = 0;
        int checkedCount = 0;
        int wokeCount = 0;
        int sleptCount = 0;
        int currentBucket = 0;
        boolean any = false;
        for (ServerLevel level : server.getAllLevels()) {
            if (level == null) continue;
            if (!(level instanceof ServerOptimizeLevelAccess access)) continue;
            HopperTimeWheel wheel = access.serverOptimize$getHopperTimeWheel();
            if (wheel == null) continue;
            DebugStats s = wheel.getDebugStats();
            for (int i = 0; i < WHEEL_SIZE; i++) {
                bucketCounts[i] += s.bucketCounts[i];
                checkedByBucket[i] += s.checkedByBucket[i];
                wokeByBucket[i] += s.wokeByBucket[i];
                sleptByBucket[i] += s.sleptByBucket[i];
            }
            sleepingCount += s.sleepingCount;
            totalActive += s.totalActive;
            checkedCount += s.checkedCount;
            wokeCount += s.wokeCount;
            sleptCount += s.sleptCount;
            if (!any) {
                currentBucket = s.currentBucket;
                any = true;
            }
        }
        if (!any) return null;
        return DebugStats.from(bucketCounts, checkedByBucket, wokeByBucket,
            sleptByBucket, sleepingCount, currentBucket, totalActive,
            checkedCount, wokeCount, sleptCount);
    }

    public static HopperTimeWheel get(ServerLevel level) {
        if (!(level instanceof ServerOptimizeLevelAccess access)) {
            throw new IllegalStateException("ServerLevel is not mixin patched for HopperTimeWheel");
        }
        return access.serverOptimize$getHopperTimeWheel();
    }

    public void onTickStart() {
        if (!ModConfig.INSTANCE.hopper.enabled) {
            clear();
            tickInProgress = false;
            return;
        }
        if (!level.tickRateManager().runsNormally()) {
            clearTickState();
            tickInProgress = false;
            return;
        }

        flushTickerRemovals();
        tickInProgress = true;
        currentTick = level.getGameTime() + 1;
        currentBucket = timeBucket(currentTick);
        tickCheckedCount = 0;
        tickSleptCount = 0;
        tickWokeCount = 0;
        wokeThisTick.clear();
        Arrays.fill(tickCheckedByBucket, 0);
        Arrays.fill(tickSleptByBucket, 0);
        Arrays.fill(tickWokeByBucket, 0);
        currentTickQueue.clear();
        currentTickQueue.addAll(pendingTickQueue);
        pendingTickQueue.clear();
        processedThisTick.clear();
        didEjectThisTick.clear();
        didSuckThisTick.clear();
        didWorkThisTick.clear();
        vanillaTickedThisTick.clear();

        if (!cacheQueue.isEmpty()) {
            wakeQueue.addAll(cacheQueue);
            cacheQueue.clear();
        }
    }

    /**
     * Must run after entity ticks and before block entity ticks. Entity
     * mixins add wake-ups while ServerLevel is ticking entities; processing
     * them here lets those hoppers run in the same block entity tick phase.
     */
    public void onPreBlockEntitiesTick() {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (!tickInProgress) return;

        flushTickerRemovals();
        processWakeQueue();
        processWheelBucket();
        pruneCurrentTickQueue();
        activateAllScheduled();
        modifiedContainers.clear();
    }

    public void onTickEnd() {
        if (!ModConfig.INSTANCE.hopper.enabled) {
            clear();
            return;
        }
        if (!level.tickRateManager().runsNormally()) {
            tickInProgress = false;
            return;
        }
        flushTickerRemovals();
        for (long packed : currentTickQueue) {
            if (!processedThisTick.contains(packed)) {
                pendingTickQueue.add(packed);
            }
        }
        currentTickQueue.clear();
        itemWokenThisTick.clear();
        syncTickersWithQueue();
        flushTickerRemovals();
        wakeQueue.addAll(cacheQueue);
        cacheQueue.clear();
        updateDebugStats();
        tickInProgress = false;
    }

    public void entityWake(BlockPos pos) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        wakeQueue.add(pos.asLong());
    }

    public void neighborWake(BlockPos pos) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        wakeQueue.add(pos.asLong());
    }

    public void wakeForItem(ItemEntity item) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (item.getItem().isEmpty()) return;

        AABB itemBox = item.getBoundingBox();
        if (itemBox == null) return;

        AABB suckAabb = Hopper.SUCK_AABB;
        int minX = Mth.floor(itemBox.minX - suckAabb.maxX) + 1;
        int maxX = Mth.ceil(itemBox.maxX - suckAabb.minX) - 1;
        int minY = Mth.floor(itemBox.minY - suckAabb.maxY) + 1;
        int maxY = Mth.ceil(itemBox.maxY - suckAabb.minY) - 1;
        int minZ = Mth.floor(itemBox.minZ - suckAabb.maxZ) + 1;
        int maxZ = Mth.ceil(itemBox.maxZ - suckAabb.minZ) - 1;
        if (minX > maxX || minY > maxY || minZ > maxZ) return;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) continue;
                    if (!(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) continue;

                    AABB suckWorld = hopper.getSuckAabb().move(x, y, z);
                    if (suckWorld.intersects(itemBox)) {
                        entityWake(pos);
                        itemWokenThisTick.add(pos.asLong());
                    }
                }
            }
        }
    }

    public void containerChanged(BlockPos pos) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (!level.isLoaded(pos)) return;
        BlockEntity changed = level.getBlockEntity(pos);
        if (changed instanceof HopperBlockEntity hopper && hasPotentialWork(pos, hopper)) {
            long packed = pos.asLong();
            if (sleepSet.contains(packed)) {
                removeFromSleep(packed);
                wakeQueue.add(packed);
            }
        }
        addHoppersAroundContainer(pos, wakeQueue, true);
    }

    public void onLoad(BlockPos pos, int cooldownTime) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        long packed = pos.asLong();
        clearFromAllQueues(packed);
        removePerTickState(packed);
        cooldowns.remove(packed);

        if (cooldownTime > 0) {
            long firstTick = tickInProgress
                ? currentTick
                : Math.max(currentTick + 1, level.getGameTime() + 1);
            long due = firstTick + Math.max(0, cooldownTime - 1);
            cooldowns.put(packed, CooldownState.active(due));
            scheduleAt(packed, due);
        } else {
            cooldowns.put(packed, CooldownState.idle(cooldownTime));
            if (tickInProgress) {
                currentTickQueue.add(packed);
                removeFromSleep(packed);
            } else {
                pendingTickQueue.add(packed);
            }
        }
    }

    public void onUnload(BlockPos pos) {
        long packed = pos.asLong();
        clearFromAllQueues(packed);
        removePerTickState(packed);
        tickerActive.remove(packed);
        cooldowns.remove(packed);
    }

    public void refreshDebugStats() {
        updateDebugStats();
    }

    public void onTickerRegistered(BlockPos pos) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        tickerActive.add(pos.asLong());
    }

    public boolean isStale(BlockPos pos) {
        long packed = pos.asLong();
        return sleepSet.contains(packed) || pendingTickQueue.contains(packed);
    }

    public boolean isRegistered(BlockPos pos) {
        long packed = pos.asLong();
        return cooldowns.containsKey(packed)
            || sleepSet.contains(packed)
            || isInAnyWheelBucket(packed)
            || wakeQueue.contains(packed)
            || cacheQueue.contains(packed)
            || currentTickQueue.contains(packed)
            || pendingTickQueue.contains(packed)
            || processedThisTick.contains(packed)
            || tickerActive.contains(packed);
    }

    /** Thread-safe check used by client render threads before reading cooldown. */
    public boolean hasCooldown(BlockPos pos) {
        return cooldowns.containsKey(pos.asLong());
    }

    /** True while the wheel considers this hopper still on cooldown. */
    public boolean isOnCooldown(BlockPos pos) {
        CooldownState state = cooldowns.get(pos.asLong());
        return state != null && state.remaining(displayTime()) > 0;
    }

    public boolean isScheduled(BlockPos pos) {
        return currentTickQueue.contains(pos.asLong());
    }

    public boolean claimProcessing(BlockPos pos) {
        long packed = pos.asLong();
        if (processedThisTick.contains(packed)) return false;
        if (!currentTickQueue.contains(packed)) return false;
        didEjectThisTick.remove(packed);
        didSuckThisTick.remove(packed);
        processedThisTick.add(packed);
        return true;
    }

    public void markEject(BlockPos pos) {
        didEjectThisTick.add(pos.asLong());
    }

    public void markSuck(BlockPos pos) {
        didSuckThisTick.add(pos.asLong());
    }

    public void markWork(BlockPos pos, HopperBlockEntity hopper) {
        long packed = pos.asLong();
        didWorkThisTick.add(packed);
        if (processedThisTick.contains(packed)) return;
        vanillaTickedThisTick.add(packed);
        noteCooldownSet(pos, DEFAULT_COOLDOWN);
        wakeHoppersAroundContainer(pos, pos);
    }

    public void onVanillaTick(BlockPos pos) {
        long packed = pos.asLong();
        vanillaTickedThisTick.add(packed);
        CooldownState state = cooldowns.get(packed);
        if (state == null) return;
        if (!state.active) {
            if (state.idleRaw < 0) {
                cooldowns.put(packed, CooldownState.idle(0));
            }
            return;
        }
        if (state.remaining(currentTick) <= 0 && !processedThisTick.contains(packed)) {
            currentTickQueue.add(packed);
            removeFromSleep(packed);
            recordWake(packed);
        }
    }

    public boolean wasProcessedThisTick(BlockPos pos) {
        return processedThisTick.contains(pos.asLong());
    }

    public boolean wakeIfStaleHasWork(BlockPos pos, HopperBlockEntity hopper) {
        long packed = pos.asLong();
        if (!sleepSet.contains(packed)) return false;
        if (!ModConfig.INSTANCE.hopper.enabled) return false;
        if (isOnCooldown(pos)) return false;
        if (hasPotentialWork(pos, hopper)) {
            clearFromAllQueues(packed);
            currentTickQueue.add(packed);
            recordWake(packed);
            return true;
        }
        return false;
    }

    public void afterProcess(BlockPos pos, HopperBlockEntity hopper) {
        long packed = pos.asLong();
        currentTickQueue.remove(packed);
        processedThisTick.remove(packed);

        boolean didEject = didEjectThisTick.remove(packed);
        boolean didSuck = didSuckThisTick.remove(packed);
        boolean didWork = didEject || didSuck || didWorkThisTick.remove(packed);

        if (didWork) {
            noteCooldownSet(pos, DEFAULT_COOLDOWN);
            Direction facing = hopper.getBlockState().getValue(HopperBlock.FACING);

            if (!didEject && !didSuck) {
                wakeHoppersAroundContainer(pos, pos);
            }

            if (didEject) {
                BlockPos outputPos = pos.relative(facing);
                BlockEntity output = getBlockEntityIfLoaded(outputPos);
                if (output instanceof Container) {
                    modifiedContainers.add(outputPos.asLong());
                    wakeHoppersAroundContainer(outputPos, pos);
                }
            }

            if (didSuck) {
                BlockPos sourcePos = pos.above();
                BlockEntity source = getBlockEntityIfLoaded(sourcePos);
                if (source instanceof Container) {
                    modifiedContainers.add(sourcePos.asLong());
                    wakeHoppersAroundContainer(sourcePos, pos);
                }
            }
        } else {
            CooldownState state = cooldowns.get(packed);
            if (state != null && state.active) {
                cooldowns.put(packed, CooldownState.idle(0));
            }
            if (itemWokenThisTick.contains(packed)) {
                removeFromSleep(packed);
                pendingTickQueue.add(packed);
            } else if (hasPotentialWork(pos, hopper)) {
                schedule(packed, QUICK_RETRY);
            } else {
                enterSleep(packed);
            }
        }
        deactivateTicker(pos);
    }

    // =====================================================
    //  Cooldown queries (for Tweakmore / NBT / sync)
    // =====================================================

    public int getCooldownForDisplay(BlockPos pos) {
        CooldownState state = cooldowns.get(pos.asLong());
        if (state == null) return -1;
        int remaining = state.remaining(displayTime());
        // Vanilla stores 1 until the due pushItemsTick decrements it to 0 and
        // then resets it to 8 after transfer. Don't show the next 8 while the
        // due tick has not run yet, otherwise the transfer interval appears as 9.
        if (state.active && remaining <= 0) return 1;
        return remaining;
    }

    public int getDisplaySnapshot(BlockPos pos) {
        return getCooldownForDisplay(pos);
    }

    public void noteCooldownSet(BlockPos pos, int cooldown) {
        long packed = pos.asLong();
        if (cooldown <= 0) {
            cooldowns.put(packed, CooldownState.idle(cooldown));
            clearFromAllQueues(packed);
            enterSleep(packed);
            deactivateTicker(pos);
            return;
        }

        boolean willTickLaterThisTick = !processedThisTick.contains(packed)
            && (currentTickQueue.contains(packed) || pendingTickQueue.contains(packed));
        long due = currentTick + Math.max(0, cooldown - (willTickLaterThisTick ? 1 : 0));
        cooldowns.put(packed, CooldownState.active(due));
        scheduleAt(packed, due);
        deactivateTicker(pos);
    }

    public void requeueOnCooldown(BlockPos pos) {
        long packed = pos.asLong();
        CooldownState state = cooldowns.get(packed);
        if (state == null || processedThisTick.contains(packed)) return;

        currentTickQueue.remove(packed);
        removeFromSleep(packed);
        if (state.active) {
            scheduleAt(packed, state.dueGameTime);
        } else if (state.idleRaw > 0) {
            long due = currentTick + Math.max(0, state.idleRaw - (vanillaTickedThisTick.contains(packed) ? 0 : 1));
            cooldowns.put(packed, CooldownState.active(due));
            scheduleAt(packed, due);
        }
        deactivateTicker(pos);
    }

    public int getCooldownForSave(BlockPos pos) {
        return getCooldownForSave(pos, -1);
    }

    public int getCooldownForSave(BlockPos pos, int fallback) {
        CooldownState state = cooldowns.get(pos.asLong());
        if (state == null) return fallback;
        int value = state.remaining(displayTime());
        if (state.active && value <= 0) return 1;
        return Math.max(-1, Math.min(value, 8));
    }

    private long displayTime() {
        return currentTick > 0 ? currentTick : level.getGameTime();
    }

    // =====================================================
    //  Internal helpers
    // =====================================================

    private void processWakeQueue() {
        Iterator<Long> it = wakeQueue.iterator();
        while (it.hasNext()) {
            long packed = it.next();
            if (!isHopperValid(packed)) {
                it.remove();
                continue;
            }
        tickCheckedCount++;
        tickCheckedByBucket[currentBucket]++;
        BlockPos pos = BlockPos.of(packed);
            if (isOnCooldown(pos)) {
                requeueOnCooldown(pos);
                deactivateTicker(pos);
                it.remove();
                continue;
            }
            if (wasAffectedByLastTick(packed)) {
                it.remove();
                addHopperBlockNeighbors(packed);
                currentTickQueue.add(packed);
                removeFromSleep(packed);
                clearFromWheelBucket(packed);
                recordWake(packed);
            } else {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof HopperBlockEntity hopper && hasPotentialWork(pos, hopper)) {
                    it.remove();
                    currentTickQueue.add(packed);
                    removeFromSleep(packed);
                    clearFromWheelBucket(packed);
                    recordWake(packed);
                } else if (isInAnyWheelBucket(packed)) {
                    it.remove();
                } else {
                    if (itemWokenThisTick.contains(packed)) {
                        removeFromSleep(packed);
                        pendingTickQueue.add(packed);
                    } else {
                        enterSleep(packed);
                    }
                    it.remove();
                    deactivateTicker(pos);
                }
            }
        }
    }

    private void processWheelBucket() {
        LongSet bucket = wheel.get(currentBucket);
        List<Long> bucketEntries = new ArrayList<>(bucket);
        for (long packed : bucketEntries) {
            if (!isHopperValid(packed)) {
                removeFromWheelBucket(packed, currentBucket);
                continue;
            }
            tickCheckedCount++;
            tickCheckedByBucket[currentBucket]++;
            BlockPos pos = BlockPos.of(packed);
            if (isOnCooldown(pos)) {
                requeueOnCooldown(pos);
                deactivateTicker(pos);
            } else {
                currentTickQueue.add(packed);
                removeFromSleep(packed);
                recordWake(packed);
            }
        }
    }

    private void pruneCurrentTickQueue() {
        Iterator<Long> it = currentTickQueue.iterator();
        while (it.hasNext()) {
            long packed = it.next();
            BlockPos pos = BlockPos.of(packed);
            if (!isHopperValid(packed)) {
                it.remove();
                continue;
            }
            if (isOnCooldown(pos)) {
                requeueOnCooldown(pos);
                it.remove();
                continue;
            }
            if (!hasPotentialWork(packed)) {
                CooldownState state = cooldowns.get(packed);
                if (state != null && state.active) {
                    cooldowns.put(packed, CooldownState.idle(0));
                }
                if (itemWokenThisTick.contains(packed)) {
                    removeFromSleep(packed);
                    pendingTickQueue.add(packed);
                } else {
                    enterSleep(packed);
                }
                it.remove();
                deactivateTicker(pos);
            }
        }
    }

    private void schedule(long packed, int delay) {
        long due = currentTick + Math.max(0, delay);
        scheduleAt(packed, due);
    }

    private void scheduleAt(long packed, long dueGameTime) {
        if (tickInProgress && dueGameTime <= currentTick) {
            currentTickQueue.add(packed);
            removeFromSleep(packed);
            return;
        }
        int targetBucket = timeBucket(dueGameTime);
        if (!wheel.get(targetBucket).contains(packed)) {
            clearFromWheelBucket(packed);
            wheel.get(targetBucket).add(packed);
            activeCountByBucket[targetBucket]++;
            totalActiveCount++;
        }
        removeFromSleep(packed);
    }

    private void enterSleep(long packed) {
        BlockPos pos = BlockPos.of(packed);
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof HopperBlockEntity hopper) {
            ((HopperBlockEntityAccessor) hopper).setCooldown(0);
        }
        if (sleepSet.add(packed)) {
            tickSleptCount++;
            tickSleptByBucket[currentBucket]++;
            sleepingCount++;
        }
        clearFromWheelBucket(packed);
    }

    private void removeFromSleep(long packed) {
        if (sleepSet.remove(packed)) {
            sleepingCount--;
        }
    }

    private void recordWake(long packed) {
        if (wokeThisTick.add(packed)) {
            tickWokeCount++;
            tickWokeByBucket[currentBucket]++;
        }
    }

    private static int timeBucket(long gameTime) {
        return (int) Math.floorMod(gameTime, WHEEL_SIZE);
    }

    private void updateDebugStats() {
        int[] checkedByBucket = tickCheckedByBucket.clone();
        lastWokeByBucket[currentBucket] = tickWokeByBucket[currentBucket];
        lastSleptByBucket[currentBucket] = tickSleptByBucket[currentBucket];
        int[] wokeByBucket = lastWokeByBucket.clone();
        int[] sleptByBucket = lastSleptByBucket.clone();
        debugStats = new DebugStats(
            activeCountByBucket.clone(),
            checkedByBucket,
            wokeByBucket,
            sleptByBucket,
            sleepingCount,
            currentBucket,
            totalActiveCount,
            tickCheckedCount,
            tickWokeCount,
            tickSleptCount
        );
    }

    public DebugStats getDebugStats() {
        return debugStats;
    }

    private boolean shouldHaveTicker(long packed) {
        return currentTickQueue.contains(packed);
    }

    private void activateAllScheduled() {
        for (long packed : currentTickQueue) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.isLoaded(pos)) continue;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof HopperBlockEntity hopper) {
                ensureTickerActive(pos, hopper);
            }
        }
        syncTickersWithQueue();
    }

    private void ensureTickerActive(BlockPos pos, HopperBlockEntity hopper) {
        long packed = pos.asLong();
        if (tickerActive.contains(packed)) return;
        LevelChunk chunk = level.getChunkAt(pos);
        ((LevelChunkAccessor) chunk).serverOptimize$updateBlockEntityTicker(hopper);
        tickerActive.add(packed);
    }

    private void deactivateTicker(BlockPos pos) {
        long packed = pos.asLong();
        if (!tickerActive.remove(packed)) return;
        if (!level.isLoaded(pos)) return;

        LevelChunk chunk = level.getChunkAt(pos);
        if (!(chunk instanceof LevelChunkAccessor accessor)) return;
        Map<BlockPos, TickingBlockEntity> tickersInLevel = accessor.serverOptimize$getTickersInLevel();
        TickingBlockEntity wrapper = tickersInLevel.get(pos);
        if (wrapper != null) {
            accessor.serverOptimize$removeBlockEntityTicker(pos);
            queueTickerRemoval(packed, wrapper);
        }
    }

    private void queueTickerRemoval(long packed, TickingBlockEntity wrapper) {
        pendingTickerRemoval
            .computeIfAbsent(packed, key -> Collections.newSetFromMap(new IdentityHashMap<>()))
            .add(wrapper);
    }

    private void syncTickersWithQueue() {
        if (tickerActive.isEmpty()) return;
        List<Long> toDeactivate = new ArrayList<>();
        for (long packed : tickerActive) {
            if (!currentTickQueue.contains(packed)) {
                toDeactivate.add(packed);
            }
        }
        for (long packed : toDeactivate) {
            deactivateTicker(BlockPos.of(packed));
        }
    }

    private void flushTickerRemovals() {
        if (pendingTickerRemoval.isEmpty()) return;
        LevelAccessor levelAccessor = (LevelAccessor) level;
        if (levelAccessor.serverOptimize$isTickingBlockEntities()) return;

        List<TickingBlockEntity> blockEntityTickers = levelAccessor.serverOptimize$getBlockEntityTickers();
        List<TickingBlockEntity> pendingBlockEntityTickers = levelAccessor.serverOptimize$getPendingBlockEntityTickers();
        for (Set<TickingBlockEntity> wrappers : pendingTickerRemoval.values()) {
            for (TickingBlockEntity wrapper : wrappers) {
                removeByIdentity(blockEntityTickers, wrapper);
                removeByIdentity(pendingBlockEntityTickers, wrapper);
            }
        }
        pendingTickerRemoval.clear();
    }

    private static void removeByIdentity(List<TickingBlockEntity> list, TickingBlockEntity wrapper) {
        Iterator<TickingBlockEntity> iterator = list.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() == wrapper) {
                iterator.remove();
                return;
            }
        }
    }

    private void clearTickState() {
        currentTickQueue.clear();
        itemWokenThisTick.clear();
        processedThisTick.clear();
        didEjectThisTick.clear();
        didSuckThisTick.clear();
        didWorkThisTick.clear();
        vanillaTickedThisTick.clear();
        modifiedContainers.clear();
    }

    private void removePerTickState(long packed) {
        processedThisTick.remove(packed);
        didEjectThisTick.remove(packed);
        didSuckThisTick.remove(packed);
        didWorkThisTick.remove(packed);
        vanillaTickedThisTick.remove(packed);
        modifiedContainers.remove(packed);
    }

    private boolean wasAffectedByLastTick(long packed) {
        if (modifiedContainers.isEmpty()) return false;
        BlockPos pos = BlockPos.of(packed);
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HopperBlockEntity hopper)) return false;
        for (long modified : modifiedContainers) {
            if (canHopperInteractWithContainer(pos, hopper, BlockPos.of(modified))) {
                return true;
            }
        }
        return false;
    }

    private void addHopperBlockNeighbors(long packed) {
        BlockPos pos = BlockPos.of(packed);
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (!level.isLoaded(neighbor)) continue;
            if (!(level.getBlockEntity(neighbor) instanceof HopperBlockEntity hopper)) continue;
            if (!canHopperInteractWithContainer(neighbor, hopper, pos)) continue;
            cacheQueue.add(neighbor.asLong());
        }
    }

    private void wakeHoppersAroundContainer(BlockPos containerPos, BlockPos processedHopper) {
        addHoppersAroundContainer(containerPos, wakeQueue, true);
    }

    private void addHoppersAroundContainer(BlockPos containerPos, LongSet targetQueue, boolean requireWork) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = containerPos.relative(dir);
            if (!level.isLoaded(neighbor)) continue;
            BlockEntity be = level.getBlockEntity(neighbor);
            if (!(be instanceof HopperBlockEntity hopper)) continue;
            if (!canHopperInteractWithContainer(neighbor, hopper, containerPos)) continue;
            if (requireWork && !hasPotentialWork(neighbor, hopper)) continue;
            long np = neighbor.asLong();
            if (!sleepSet.contains(np)) continue;
            removeFromSleep(np);
            targetQueue.add(np);
        }
    }

    private boolean canHopperInteractWithContainer(BlockPos hopperPos, HopperBlockEntity hopper,
                                                   BlockPos containerPos) {
        if (containerPos.equals(hopperPos.above())) return true;
        Direction facing = hopper.getBlockState().getValue(HopperBlock.FACING);
        return hopperPos.relative(facing).equals(containerPos);
    }

    private void tryWakeIfSleeping(BlockPos target, BlockPos processedHopper) {
        if (target.equals(processedHopper)) return;
        long packed = target.asLong();
        if (!sleepSet.contains(packed)) return;
        if (!level.isLoaded(target)) return;
        if (!(level.getBlockEntity(target) instanceof HopperBlockEntity)) {
            removeFromSleep(packed);
            return;
        }
        removeFromSleep(packed);
        wakeQueue.add(packed);
    }

    private BlockEntity getBlockEntityIfLoaded(BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        return level.getBlockEntity(pos);
    }

    private boolean hasPotentialWork(BlockPos pos, HopperBlockEntity hopper) {
        if (!hopper.getBlockState().getValue(HopperBlock.ENABLED)) return false;
        boolean canEject = !hopper.isEmpty() && outputCanAccept(pos, hopper);
        boolean canSuck = !inventoryFull(hopper) && inputAvailable(pos, hopper);
        return canEject || canSuck;
    }

    private boolean outputCanAccept(BlockPos pos, HopperBlockEntity hopper) {
        Direction facing = hopper.getBlockState().getValue(HopperBlock.FACING);
        Container target = HopperBlockEntity.getContainerAt(level, pos.relative(facing));
        if (target == null) return false;
        Direction targetFace = facing.getOpposite();
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            ItemStack stack = hopper.getItem(i);
            if (!stack.isEmpty() && canPlaceAnyInto(target, stack, targetFace)) return true;
        }
        return false;
    }

    private boolean inputAvailable(BlockPos pos, HopperBlockEntity hopper) {
        Container source = HopperBlockEntity.getContainerAt(level, pos.above());
        if (source != null) {
            int[] slots = getSlotsForFace(source, Direction.DOWN);
            for (int slot : slots) {
                ItemStack stack = source.getItem(slot);
                if (stack.isEmpty()) continue;
                if (!source.canTakeItem(hopper, slot, stack)) continue;
                if (source instanceof WorldlyContainer worldly
                        && !worldly.canTakeItemThroughFace(slot, stack, Direction.DOWN)) continue;
                if (canAcceptAnyInto(hopper, stack)) return true;
            }
            return false;
        }

        BlockState above = level.getBlockState(pos.above());
        boolean blocked = hopper.isGridAligned()
                && above.isCollisionShapeFullBlock(level, pos.above())
                && !above.is(BlockTags.DOES_NOT_BLOCK_HOPPERS);
        if (blocked) return false;

        List<ItemEntity> itemsAbove = HopperBlockEntity.getItemsAtAndAbove(level, hopper);
        if (itemsAbove == null) return false;
        for (ItemEntity item : itemsAbove) {
            ItemStack stack = item.getItem();
            if (!stack.isEmpty() && canAcceptAnyInto(hopper, stack)) return true;
        }
        return false;
    }

    private boolean inventoryFull(HopperBlockEntity hopper) {
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            var stack = hopper.getItem(i);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) return false;
        }
        return true;
    }

    private int[] getSlotsForFace(Container container, Direction face) {
        if (container instanceof WorldlyContainer worldly) {
            return worldly.getSlotsForFace(face);
        }
        int[] slots = new int[container.getContainerSize()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        return slots;
    }

    private boolean canPlaceAnyInto(Container target, ItemStack stack, Direction face) {
        for (int slot : getSlotsForFace(target, face)) {
            if (!target.canPlaceItem(slot, stack)) continue;
            if (target instanceof WorldlyContainer worldly
                    && !worldly.canPlaceItemThroughFace(slot, stack, face)) continue;
            ItemStack existing = target.getItem(slot);
            if (existing.isEmpty() || canMerge(existing, stack)) return true;
        }
        return false;
    }

    private boolean canAcceptAnyInto(Container target, ItemStack stack) {
        for (int slot = 0; slot < target.getContainerSize(); slot++) {
            if (!target.canPlaceItem(slot, stack)) continue;
            ItemStack existing = target.getItem(slot);
            if (existing.isEmpty() || canMerge(existing, stack)) return true;
        }
        return false;
    }

    private boolean canMerge(ItemStack existing, ItemStack incoming) {
        return existing.getCount() < existing.getMaxStackSize()
                && ItemStack.isSameItemSameComponents(existing, incoming);
    }

    private boolean hasPotentialWork(long packed) {
        BlockPos pos = BlockPos.of(packed);
        if (!level.isLoaded(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HopperBlockEntity hopper)) return false;
        return hasPotentialWork(pos, hopper);
    }

    private boolean isHopperValid(long packed) {
        BlockPos pos = BlockPos.of(packed);
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof HopperBlockEntity;
    }

    private boolean isInAnyWheelBucket(long packed) {
        for (LongSet b : wheel) {
            if (b.contains(packed)) return true;
        }
        return false;
    }

    private void clearFromWheelBucket(long packed) {
        for (int i = 0; i < WHEEL_SIZE; i++) {
            if (wheel.get(i).remove(packed)) {
                activeCountByBucket[i]--;
                totalActiveCount--;
            }
        }
    }

    private void removeFromWheelBucket(long packed, int bucket) {
        if (wheel.get(bucket).remove(packed)) {
            activeCountByBucket[bucket]--;
            totalActiveCount--;
        }
    }

    private void clearFromAllQueues(long packed) {
        removeFromSleep(packed);
        wakeQueue.remove(packed);
        cacheQueue.remove(packed);
        currentTickQueue.remove(packed);
        pendingTickQueue.remove(packed);
        clearFromWheelBucket(packed);
    }

    public void clear() {
        for (int i = 0; i < WHEEL_SIZE; i++) {
            wheel.get(i).clear();
            activeCountByBucket[i] = 0;
        }
        totalActiveCount = 0;
        sleepSet.clear();
        sleepingCount = 0;
        wakeQueue.clear();
        cacheQueue.clear();
        modifiedContainers.clear();
        currentTickQueue.clear();
        pendingTickQueue.clear();
        itemWokenThisTick.clear();
        currentTick = 0;
        processedThisTick.clear();
        didEjectThisTick.clear();
        didSuckThisTick.clear();
        didWorkThisTick.clear();
        vanillaTickedThisTick.clear();
        cooldowns.clear();
        currentBucket = 0;
        tickCheckedCount = 0;
        tickSleptCount = 0;
        tickWokeCount = 0;
        wokeThisTick.clear();
        Arrays.fill(tickCheckedByBucket, 0);
        Arrays.fill(tickSleptByBucket, 0);
        Arrays.fill(tickWokeByBucket, 0);
        Arrays.fill(lastWokeByBucket, 0);
        Arrays.fill(lastSleptByBucket, 0);
        debugStats = DebugStats.empty();
        tickerActive.clear();
        pendingTickerRemoval.clear();
    }
}
