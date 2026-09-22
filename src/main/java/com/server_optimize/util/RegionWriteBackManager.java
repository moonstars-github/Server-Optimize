package com.server_optimize.util;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.RegionFileAccessor;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry for active region-file write-back mirrors.
 * <p>
 * Holds the {@link ByteArrayFileChannel} mirrors (one per open RegionFile) and
 * the dirty set. Flush writes the entire mirror (header + sector data) as a
 * single atomic .tmp + ATOMIC_MOVE file, triggered by
 * {@link #flushAll()} after auto-save/save-all and by
 * {@link #flush(RegionFile)} on region close.
 * <p>
 * This class is deliberately separate from the mixin to avoid the "non-private
 * static method in mixin" restriction and to keep the flush logic in a
 * testable utility.
 */
public final class RegionWriteBackManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("server-optimize");

    private static final ConcurrentHashMap<RegionFile, ByteArrayFileChannel> MIRRORS = new ConcurrentHashMap<>();
    private static final java.util.Set<RegionFile> DIRTY = ConcurrentHashMap.newKeySet();

    /** Background writer for the auto-save flush, so the disk write never blocks the tick. */
    private static final ExecutorService FLUSH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "server-optimize-region-flush");
        t.setDaemon(true);
        return t;
    });

    /** Number of async flush passes still writing to disk; drained before a flush save. */
    private static final AtomicInteger ASYNC_IN_FLIGHT = new AtomicInteger();

    private RegionWriteBackManager() {
    }

    /** Register a newly created mirror for a RegionFile. */
    public static void register(RegionFile rf, ByteArrayFileChannel mirror) {
        MIRRORS.put(rf, mirror);
    }

    /** Mark a region as dirty (a chunk was written to its mirror). */
    public static void markDirty(RegionFile rf) {
        if (MIRRORS.containsKey(rf)) {
            DIRTY.add(rf);
        }
    }

    /** Remove a region from the registry (its mirror is no longer active). */
    public static void unregister(RegionFile rf) {
        MIRRORS.remove(rf);
        DIRTY.remove(rf);
    }

    /** Flush a single region's mirror to disk (if dirty). */
    public static void flush(RegionFile rf) {
        ByteArrayFileChannel mirror = MIRRORS.get(rf);
        if (mirror == null) {
            return;
        }
        // Atomically take the dirty marker for THIS flush. A write arriving
        // during the disk write below re-adds it, so the next flush commits
        // that data too; removing here (instead of after the write) avoids
        // clearing a dirty marker that appeared mid-flush.
        if (!DIRTY.remove(rf)) {
            return;
        }
        byte[] data;
        try {
            RegionFileAccessor acc = (RegionFileAccessor) rf;
            // Take the snapshot under the RegionFile monitor, the same lock
            // vanilla's RegionFile.write() holds for its whole body. The
            // header (sector offset tables) and the chunk data in the mirror
            // are then guaranteed to be a consistent point-in-time state:
            // without this, a concurrent write (IOWorker) could mutate the
            // sector tables / mirror between refreshHeader() and backing(),
            // producing a torn file where the header points at another
            // chunk's sectors -> "Chunk at [x,z] is in the wrong location"
            // corruption on the next world load.
            synchronized (rf) {
                acc.serverOptimize$writeHeader();
                data = mirror.backing();
            }
            Path target = acc.serverOptimize$getPath();
            if (target == null) {
                return;
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (FileChannel out = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer wrap = ByteBuffer.wrap(data);
                while (wrap.hasRemaining()) {
                    out.write(wrap);
                }
                // softSaveFileWrite owns the durability choice of the write-back: on, the
                // flush fsyncs the region file (the crash window is the auto-save period);
                // off, the write is left to the OS and the flush only swaps the file in.
                // The ATOMIC_MOVE below still guarantees an intact file either way.
                if (ModConfig.INSTANCE == null || ModConfig.INSTANCE.safety.softSaveFileWrite) {
                    out.force(true);
                }
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            DIRTY.add(rf); // retry next time
            LOGGER.error("region flush failed for {}", rf, e);
        } catch (Exception e) {
            DIRTY.add(rf);
            LOGGER.error("region flush error", e);
        }
    }

    /** Flush every dirty region mirror (called after ChunkMap.saveAllChunks). */
    public static void flushAll() {
        for (RegionFile rf : DIRTY) {
            flush(rf);
        }
    }

    /**
     * Flush every dirty region mirror on the background writer (auto-save path). Returns
     * immediately; the disk write happens while the server continues ticking. A region
     * dirtied after this pass's snapshot is taken stays dirty and reaches the disk on the
     * next flush.
     */
    public static void flushAllAsync() {
        if (DIRTY.isEmpty()) {
            return;
        }
        ASYNC_IN_FLIGHT.incrementAndGet();
        FLUSH_EXECUTOR.execute(() -> {
            try {
                flushAll();
            } finally {
                // Decrement and notify inside the same monitor the waiter uses, so a
                // decrement-to-zero can never be missed between the waiter's check and wait.
                synchronized (ASYNC_IN_FLIGHT) {
                    if (ASYNC_IN_FLIGHT.decrementAndGet() == 0) {
                        ASYNC_IN_FLIGHT.notifyAll();
                    }
                }
            }
        });
    }

    /** Waits for every background flush pass to reach the disk (flush save / shutdown). */
    public static void awaitAsyncFlushes() {
        synchronized (ASYNC_IN_FLIGHT) {
            while (ASYNC_IN_FLIGHT.get() > 0) {
                try {
                    ASYNC_IN_FLIGHT.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}