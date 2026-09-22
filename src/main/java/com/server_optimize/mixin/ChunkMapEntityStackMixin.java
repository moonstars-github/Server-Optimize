package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.util.EntityStackTracker;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Entity stacking ([entity] stackDisplay): intercepts the vanilla
 * ChunkMap.addEntity/removeEntity entity-tracking entry points.
 * <ul>
 *   <li>addEntity: hidden members of a collapsed group are not tracked at
 *       all - no add packet, no per-tick updates, no remove packet ever
 *       reaches a client for them. The representative tracks normally.</li>
 *   <li>removeEntity: keeps the stacker's group bookkeeping in sync (count,
 *       representative promotion, group expansion below the threshold).</li>
 * </ul>
 * The stacker mutates tracking itself through ChunkMapAccessor @Invoker
 * calls (collapse/expand/promote), which bypass this wrapper to avoid
 * recursion - the wrapper only handles entities entering/leaving the world.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapEntityStackMixin {

    @WrapMethod(method = "addEntity")
    private void serverOptimize$stackingAdd(Entity entity, Operation<Void> original) {
        if (EntityStackTracker.onEntityAdd((ChunkMap) (Object) this, entity)) {
            // hidden member of a collapsed group: keep it untracked (the
            // tracker's mirror of vanilla state already says "untracked")
            return;
        }
        original.call(entity);
        EntityStackTracker.markTracked(entity, true);
    }

    @WrapMethod(method = "removeEntity")
    private void serverOptimize$stackingRemove(Entity entity, Operation<Void> original) {
        EntityStackTracker.onEntityRemove((ChunkMap) (Object) this, entity);
        original.call(entity);
        EntityStackTracker.markTracked(entity, false);
    }
}