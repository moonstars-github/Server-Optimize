package com.server_optimize.client.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.util.ParticleThrottle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side particle throttling ([particle.limit.block]). Intercepts
 * {@code ClientLevel.doAddParticle} (the unified entry for client particles -
 * both packets from the server and local client-side emissions) so locally
 * rendered particles obey the same per-block caps as server-sent ones. Runs
 * on the render thread (the add path is O(1)); Sodium only replaces the
 * particle RENDER path (ParticleEngine render types), not the spawn entry,
 * so there is no interaction.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelParticleMixin {

    @Inject(
        method = "doAddParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void serverOptimize$throttleClientParticle(ParticleOptions options, boolean force,
                                                       boolean alwaysVisible, double x, double y, double z,
                                                       double dx, double dy, double dz, CallbackInfo ci) {
        if (ModConfig.INSTANCE == null || !ParticleThrottle.enabled()) {
            return;
        }
        if (!ParticleThrottle.throttleClient((ClientLevel) (Object) this, options, x, y, z)) {
            ci.cancel(); // block already holds its cap: don't spawn/render this particle
        }
    }
}