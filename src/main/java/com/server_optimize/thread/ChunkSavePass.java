package com.server_optimize.thread;

import com.server_optimize.util.RegionWriteBackManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The copy-on-save snapshot pass ([thread.multithread.regionbased] save path).
 *
 * <p>Vanilla 1.21.11 already saves each chunk as "copy then write back": {@code
 * ChunkMap.save} takes an in-memory snapshot ({@code SerializableChunkData.copyOf}) and the
 * serialization + disk write happen off the server thread. What still runs on the server
 * thread at auto-save time is the per-chunk snapshot copy itself (many chunks, serially),
 * the synchronous region-mirror flush of this mod's write-back cache, and - for flush saves
 * - the wait for the disk. This class turns the save into the pause the region engine can
 * afford: the snapshot copies are handed to the save pool while the server thread collects
 * them, and the save-all wrapper waits for every copy to complete before the next tick may
 * mutate the chunks again; the copied data is written back on background threads afterwards.
 *
 * <p>All state here is touched by the server thread only ({@code ChunkMap.saveAllChunks}
 * runs on it), so no locking is needed.
 */
public final class ChunkSavePass {

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("server-optimize");

    /** True while a {@code ChunkMap.saveAllChunks} pass is on the server thread. */
    private static boolean inPass;

    /** Snapshot-copy futures started by this pass, joined before the pass exits. */
    private static final List<CompletableFuture<?>> COPIES = new ArrayList<>();

    /** Passes seen so far; the first few log what the copy-on-save path did. */
    private static int passes;

    /** Passes that are logged (evidence without permanent log noise). */
    private static final int LOG_LIMIT = 12;

    private ChunkSavePass() {
    }

    /** Whether a save-all pass is currently running (main thread only). */
    public static boolean inPass() {
        return inPass;
    }

    /** Opens the pass; the async chunk-copy path engages while it is open. */
    public static void enter() {
        inPass = true;
    }

    /**
     * Closes the pass. First the snapshot pause: every copy started by this pass must have
     * captured the chunk data before the next tick mutates the world, so the server thread
     * waits for them here. Then the write-back strategy depends on the save type:
     * auto-save (flush=false) hands the region write-back cache flush to a background
     * thread and continues immediately; flush saves (/save-all, shutdown) wait for the
     * background flushes and then sync-flush, keeping the "everything is on disk" contract.
     */
    public static void exit(boolean flush) {
        int copies = COPIES.size();
        for (CompletableFuture<?> copy : COPIES) {
            try {
                copy.join();
            } catch (Throwable t) {
                // A failed snapshot must not take the save pass (or the server) down: vanilla
                // catches a chunk that cannot be serialized inside ChunkMap.save and reports
                // it, and the same failure reaches that handler through the write future. Here
                // it only means this chunk was not snapshotted.
                LOGGER.warn("server-optimize: chunk snapshot copy failed: {}", t.toString());
            }
        }
        COPIES.clear();
        inPass = false;
        if (flush) {
            RegionWriteBackManager.awaitAsyncFlushes();
            RegionWriteBackManager.flushAll();
        } else {
            RegionWriteBackManager.flushAllAsync();
        }
        if (++passes <= LOG_LIMIT) {
            LOGGER.info("[save] copy-on-save pass #{}: {} chunk snapshot(s) waited for at the "
                + "tick end, region mirror flush {}", passes, copies, flush ? "sync" : "async");
        }
    }

    /** Registers one snapshot copy so the pass waits for it before letting the tick resume. */
    public static void track(CompletableFuture<?> copy) {
        COPIES.add(copy);
    }
}