package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.ClassInstanceMultiMapAccessor;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Fast {@code EntitySection.getEntities(AABB, AbortableIterationConsumer)}.
 * <p>
 * This method is the hot path of the fast projectile hit test (and of every
 * box-based entity query): it showed ~45% of ServerThread time as a leaf
 * frame in the blaze benchmark. The vanilla loop allocates a wrapping
 * iterator per call and tests every entity's AABB through the
 * {@code AABB.intersects} method call. Here the backing
 * {@code ClassInstanceMultiMap.allInstances} list is iterated with indexed
 * access (no iterator allocation) and the AABB/box overlap test is inlined on
 * raw doubles with the exact vanilla strict-bound semantics.
 * <p>
 * Semantics are identical to vanilla: every entity is tested, the same
 * strict overlap predicate, the same abortable consumer and return value.
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionFastIterationMixin<T extends EntityAccess> {

    @Shadow
    @Final
    private ClassInstanceMultiMap<T> storage;

    @Inject(
        method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void serverOptimize$fastGetEntities(AABB box,
                                                AbortableIterationConsumer<T> consumer,
                                                CallbackInfoReturnable<AbortableIterationConsumer.Continuation> cir) {
        if (ModConfig.INSTANCE == null || !ModConfig.INSTANCE.entity.fastEntitySectionScan) {
            return; // config off: fall through to the vanilla implementation
        }
        List<T> all = ((ClassInstanceMultiMapAccessor<T>) this.storage).serverOptimize$getAllInstances();
        for (int i = 0; i < all.size(); i++) {
            T entity = all.get(i);
            AABB b = entity.getBoundingBox();
            // Exact mirror of AABB.intersects (strict bounds).
            if (b.minX < box.maxX && b.maxX > box.minX
                && b.minY < box.maxY && b.maxY > box.minY
                && b.minZ < box.maxZ && b.maxZ > box.minZ) {
                if (consumer.accept(entity).shouldAbort()) {
                    cir.setReturnValue(AbortableIterationConsumer.Continuation.ABORT);
                    return;
                }
            }
        }
        cir.setReturnValue(AbortableIterationConsumer.Continuation.CONTINUE);
    }
}
