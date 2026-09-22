package com.server_optimize.mixin.hopper;

import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import com.server_optimize.mixin.accessor.HopperBlockEntityAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerQueryTagMixin {

    @Redirect(
        method = "handleBlockEntityTagQuery",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;saveWithoutMetadata(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"
        )
    )
    private CompoundTag serverOptimize$queryHopperCooldown(BlockEntity blockEntity, HolderLookup.Provider registries) {
        CompoundTag tag = blockEntity.saveWithoutMetadata(registries);
        if (!(blockEntity instanceof HopperBlockEntity hopper)) return tag;
        if (!ModConfig.INSTANCE.hopper.enabled || !ModConfig.INSTANCE.hopper.tweakmoreCompat) return tag;

        Level level = hopper.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return tag;

        HopperTimeWheel wheel = HopperTimeWheel.get(serverLevel);
        BlockPos pos = hopper.getBlockPos();
        if (wheel.hasCooldown(pos)) {
            tag.putInt("TransferCooldown", wheel.getDisplaySnapshot(pos));
        }
        return tag;
    }
}
