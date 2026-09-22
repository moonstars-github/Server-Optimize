package com.server_optimize.client.mixin;

import com.server_optimize.client.HopperCooldownClientTracker;
import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import com.server_optimize.hopper.ServerOptimizeLevelAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.fallenbreath.tweakermore.impl.features.infoView.cache.RenderVisitorWorldView;

@Pseudo
@Mixin(targets = "me.fallenbreath.tweakermore.impl.features.infoView.hopper.HopperCooldownRenderer")
public class HopperCooldownRendererMixin {

    @Inject(
        method = "shouldRenderFor",
        at = @At("HEAD"),
        cancellable = true
    )
    private void serverOptimize$skipUnloaded(
            RenderVisitorWorldView worldView,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (!isSafeToRead(worldView, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(
        method = {"shouldRenderFor", "render"},
        at = @At(
            value = "INVOKE",
            target = "Lme/fallenbreath/tweakermore/impl/features/infoView/cache/RenderVisitorWorldView;method_8321(Lnet/minecraft/class_2338;)Lnet/minecraft/class_2586;",
            remap = false
        )
    )
    private BlockEntity serverOptimize$getBlockEntitySafely(
            RenderVisitorWorldView worldView,
            BlockPos pos) {
        if (!isSafeToRead(worldView, pos)) return null;
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : level.getBlockEntity(pos);
    }

    private static boolean isSafeToRead(RenderVisitorWorldView worldView, BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.isLoaded(pos);
    }

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lme/fallenbreath/tweakermore/mixins/tweaks/features/infoView/hopper/HopperBlockEntityAccessor;getTransferCooldown()I",
            remap = false
        )
    )
    private int serverOptimize$tweakmoreCooldown(
            me.fallenbreath.tweakermore.mixins.tweaks.features.infoView.hopper.HopperBlockEntityAccessor accessor) {
        HopperBlockEntity hopper = (HopperBlockEntity) (Object) accessor;
        if (ModConfig.INSTANCE == null
                || !ModConfig.INSTANCE.hopper.enabled
                || !ModConfig.INSTANCE.hopper.tweakmoreCompat) {
            return accessor.getTransferCooldown();
        }

        Level level = hopper.getLevel();
        BlockPos pos = hopper.getBlockPos();
        if (level instanceof ServerLevel serverLevel
                && serverLevel instanceof ServerOptimizeLevelAccess access) {
            HopperTimeWheel wheel = access.serverOptimize$getHopperTimeWheel();
            if (wheel != null && wheel.hasCooldown(pos)) {
                return wheel.getDisplaySnapshot(pos);
            }
        } else if (level != null && level.isClientSide()) {
            Minecraft client = Minecraft.getInstance();
            if (client.getSingleplayerServer() != null) {
                ServerLevel serverLevel = client.getSingleplayerServer().getLevel(level.dimension());
                if (serverLevel instanceof ServerOptimizeLevelAccess access) {
                    HopperTimeWheel wheel = access.serverOptimize$getHopperTimeWheel();
                    if (wheel != null && wheel.hasCooldown(pos)) {
                        return wheel.getDisplaySnapshot(pos);
                    }
                }
            }
            return HopperCooldownClientTracker.getDisplay(
                level,
                pos,
                accessor.getTransferCooldown()
            );
        }

        return accessor.getTransferCooldown();
    }
}
