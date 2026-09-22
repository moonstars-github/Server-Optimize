package com.server_optimize.mixin.accessor;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HopperBlockEntity.class)
public interface HopperBlockEntityAccessor {
    @Accessor("cooldownTime")
    int getCooldown();
    @Accessor("cooldownTime")
    void setCooldown(int value);
    @Accessor("tickedGameTime")
    long getTickedGameTime();
    @Accessor("tickedGameTime")
    void setTickedGameTime(long value);
}
