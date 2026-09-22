package com.server_optimize.mixin.accessor;

import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private {@code storage} (ClassInstanceMultiMap) of
 * {@link EntitySection} so the fast many-hit test can iterate the backing
 * entity list directly (indexed access, no per-call method/lambda layers).
 */
@Mixin(EntitySection.class)
public interface EntitySectionAccessor<T extends EntityAccess> {

    @Accessor("storage")
    ClassInstanceMultiMap<T> serverOptimize$getStorage();
}
