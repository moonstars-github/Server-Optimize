package com.server_optimize.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

/**
 * Field access into {@link RegionFileStorage} for the thread-safe
 * {@code getRegionFile} replacement (chunk.regionBatchLoader). All fields
 * are final, so concurrent readers can safely snapshot them.
 */
@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccessor {

    @Accessor("regionCache")
    Long2ObjectLinkedOpenHashMap<RegionFile> serverOptimize$getRegionCache();

    @Accessor("info")
    RegionStorageInfo serverOptimize$getInfo();

    @Accessor("folder")
    Path serverOptimize$getFolder();

    @Accessor("sync")
    boolean serverOptimize$getSync();
}