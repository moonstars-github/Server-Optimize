package com.server_optimize.mixin.entity;

import com.server_optimize.config.ModConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractMinecartContainer.class)
public abstract class AbstractMinecartContainerMixin {
    @Shadow
    private ResourceKey<LootTable> lootTable;

    @Inject(method = "applyNaturalSlowdown", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$disableContentSlowdown(Vec3 velocity, CallbackInfoReturnable<Vec3> cir) {
        if (!ModConfig.INSTANCE.tweak.disableMinecartContentSlowdown) return;

        AbstractMinecart minecart = (AbstractMinecart)(Object)this;
        if (!(minecart.getBehavior() instanceof NewMinecartBehavior)) return;

        float factor = 0.98F;
        if (lootTable == null) {
            factor += 15.0F * 0.001F;
        }
        if (minecart.isInWater()) {
            factor *= 0.95F;
        }

        cir.setReturnValue(velocity.multiply(factor, 0.0, factor));
    }
}
