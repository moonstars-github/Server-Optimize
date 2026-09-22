package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.thread.EntityRegionTicker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Per-region entity tick (part of [thread.multithread.regionbased]).
 *
 * <p>Each entity tick runs through {@code ServerLevel.tickNonPassenger} on the server
 * thread. With the region engine on, the per-region deferral records the slow core entities
 * instead of ticking them here; the region workers run the vanilla per-entity tick after the
 * level tick (see {@link EntityRegionTicker}), and the removals they request are replayed at
 * the flush. Everything else keeps the vanilla serial tick. The separate
 * {@code thread.multithread.entityTickParallel} option is the within-region further split
 * for a single region's ultra-high entity load, built on top of this.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityRegionMixin {

    @WrapMethod(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V")
    private void serverOptimize$regionEntityTick(Entity entity, Operation<Void> original) {
        if (!EntityRegionTicker.deferredRun()
            && EntityRegionTicker.defer((ServerLevel) (Object) this, entity)) {
            return; // the region worker ticks it
        }
        original.call(entity);
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
    private void serverOptimize$flushEntityRegions(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        EntityRegionTicker.flush((ServerLevel) (Object) this);
    }
}