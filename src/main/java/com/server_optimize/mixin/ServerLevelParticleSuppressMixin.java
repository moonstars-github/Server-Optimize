package com.server_optimize.mixin;

import com.server_optimize.util.EntityStackTracker;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Particle suppression for the entity stacking display ([entity] stackDisplay):
 * when a cluster is collapsed, its hidden members' interaction particles are
 * suppressed at the send-to-clients step, so the clients only see the
 * representative's particles (less client noise and network traffic). A
 * general fallback alongside the source-level arrow/entity mixins.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelParticleSuppressMixin {

    @Inject(
        method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I",
        at = @At("HEAD"),
        cancellable = true
    )
    private void serverOptimize$suppressHiddenParticles(ParticleOptions options, boolean force,
                                                       boolean includeBig, double x, double y,
                                                       double z, int count, double dx, double dy,
                                                       double dz, double speed,
                                                       CallbackInfoReturnable<Integer> cir) {
        if (EntityStackTracker.shouldSuppressParticle(x, y, z)) {
            cir.setReturnValue(0);
        }
    }
}