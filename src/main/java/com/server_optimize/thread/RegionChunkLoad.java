package com.server_optimize.thread;

import com.server_optimize.mixin.accessor.ChunkMapAccessor;
import com.server_optimize.mixin.accessor.ServerChunkCacheRegionAccessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Worker-side chunk access for the region apply ([thread.multithread.regionbased]).
 *
 * <p>Vanilla {@link ServerChunkCache#getChunk(int, int, ChunkStatus, boolean)} served to a
 * non-server thread does {@code supplyAsync(supplier, mainThreadProcessor).join()}: the load
 * is drained by the server thread. During a region apply the server thread is parked at the
 * join barrier, so a worker cannot wait for it - and it must not load the chunk itself
 * either: completing a load on a worker runs the FULL-status transition on that worker, and
 * other mods assert that this happens on the server thread. Lithium's ChunkStatusTracker
 * throws {@code IllegalStateException: ChunkStatusTracker.onChunkAccessible called on wrong
 * thread!} from exactly that spot (observed crash, stack through the worker's load), and
 * vanilla drives every load from the server thread for the same reason.
 *
 * <p>So a region apply never loads and never waits: it reads what the world already has.
 * <ol>
 *   <li>the region's pre-resolved chunk table (built by the server thread before the task
 *       starts) - an indexed lookup in the worker's own array, no lock, no shared map;</li>
 *   <li>otherwise the holder's already-completed full chunk, if the chunk is loaded but was
 *       not part of the table (a chunk whose area lies outside the region's bounding box);</li>
 *   <li>otherwise an empty chunk for this tick, so a random tick at the edge of the loaded
 *       area sees air instead of blocking the tick or tripping another mod's thread check.
 *       A write there is still committed: setBlock is deferred to the region scene and
 *       replayed on the server thread, which loads the chunk the vanilla way if it has to.</li>
 * </ol>
 * Reads that are not asking for FULL status get whatever is already present at that status,
 * which is what a caller checking for structure starts or features gets in this window.
 */
public final class RegionChunkLoad {

    /** Reads served by the region's pre-resolved table. */
    public static final LongAdder TABLE_CALLS = new LongAdder();

    /** Reads served by the holder's already-loaded full chunk. */
    public static final LongAdder PRESENT_CALLS = new LongAdder();

    /** Reads of a chunk that is not loaded, answered with an empty chunk. */
    public static final LongAdder EMPTY_CALLS = new LongAdder();

    /** Reads not asking for FULL status that no table entry satisfied. */
    public static final LongAdder OTHER_CALLS = new LongAdder();

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("server-optimize");

    /** Empty-fallback reads seen so far; the first few are logged. */
    private static final AtomicInteger EMPTY_LOGGED = new AtomicInteger();

    /** Table misses seen so far; the first few are logged with the table's coverage. */
    private static final AtomicInteger TABLE_MISS_LOGGED = new AtomicInteger();

    /** Table misses that are logged (the rest only count). */
    private static final int MISS_LOG_LIMIT = 6;

    /** Empty-fallback reads that are logged (the rest only count). */
    private static final int LOG_LIMIT = 10;

    /** One empty chunk per level, used when a read lands outside the loaded area. */
    private static final Map<Level, LevelChunk> EMPTY_CHUNKS =
        Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Debug switch ({@code -Dserveroptimize.noFastLoad=true}): skip the table and always take
     * the present-or-empty path. Used to verify that path; not a config item, off in
     * production.
     */
    private static final boolean NO_TABLE = Boolean.getBoolean("serveroptimize.noFastLoad");

    private RegionChunkLoad() {
    }

    /**
     * The chunk a region-apply worker gets for this position. Never loads, never blocks.
     * With {@code require} false an absent chunk is null (so {@code Level.isLoaded} and
     * friends keep saying "not loaded"); with {@code require} true it is an empty chunk,
     * because callers of the requiring path dereference the result.
     *
     * <p>The arguments are CHUNK coordinates, not block coordinates:
     * {@code ServerChunkCache.getChunk(int, int, ChunkStatus, boolean)} is the ChunkSource API
     * and {@code Level.getChunk(int, int, ...)} passes its chunk coordinates straight through.
     * (Shifting them by 4 here served reads from the chunk at chunkX/16 - silently the wrong
     * chunk, which the table-miss diagnostics exposed.)
     */
    public static ChunkAccess load(RegionScene.Context context, ServerChunkCache cache,
                                   int chunkX, int chunkZ, ChunkStatus status, boolean require) {
        // The table holds full chunks, and a chunk at FULL satisfies every lower status as
        // well - that is exactly what vanilla's getChunk(x, z, status, require) promises - so
        // a biome or feature lookup is served here too instead of taking the slow path.
        if (!NO_TABLE) {
            ChunkAccess fromTable = context.chunk(chunkX, chunkZ);
            if (fromTable != null) {
                TABLE_CALLS.increment();
                return fromTable;
            }
            int miss = TABLE_MISS_LOGGED.incrementAndGet();
            if (miss <= MISS_LOG_LIMIT) {
                LOGGER.info("[region] table miss: chunk {} {} {} (context {} on {}) {}",
                    chunkX, chunkZ, context.describeCoverage(chunkX, chunkZ),
                    context.regionId(), Thread.currentThread().getName(), caller());
            }
        }
        if (status == ChunkStatus.FULL) {
            LevelChunk present = presentChunk(cache, chunkX, chunkZ);
            if (present != null) {
                PRESENT_CALLS.increment();
                return present;
            }
            if (!require) {
                return null;
            }
            EMPTY_CALLS.increment();
            int seen = EMPTY_LOGGED.incrementAndGet();
            if (seen <= LOG_LIMIT) {
                LOGGER.info("[region] read outside the loaded area: chunk {} {} on {} - "
                    + "answered empty for this tick ({})", chunkX, chunkZ,
                    Thread.currentThread().getName(), seen);
            }
            return emptyChunk(cache);
        }
        // Not the FULL status and not in the table: whatever is present at that status,
        // without promoting anything.
        OTHER_CALLS.increment();
        ChunkAccess present = presentAtStatus(cache, chunkX, chunkZ, status);
        if (present != null) {
            return present;
        }
        return require ? emptyChunk(cache) : null;
    }

    /**
     * The loaded full chunk of a visible holder, or null. Read-only and safe on a worker: the
     * server thread is parked for the whole apply and no worker mutates the chunk maps any
     * more (nothing loads), so the visible map is only being read.
     */
    public static LevelChunk loadedOrNull(ServerChunkCache cache, int chunkX, int chunkZ) {
        return presentChunk(cache, chunkX, chunkZ);
    }

    /** The holder's completed full chunk, or null when it is not loaded (never waits). */
    private static LevelChunk presentChunk(ServerChunkCache cache, int chunkX, int chunkZ) {
        ChunkHolder holder = holder(cache, chunkX, chunkZ);
        if (holder == null) {
            return null;
        }
        try {
            // Typed LevelChunk: the full-chunk future cannot hand out a proto chunk, so the
            // result is always safe for the LevelChunk cast the read paths do.
            net.minecraft.server.level.ChunkResult<LevelChunk> result =
                holder.getFullChunkFuture().getNow(null);
            return result == null ? null : result.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whatever is already present at this status (no promotion, no load). */
    private static ChunkAccess presentAtStatus(ServerChunkCache cache, int chunkX, int chunkZ,
                                               ChunkStatus status) {
        ChunkHolder holder = holder(cache, chunkX, chunkZ);
        return holder == null ? null : holder.getChunkIfPresentUnchecked(status);
    }

    private static ChunkHolder holder(ServerChunkCache cache, int chunkX, int chunkZ) {
        return ((ServerChunkCacheRegionAccessor) (Object) cache)
            .serverOptimize$callGetVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** A few frames of the current call stack, for the table-miss diagnostic. */
    private static String caller() {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        StringBuilder text = new StringBuilder("callers:");
        int added = 0;
        for (StackTraceElement frame : frames) {
            String name = frame.getClassName();
            // Skip our own frames and the plain plumbing between the caller and this code, so
            // the printed frames are the ones that decided the coordinates.
            if (name.startsWith("com.server_optimize") || name.startsWith("java.lang.Thread")
                || name.endsWith("class_3215") || name.endsWith("class_1937")) {
                continue;
            }
            text.append(' ').append(frame.getClassName()).append('#').append(frame.getMethodName());
            if (++added >= 4) {
                break;
            }
        }
        return text.toString();
    }

    /** One empty chunk per level, so a read outside the loaded area has something to read. */
    private static LevelChunk emptyChunk(ServerChunkCache cache) {
        ServerLevel level = ((ChunkMapAccessor) (Object) cache.chunkMap).serverOptimize$getLevel();
        return EMPTY_CHUNKS.computeIfAbsent(level, owner -> {
            ResourceKey<net.minecraft.world.level.biome.Biome> plains = Biomes.PLAINS;
            return new EmptyLevelChunk(owner, new ChunkPos(0, 0),
                owner.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(plains));
        });
    }
}
