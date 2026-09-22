package com.server_optimize.mixin;

import com.server_optimize.thread.EntityRegionTicker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defers entity removals requested during a per-region deferred entity tick
 * ([thread.multithread] entityTickParallel): the removal touches the shared entity
 * manager, so it is recorded and replayed by the server thread after the region workers
 * join.
 */
@Mixin(Entity.class)
public abstract class EntityRegionDiscardMixin {

    @Inject(method = "discard()V", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$deferDiscard(CallbackInfo ci) {
        if (EntityRegionTicker.deferredRun()) {
            EntityRegionTicker.deferDiscard((Entity) (Object) this);
            ci.cancel();
        }
    }
}