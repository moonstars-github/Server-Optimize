package com.server_optimize.mixin.accessor;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/**
 * Static-field accessors for Entity's synched-data accessors. Mixin maps the
 * field names at runtime (the remapped jar uses intermediary field names, so
 * plain reflection with mojmap names fails in production - this is the
 * standard way to reach them).
 */
@Mixin(Entity.class)
public interface EntityDataAccessors {

    @Accessor("DATA_CUSTOM_NAME")
    static EntityDataAccessor<Optional<Component>> serverOptimize$customName() {
        throw new AssertionError();
    }

    @Accessor("DATA_CUSTOM_NAME_VISIBLE")
    static EntityDataAccessor<Boolean> serverOptimize$customNameVisible() {
        throw new AssertionError();
    }
}