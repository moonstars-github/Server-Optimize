package com.server_optimize.mixin;

import com.server_optimize.mixin.accessor.RegionFileStorageAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.util.FileUtil;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.file.Path;

/**
 * Make {@link RegionFileStorage#getRegionFile} thread-safe
 * (chunk.regionBatchLoader).
 * <p>
 * Vanilla's {@code getRegionFile} manipulates the private
 * {@code Long2ObjectLinkedOpenHashMap<RegionFile> regionCache} with no lock.
 * That is fine when every read goes through the single IOWorker
 * consecutive executor, but it corrupts the hash table the moment reads are
 * dispatched to a parallel pool (rehash "Index -1" crash, the reason the
 * previous regionBatchLoader was disabled).
 * <p>
 * Every call site (read / write / scanChunk) is redirected to an identical
 * implementation that performs the cache look-up, LRU eviction and insert
 * inside {@code synchronized(regionCache)}. The lock is held only for the
 * map operations (microseconds); the actual chunk NBT read on the
 * RegionFile happens outside it. RegionFile methods themselves are
 * {@code synchronized}, so cross-thread access to the same region is safe.
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageSyncMixin {

    @Unique
    private static final int MAX_CACHE_SIZE = 256;

    @Redirect(
        method = {"read", "write", "scanChunk"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;getRegionFile(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/storage/RegionFile;")
    )
    private RegionFile serverOptimize$safeGetRegionFile(RegionFileStorage self, ChunkPos pos)
        throws java.io.IOException {
        RegionFileStorageAccessor acc = (RegionFileStorageAccessor) (Object) self;
        Long2ObjectLinkedOpenHashMap<RegionFile> cache = acc.serverOptimize$getRegionCache();
        synchronized (cache) {
            long key = ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ());
            RegionFile cached = cache.getAndMoveToFirst(key);
            if (cached != null) {
                return cached;
            }
            if (cache.size() >= MAX_CACHE_SIZE) {
                cache.removeLast().close();
            }
            Path folder = acc.serverOptimize$getFolder();
            FileUtil.createDirectoriesSafe(folder);
            Path file = folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
            RegionFile regionFile = new RegionFile(acc.serverOptimize$getInfo(), file, folder, acc.serverOptimize$getSync());
            cache.putAndMoveToFirst(key, regionFile);
            return regionFile;
        }
    }
}