package com.server_optimize.client.mixin;

import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(DebugScreenEntryList.class)
public interface DebugScreenEntryListAccessor {
    @Accessor("allStatuses")
    Map<Identifier, DebugScreenEntryStatus> serverOptimize$getAllStatuses();

    @Accessor("currentlyEnabled")
    List<Identifier> serverOptimize$getCurrentlyEnabled();
}
