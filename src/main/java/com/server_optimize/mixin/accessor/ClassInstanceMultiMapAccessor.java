package com.server_optimize.mixin.accessor;

import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes the raw backing {@code allInstances} list of
 * {@link ClassInstanceMultiMap} so the fast entity-section scan can iterate
 * it directly (indexed access) instead of going through the wrapping
 * iterator that vanilla allocates per query.
 */
@Mixin(ClassInstanceMultiMap.class)
public interface ClassInstanceMultiMapAccessor<T> {

    @Accessor("allInstances")
    List<T> serverOptimize$getAllInstances();
}
