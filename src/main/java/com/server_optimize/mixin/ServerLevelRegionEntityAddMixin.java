package com.server_optimize.mixin;

import com.server_optimize.thread.RegionScene;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Deferred entity creation for the region apply ([thread.multithread.regionbased]).
 *
 * <p>Anything that inserts an entity into the level - the region spawn pass (mob spawning),
 * block drops, and any other worker-side logic - must not touch the entity section storage
 * from a worker. While a region-apply worker is active, {@code ServerLevel.addFreshEntity}
 * records the entity in the region scene and returns success; the server thread re-adds them
 * after the region tasks join. This one hook is the funnel: the with-passengers default on
 * {@code ServerLevelAccessor} and {@code tryAddFreshEntityWithPassengers} both reach it once
 * per entity (entity and passengers), so jockey-style spawns are captured passenger by
 * passenger and replayed the same way.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRegionEntityAddMixin {

    @Inject(
        method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void serverOptimize$deferFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (RegionScene.active()) {
            RegionScene.deferEntity(entity, false);
            cir.setReturnValue(true);
        }
    }
}