package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import com.server_optimize.hopper.ServerOptimizeLevelAccess;
import com.server_optimize.mixin.accessor.HopperBlockEntityAccessor;
import com.server_optimize.util.ParticleThrottle;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements ServerOptimizeLevelAccess {
    @Unique
    private HopperTimeWheel serverOptimize$hopperTimeWheel;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void serverOptimize$initState(CallbackInfo ci) {
        this.serverOptimize$hopperTimeWheel = new HopperTimeWheel((ServerLevel)(Object)this);
    }

    @Override
    public HopperTimeWheel serverOptimize$getHopperTimeWheel() {
        return this.serverOptimize$hopperTimeWheel;
    }

    @Override
    public void serverOptimize$setHopperTimeWheel(HopperTimeWheel wheel) {
        this.serverOptimize$hopperTimeWheel = wheel;
    }

    /**
     * Particle throttling ([particle]). Cuts the emission count of
     * {@code ServerLevel.sendParticles} before the packet is built: when a
     * block already holds its per-block cap, further spawns are not
     * sent/processed. Rules and layered weighting live in
     * {@link ParticleThrottle}.
     */
    @WrapMethod(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I")
    private int serverOptimize$throttleParticles(ParticleOptions options, double x, double y, double z,
                                                 int count, double dx, double dy, double dz, double speed,
                                                 Operation<Integer> original) {
        if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.particle.onlyNearbyPlayers
            && !ParticleThrottle.anyPlayerNearby((ServerLevel) (Object) this, x, y, z)) {
            return 0;   // nobody who could see it: skip packet build + player walk
        }
        if (ModConfig.INSTANCE != null && ParticleThrottle.enabled() && count > 0) {
            int allowed = ParticleThrottle.throttle((ServerLevel) (Object) this, options, x, y, z, count);
            if (allowed <= 0) {
                return 0;
            }
            if (allowed < count) {
                return original.call(options, x, y, z, allowed, dx, dy, dz, speed);
            }
        }
        return original.call(options, x, y, z, count, dx, dy, dz, speed);
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V",
            shift = At.Shift.BEFORE
        )
    )
    private void serverOptimize$beforeBlockEntitiesTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;
        HopperTimeWheel wheel = this.serverOptimize$hopperTimeWheel;
        if (wheel != null) {
            wheel.onPreBlockEntitiesTick();
        }
    }

    @Inject(method = "addEntity", at = @At("RETURN"))
    private void serverOptimize$onEntityAdded(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        if (!(entity instanceof ItemEntity item)) return;
        if (!ModConfig.INSTANCE.hopper.enabled) return;

        HopperTimeWheel wheel = this.serverOptimize$hopperTimeWheel;
        if (wheel != null) {
            wheel.wakeForItem(item);
        }
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void serverOptimize$beforeChunkUnload(LevelChunk chunk, CallbackInfo ci) {
        if (ModConfig.INSTANCE == null || !ModConfig.INSTANCE.hopper.enabled) return;
        HopperTimeWheel wheel = this.serverOptimize$hopperTimeWheel;
        if (wheel == null) return;

        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity instanceof HopperBlockEntity hopper) {
                HopperBlockEntityAccessor accessor = (HopperBlockEntityAccessor) hopper;
                accessor.setCooldown(
                    wheel.getCooldownForSave(hopper.getBlockPos(), accessor.getCooldown())
                );
            }
        }
    }
}
