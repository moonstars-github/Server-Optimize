package com.server_optimize.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.file.Path;

@Mixin(net.minecraft.world.level.chunk.storage.RegionFile.class)
public interface RegionFileAccessor {

    @Invoker("writeHeader")
    void serverOptimize$writeHeader();

    @Accessor("path")
    Path serverOptimize$getPath();
}