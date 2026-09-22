package com.server_optimize.mixin.accessor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(Level.class)
public interface LevelAccessor {
    @Accessor("blockEntityTickers")
    List<TickingBlockEntity> serverOptimize$getBlockEntityTickers();

    @Accessor("pendingBlockEntityTickers")
    List<TickingBlockEntity> serverOptimize$getPendingBlockEntityTickers();

    @Accessor("tickingBlockEntities")
    boolean serverOptimize$isTickingBlockEntities();

    /**
     * Exposes the protected {@link Level#getEntities()} (both ServerLevel and
     * ClientLevel override it and return the same implementation, a
     * {@code LevelEntityGetterAdapter}). Used by the fast projectile hit test
     * to reach the entity section storage.
     */
    @Invoker("getEntities")
    LevelEntityGetter<Entity> serverOptimize$invokeGetEntities();
}
