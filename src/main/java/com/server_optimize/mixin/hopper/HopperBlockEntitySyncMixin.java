package com.server_optimize.mixin.hopper;

import com.server_optimize.client.HopperCooldownClientTracker;
import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import com.server_optimize.mixin.accessor.HopperBlockEntityAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntity.class)
public class HopperBlockEntitySyncMixin {

    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void serverOptimize$wakeAroundChangedContainer(CallbackInfo ci) {
        if (!((Object)this instanceof Container)) return;
        if (!ModConfig.INSTANCE.hopper.enabled) return;

        BlockEntity self = (BlockEntity)(Object)this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        HopperTimeWheel.get(serverLevel).containerChanged(self.getBlockPos());
    }

    @Inject(method = "getUpdateTag", at = @At("RETURN"), cancellable = true)
    private void serverOptimize$getUpdateTag(HolderLookup.Provider registries,
                                             CallbackInfoReturnable<CompoundTag> cir) {
        if (!((Object)this instanceof HopperBlockEntity hopper)) return;
        if (!ModConfig.INSTANCE.hopper.enabled) return;

        Level level = hopper.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        CompoundTag tag = cir.getReturnValue();
        if (tag == null) return;

        if (ModConfig.INSTANCE.hopper.suppressItemPackets) {
            tag.remove("Items");
        }

        if (ModConfig.INSTANCE.hopper.tweakmoreCompat) {
            HopperTimeWheel wheel = HopperTimeWheel.get(serverLevel);
            BlockPos pos = hopper.getBlockPos();
            if (wheel.hasCooldown(pos)) {
                tag.putInt("TransferCooldown", wheel.getDisplaySnapshot(pos));
            }
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void serverOptimize$setRemoved(CallbackInfo ci) {
        if (!((Object)this instanceof HopperBlockEntity hopper)) return;
        Level level = hopper.getLevel();
        if (level != null) {
            HopperCooldownClientTracker.onRemoved(level, hopper.getBlockPos());
        }
    }

    // The NBT returned by a server query or block-entity update is the only
    // authoritative client-side cooldown source. setLevel/loadAdditional may
    // run before that data is loaded, so they must not write the tracker with
    // a stale vanilla field.
    @Inject(method = "loadWithComponents(Lnet/minecraft/world/level/storage/ValueInput;)V", at = @At("RETURN"))
    private void serverOptimize$loadWithComponents(ValueInput input, CallbackInfo ci) {
        if (!((Object)this instanceof HopperBlockEntity hopper)) return;
        Level level = hopper.getLevel();
        if (level != null && !level.isClientSide()) return;
        HopperCooldownClientTracker.onSync(
            level,
            hopper.getBlockPos(),
            ((HopperBlockEntityAccessor) hopper).getCooldown()
        );
    }
}
