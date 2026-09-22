package com.server_optimize.mixin.accessor;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(IOWorker.class)
public interface IOWorkerAccessor {

    @Accessor("storage")
    RegionFileStorage serverOptimize$getStorage();
}