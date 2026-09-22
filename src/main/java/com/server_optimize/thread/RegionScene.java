package com.server_optimize.thread;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * One region-apply worker's context: its deferred setBlock commits and the chunks it may read.
 *
 * <p>Region apply workers run the randomTick logic (read + decide) but every shared-structure
 * mutation - light, neighbour updates, game events, scheduled ticks - is deferred through
 * {@link #defer} instead of being applied on the worker. That is the "light isolation" of
 * the region engine in its minimal form: the LightEngine and the update machinery are never
 * touched concurrently, because workers only queue and the server thread replays each
 * region's commits serially after the region tasks join.
 *
 * <p>The context also carries the <b>pre-resolved chunk table</b> of its region: the server
 * thread fills a dense array covering the region's chunks plus a one-chunk ring before the
 * task is submitted, so a worker's block reads are an indexed lookup in its own array with
 * no shared map, no lock and no cross-thread state. That is what makes the reads cheap
 * enough to run on many workers at once - guarding every single read with a global lock cost
 * more than the work it protected (measured: the lock and the map lookup were over half of
 * the pool's samples). Reads outside the table (rare) fall back to the locked load path in
 * {@link RegionChunkLoad}, which is still correct, just serialized.
 *
 * <p>Threading: a context belongs to exactly one worker for the duration of its task and the
 * table is written only before the task starts, so no locking is needed beyond the thread
 * local itself.
 */
public final class RegionScene {

    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();

    /** One deferred commit, in the order it was produced. */
    public static final class Entry {
        public final BlockPos pos;
        public final BlockState state;
        public final int flags;

        Entry(BlockPos pos, BlockState state, int flags) {
            this.pos = pos;
            this.state = state;
            this.flags = flags;
        }
    }

    /** A region's deferred commits and the chunk table of the region it applies. */
    public static final class Context {
        private final int regionId;
        private final List<Entry> scene = new ArrayList<>();
        /** Entities created by the region's spawn pass, replayed by the server thread. */
        private final List<EntityAdd> entities = new ArrayList<>();
        /** Tick schedules requested by worker-side random ticks, replayed by the server
         *  thread (LevelTicks is one shared structure per level). */
        private final List<ScheduledTick> ticks = new ArrayList<>();
        /** Chunks of the region plus its one-chunk ring, indexed [x * height + z]; may be null. */
        private final ChunkAccess[] chunks;
        private final int baseX;
        private final int baseZ;
        private final int width;
        private final int height;
        /** One-entry cache of the last chunk read, the common case inside a chunk. */
        private ChunkAccess lastChunk;
        private int lastX = Integer.MIN_VALUE;
        private int lastZ = Integer.MIN_VALUE;
        /** Entries of the table that hold a loaded chunk / the table size (diagnostics). */
        private final int tableFilled;
        private final int tableSize;

        Context(int regionId, ChunkAccess[] chunks, int baseX, int baseZ, int width, int height,
                int tableFilled) {
            this.regionId = regionId;
            this.chunks = chunks;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.width = width;
            this.height = height;
            this.tableFilled = tableFilled;
            this.tableSize = chunks.length;
        }

        /** How many of the table's entries hold a loaded chunk, and how many entries exist. */
        public String tableFillText() {
            return this.tableFilled + "/" + this.tableSize;
        }

        /** Entries of the table that hold a loaded chunk (diagnostics). */
        public int filledEntries() {
            return this.tableFilled;
        }

        /** Entries of the table (diagnostics). */
        public int totalEntries() {
            return this.tableSize;
        }

        /**
         * Whether this table covers the given chunk and what its entry holds, for the table-miss
         * diagnostic: "inside, entry null" is a build gap, "outside" is a read beyond the
         * region's table.
         */
        public String describeCoverage(int chunkX, int chunkZ) {
            int ix = chunkX - this.baseX;
            int iz = chunkZ - this.baseZ;
            boolean inside = ix >= 0 && ix < this.width && iz >= 0 && iz < this.height;
            String entry = inside ? (this.chunks[ix * this.height + iz] == null ? "null" : "set")
                : "-";
            return "bbox x[" + this.baseX + "," + (this.baseX + this.width - 1) + "] z["
                + this.baseZ + "," + (this.baseZ + this.height - 1) + "] "
                + (inside ? "inside, entry " + entry : "OUTSIDE");
        }

        public int regionId() {
            return this.regionId;
        }

        /** The deferred commits of this region, in order. */
        public List<Entry> scene() {
            return this.scene;
        }

        /** Entities this region's spawn pass created, replayed by the server thread in order. */
        public List<EntityAdd> entities() {
            return this.entities;
        }

        /** Tick schedules this region's random ticks requested, replayed in order. */
        public List<ScheduledTick> ticks() {
            return this.ticks;
        }

        /**
         * The chunk at these chunk coordinates, or null when the table does not cover it (the
         * caller then takes the locked load path).
         */
        public ChunkAccess chunk(int chunkX, int chunkZ) {
            if (chunkX == this.lastX && chunkZ == this.lastZ) {
                return this.lastChunk;
            }
            // Same indexing as the build (see RandomTickPass.buildRegionContext): the array
            // starts at baseX/baseZ, so the offset is the plain difference. An extra +1 here
            // would hand out the neighbouring chunk's data - the diagnostics caught it as
            // "present" reads outnumbering table reads.
            int ix = chunkX - this.baseX;
            int iz = chunkZ - this.baseZ;
            if (ix < 0 || ix >= this.width || iz < 0 || iz >= this.height) {
                return null;
            }
            ChunkAccess chunk = this.chunks[ix * this.height + iz];
            if (chunk != null) {
                this.lastChunk = chunk;
                this.lastX = chunkX;
                this.lastZ = chunkZ;
            }
            return chunk;
        }
    }

    private RegionScene() {
    }

    /** Builds a context for one region; called on the server thread before its task starts. */
    public static Context createContext(int regionId, ChunkAccess[] chunks, int baseX, int baseZ,
                                        int width, int height, int tableFilled) {
        return new Context(regionId, chunks, baseX, baseZ, width, height, tableFilled);
    }

    /**
     * A context for the entity-tick workers of one partitioned region: its chunks plus a
     * one-chunk ring, filled with the already-loaded chunks (never loads). The workers'
     * block reads (movement collision, sensor lookups) are served from this table; a read
     * outside it falls back to the present-or-empty path, never to a load.
     */
    public static Context createStandaloneContext(ServerLevel level, int regionId) {
        java.util.List<RegionPartitioner.Region> regions = RegionPartitioner.compute(level);
        java.util.List<net.minecraft.world.level.ChunkPos> chunks = null;
        for (RegionPartitioner.Region region : regions) {
            if (region.id == regionId) {
                chunks = region.chunks;
                break;
            }
        }
        if (chunks == null || chunks.isEmpty()) {
            return new Context(regionId, new ChunkAccess[1], 0, 0, 1, 1, 0);
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (net.minecraft.world.level.ChunkPos pos : chunks) {
            if (pos.x < minX) {
                minX = pos.x;
            }
            if (pos.x > maxX) {
                maxX = pos.x;
            }
            if (pos.z < minZ) {
                minZ = pos.z;
            }
            if (pos.z > maxZ) {
                maxZ = pos.z;
            }
        }
        int baseX = minX - 4;
        int baseZ = minZ - 4;
        int width = maxX - minX + 9;
        int height = maxZ - minZ + 9;
        net.minecraft.server.level.ServerChunkCache cache = level.getChunkSource();
        ChunkAccess[] table = new ChunkAccess[width * height];
        int filled = 0;
        for (int cx = baseX; cx < baseX + width; cx++) {
            for (int cz = baseZ; cz < baseZ + height; cz++) {
                ChunkAccess chunk = RegionChunkLoad.loadedOrNull(cache, cx, cz);
                table[(cx - baseX) * height + (cz - baseZ)] = chunk;
                if (chunk != null) {
                    filled++;
                }
            }
        }
        return new Context(regionId, table, baseX, baseZ, width, height, filled);
    }

    /** Marks the current thread as a region-apply worker of the given context. */
    public static void enterRegion(Context context) {
        ACTIVE.set(context);
    }

    /** Leaves the region-apply context. */
    public static void exitRegion() {
        ACTIVE.remove();
    }

    /** The current thread's region context, or null when it is not a region-apply worker. */
    public static Context context() {
        return ACTIVE.get();
    }

    /** True when the current thread applies region work and must defer setBlock. */
    public static boolean active() {
        return ACTIVE.get() != null;
    }

    /** One deferred entity, replayed with (or without) its passengers. */
    public static final class EntityAdd {
        public final net.minecraft.world.entity.Entity entity;
        public final boolean withPassengers;

        EntityAdd(net.minecraft.world.entity.Entity entity, boolean withPassengers) {
            this.entity = entity;
            this.withPassengers = withPassengers;
        }
    }

    /** One deferred tick schedule, replayed with the vanilla Level.scheduleTick call. */
    public static final class ScheduledTick {
        public final net.minecraft.core.BlockPos pos;
        public final net.minecraft.world.level.block.Block block;
        public final long triggerTick;
        public final net.minecraft.world.ticks.TickPriority priority;

        ScheduledTick(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block,
                      long triggerTick, net.minecraft.world.ticks.TickPriority priority) {
            this.pos = pos;
            this.block = block;
            this.triggerTick = triggerTick;
            this.priority = priority;
        }
    }

    /** Queues one deferred commit for the current worker's region. */
    public static void defer(BlockPos pos, BlockState state, int flags) {
        Context context = ACTIVE.get();
        if (context != null) {
            context.scene.add(new Entry(pos, state, flags));
        }
    }

    /** Queues one entity for the current worker's region; the server thread adds it later. */
    public static void deferEntity(net.minecraft.world.entity.Entity entity, boolean withPassengers) {
        Context context = ACTIVE.get();
        if (context != null) {
            context.entities.add(new EntityAdd(entity, withPassengers));
        }
    }

    /** Queues one tick schedule for the current worker's region; the server thread replays it. */
    public static void deferTickSchedule(net.minecraft.core.BlockPos pos,
                                         net.minecraft.world.level.block.Block block,
                                         long triggerTick,
                                         net.minecraft.world.ticks.TickPriority priority) {
        Context context = ACTIVE.get();
        if (context != null) {
            context.ticks.add(new ScheduledTick(pos, block, triggerTick, priority));
        }
    }
}
