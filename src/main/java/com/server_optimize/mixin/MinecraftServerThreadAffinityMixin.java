package com.server_optimize.mixin;

import com.server_optimize.thread.AffinityManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the dedicated/integrated server thread ("Server thread") to its
 * dedicated core ([thread] pinServerThread). MinecraftServer.runServer runs
 * ON the server thread, so the affinity call applies to the current thread.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerThreadAffinityMixin {

    @Inject(method = "runServer", at = @At("HEAD"))
    private void serverOptimize$pinServerThread(CallbackInfo ci) {
        AffinityManager.applyServerThread();
    }
}