package com.server_optimize.mixin.accessor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

@Mixin(LevelChunk.class)
public interface LevelChunkAccessor {
    @Accessor("tickersInLevel")
    Map<BlockPos, TickingBlockEntity> serverOptimize$getTickersInLevel();

    @Invoker("updateBlockEntityTicker")
    void serverOptimize$updateBlockEntityTicker(BlockEntity blockEntity);

    @Invoker("removeBlockEntityTicker")
    void serverOptimize$removeBlockEntityTicker(BlockPos pos);
}
