package com.server_optimize.mixin.hopper;

import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import com.server_optimize.mixin.accessor.HopperBlockEntityAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(value = HopperBlockEntity.class)
public class HopperBlockEntityMixin {

    @Inject(method = "pushItemsTick", at = @At("HEAD"), cancellable = true)
    private static void serverOptimizeHead(Level level, BlockPos pos, BlockState state,
                                                           HopperBlockEntity blockEntity, CallbackInfo ci) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.tickRateManager().runsNormally()) return;
        HopperTimeWheel wheel = HopperTimeWheel.get(serverLevel);
        HopperBlockEntityAccessor acc = (HopperBlockEntityAccessor) blockEntity;
        if (!wheel.isRegistered(pos)) {
            wheel.onLoad(pos, acc.getCooldown());
        }
        // Vanilla decrements cooldownTime at the start of every pushItemsTick,
        // including ticks where the time wheel cancels the transfer body. Run
        // this before any cancellation so TweakMore's CD display decrements
        // once per hopper tick, including under /tick freeze + /tick step.
        wheel.onVanillaTick(pos);
        // Vanilla writes tickedGameTime at the start of every hopper tick,
        // even when the transfer body is on cooldown. Hopper-to-hopper
        // transfers read it to choose between cooldown 8 and 7, so cancelled
        // time-wheel ticks must keep it current or neighboring hoppers get
        // the wrong cooldown.
        acc.setTickedGameTime(serverLevel.getGameTime());
        if (wheel.isStale(pos)) {
            if (wheel.isOnCooldown(pos)) {
                wheel.requeueOnCooldown(pos);
            }
            ci.cancel();
            return;
        }
        if (wheel.isOnCooldown(pos)) {
            ci.cancel();
            return;
        }
        if (!wheel.isScheduled(pos)) {
            // Cooldown already decremented above. Skip the transfer body
            // until this hopper's wheel bucket is due.
            ci.cancel();
            return;
        }
        if (!wheel.claimProcessing(pos)) {
            ci.cancel();
            return;
        }
        // Vanilla decrements before checking isOnCooldown, so 1 means the
        // decrement makes it 0 and this tick processes normally.
        ((HopperBlockEntityAccessor) blockEntity).setCooldown(1);
    }

    @Inject(method = "pushItemsTick", at = @At("RETURN"))
    private static void serverOptimizeReturn(Level level, BlockPos pos, BlockState state,
                                                             HopperBlockEntity blockEntity, CallbackInfo ci) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.tickRateManager().runsNormally()) return;
        HopperTimeWheel wheel = HopperTimeWheel.get(serverLevel);
        if (wheel.wasProcessedThisTick(pos)) {
            wheel.afterProcess(pos, blockEntity);
        }
    }

    @Redirect(
        method = "tryMoveInItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;setCooldown(I)V"
        )
    )
    private static void serverOptimize$onDestinationCooldown(HopperBlockEntity destination, int cooldown) {
        ((HopperBlockEntityAccessor) destination).setCooldown(cooldown);
        if (cooldown <= 0) return;
        Level level = destination.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (!serverLevel.tickRateManager().runsNormally()) return;
        HopperTimeWheel.get(serverLevel).noteCooldownSet(destination.getBlockPos(), cooldown);
    }

    @Redirect(
        method = "tryMoveItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;Ljava/util/function/BooleanSupplier;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;setCooldown(I)V"
        )
    )
    private static void serverOptimize$onSourceCooldown(HopperBlockEntity hopper, int cooldown) {
        ((HopperBlockEntityAccessor) hopper).setCooldown(cooldown);
        if (cooldown <= 0) return;
        Level level = hopper.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (!serverLevel.tickRateManager().runsNormally()) return;
        HopperTimeWheel.get(serverLevel).noteCooldownSet(hopper.getBlockPos(), cooldown);
    }

    @Inject(
        method = "tryMoveItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;Ljava/util/function/BooleanSupplier;)Z",
        at = @At("RETURN")
    )
    private static void serverOptimize$tryMoveItemsReturn(
            Level level, BlockPos pos, BlockState state,
            HopperBlockEntity blockEntity, BooleanSupplier supplier,
            CallbackInfoReturnable<Boolean> cir) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.tickRateManager().runsNormally()) return;
        if (!cir.getReturnValueZ()) return;
        HopperTimeWheel.get(serverLevel).markWork(pos, blockEntity);
    }

    @Inject(method = "ejectItems", at = @At("RETURN"))
    private static void serverOptimizeEjectReturn(Level level, BlockPos pos,
                                                                  HopperBlockEntity blockEntity,
                                                                  CallbackInfoReturnable<Boolean> cir) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.tickRateManager().runsNormally()) return;
        if (!cir.getReturnValueZ()) return;
        HopperTimeWheel.get(serverLevel).markEject(pos);
    }

    @Inject(method = "suckInItems", at = @At("RETURN"))
    private static void serverOptimizeSuckReturn(Level level, Hopper hopper,
                                                                 CallbackInfoReturnable<Boolean> cir) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.tickRateManager().runsNormally()) return;
        if (!cir.getReturnValueZ()) return;
        if (hopper instanceof HopperBlockEntity blockEntity) {
            HopperTimeWheel.get(serverLevel).markSuck(blockEntity.getBlockPos());
        }
    }

    @Inject(method = "loadAdditional", at = @At("RETURN"))
    private void serverOptimizeLoad(ValueInput input, CallbackInfo ci) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        HopperBlockEntity self = (HopperBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        HopperTimeWheel wheel = HopperTimeWheel.get(serverLevel);
        BlockPos pos = self.getBlockPos();
        int cooldownTime = ((HopperBlockEntityAccessor) self).getCooldown();
        if (!wheel.isRegistered(pos)) {
            wheel.onLoad(pos, cooldownTime);
        }
    }

    @Redirect(
        method = "saveAdditional",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/ValueOutput;putInt(Ljava/lang/String;I)V"
        )
    )
    private void serverOptimize$saveTransferCooldown(ValueOutput output, String key, int value) {
        if (!"TransferCooldown".equals(key) || !ModConfig.INSTANCE.hopper.enabled) {
            output.putInt(key, value);
            return;
        }

        HopperBlockEntity self = (HopperBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            output.putInt(key, value);
            return;
        }

        int cooldown = HopperTimeWheel.get(serverLevel)
            .getCooldownForSave(
                self.getBlockPos(),
                ((HopperBlockEntityAccessor) self).getCooldown()
            );
        output.putInt(key, cooldown);
    }
}
