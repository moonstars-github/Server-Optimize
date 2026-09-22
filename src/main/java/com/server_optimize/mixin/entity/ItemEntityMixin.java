package com.server_optimize.mixin.entity;

import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wakes hoppers by the same suck AABB used by vanilla hopper item collection.
 */
@Mixin(ItemEntity.class)
public class ItemEntityMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void serverOptimize$onItemTick(CallbackInfo ci) {
        ItemEntity self = (ItemEntity)(Object)this;
        if (!self.isAlive()) return;
        if (!(self.level() instanceof ServerLevel serverLevel)) return;
        if (!ModConfig.INSTANCE.hopper.enabled) return;

        HopperTimeWheel.get(serverLevel).wakeForItem(self);
    }
}
