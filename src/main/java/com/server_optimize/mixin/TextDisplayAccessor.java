package com.server_optimize.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The synched-data accessor that carries a TextDisplay's text. The field is private
 * in vanilla, so the stacking display goes through this to set and read the count.
 */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {

    @Accessor("DATA_TEXT_ID")
    static EntityDataAccessor<Component> serverOptimize$textId() {
        throw new AssertionError();
    }
}