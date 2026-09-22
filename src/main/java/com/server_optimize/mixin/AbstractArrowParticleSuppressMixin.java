package com.server_optimize.mixin;

import com.server_optimize.util.EntityStackTracker;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Source-level particle suppression for the entity stacking display: a hidden
 * member of a collapsed stack does not emit its interaction/trail particles at
 * all (only the representative's particles reach the clients). Precise and
 * independent of positions, so a dense pile cannot confuse it.
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowParticleSuppressMixin {

    @Redirect(
        method = {
            "onHitEntity", "onHitBlock", "addBubbleParticles", "tick", "stepMoveAndHit"
        },
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V")
    )
    private void serverOptimize$suppressHiddenArrowParticles(Level level, ParticleOptions particle,
                                                             double x, double y, double z,
                                                             double dx, double dy, double dz) {
        if (!EntityStackTracker.isHiddenMember((AbstractArrow) (Object) this)) {
            level.addParticle(particle, x, y, z, dx, dy, dz);
        }
    }
}