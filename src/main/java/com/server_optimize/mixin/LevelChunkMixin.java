package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import com.server_optimize.mixin.accessor.HopperBlockEntityAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public class LevelChunkMixin {

    @Inject(method = "clearAllBlockEntities", at = @At("HEAD"))
    private void serverOptimize$beforeClearAllBlockEntities(CallbackInfo ci) {
        LevelChunk self = (LevelChunk)(Object)this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!ModConfig.INSTANCE.hopper.enabled) return;

        HopperTimeWheel wheel = HopperTimeWheel.get(serverLevel);
        boolean unloadedHopper = false;
        for (BlockEntity blockEntity : self.getBlockEntities().values()) {
            if (blockEntity instanceof HopperBlockEntity) {
                wheel.onUnload(blockEntity.getBlockPos());
                unloadedHopper = true;
            }
        }
        if (unloadedHopper) {
            wheel.refreshDebugStats();
        }
    }

    @Inject(method = "updateBlockEntityTicker", at = @At("RETURN"))
    private void serverOptimize$afterUpdateBlockEntityTicker(BlockEntity blockEntity, CallbackInfo ci) {
        if (!(blockEntity instanceof HopperBlockEntity hopper)) return;
        if (!ModConfig.INSTANCE.hopper.enabled) return;

        Level level = hopper.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        HopperTimeWheel wheel = HopperTimeWheel.get(serverLevel);
        if (!wheel.isRegistered(hopper.getBlockPos())) {
            wheel.onLoad(
                hopper.getBlockPos(),
                ((HopperBlockEntityAccessor) hopper).getCooldown()
            );
        }
        wheel.onTickerRegistered(hopper.getBlockPos());
    }

}
