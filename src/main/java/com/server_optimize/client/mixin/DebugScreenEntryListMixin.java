package com.server_optimize.client.mixin;

import com.server_optimize.client.debug.ServerOptimizeDebugEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenEntryList.class)
public class DebugScreenEntryListMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void serverOptimize$ensureDebugEntries(CallbackInfo ci) {
        ServerOptimizeDebugEntries.ensureStatuses((DebugScreenEntryList) (Object) this);
    }

    @Inject(method = "rebuildCurrentList", at = @At("TAIL"))
    private void serverOptimize$orderDebugEntries(CallbackInfo ci) {
        ServerOptimizeDebugEntries.reorderCurrentList((DebugScreenEntryList) (Object) this);
    }
}
