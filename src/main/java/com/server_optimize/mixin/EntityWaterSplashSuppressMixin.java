package com.server_optimize.mixin;

import com.server_optimize.util.EntityStackTracker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * For a hidden member of a collapsed stack ([entity] stackDisplay), the whole
 * water-splash processing (sound AND particles) is bypassed: only the
 * representative processes entity particles. The splash is emitted from the
 * shared Entity.doWaterSplashEffect, which also plays the splash sound - both
 * are skipped for the hidden members, leaving a single entity's worth of
 * splash on the client.
 */
@Mixin(Entity.class)
public abstract class EntityWaterSplashSuppressMixin {

    @Inject(method = "doWaterSplashEffect", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$bypassHiddenSplash(CallbackInfo ci) {
        if (EntityStackTracker.isHiddenMember((Entity) (Object) this)) {
            ci.cancel();
        }
    }
}