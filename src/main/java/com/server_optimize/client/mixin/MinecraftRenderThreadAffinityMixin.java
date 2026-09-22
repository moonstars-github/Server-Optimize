package com.server_optimize.client.mixin;

import com.server_optimize.thread.AffinityManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the client render thread ("Render thread") to its dedicated core
 * ([thread] pinRenderThread). Minecraft.run runs ON the render thread, so
 * the affinity call applies to the current thread.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftRenderThreadAffinityMixin {

    @Inject(method = "run", at = @At("HEAD"))
    private void serverOptimize$pinRenderThread(CallbackInfo ci) {
        AffinityManager.applyRenderThread();
    }
}