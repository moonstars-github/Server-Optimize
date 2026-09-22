package com.server_optimize.mixin.accessor;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private {@code sectionStorage} of
 * {@link LevelEntityGetterAdapter} so the fast projectile hit test can
 * enumerate entity sections directly (ray-ordered early exit) instead of
 * going through the vanilla box query that builds a candidate List.
 * <p>
 * Both {@code ServerLevel} and {@code ClientLevel} build their entity getter
 * as a {@code LevelEntityGetterAdapter}, so a single accessor covers both
 * sides.
 */
@Mixin(LevelEntityGetterAdapter.class)
public interface LevelEntityGetterAdapterAccessor<T extends EntityAccess> {

    @Accessor("sectionStorage")
    EntitySectionStorage<T> serverOptimize$getSectionStorage();
}
