package com.server_optimize.mixin.entity;

import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Container entity (hopper minecart) wake-up.
 * When a hopper minecart has items to output or is looking for input,
 * it wakes up nearby hoppers.
 */
@Mixin(MinecartHopper.class)
public abstract class MinecartHopperMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void serverOptimize$onTick(CallbackInfo ci) {
        if (!ModConfig.INSTANCE.hopper.enabled) return;

        MinecartHopper self = (MinecartHopper)(Object)this;
        if (!(self.level() instanceof ServerLevel serverLevel)) return;
        if (!self.isAlive()) return;

        HopperTimeWheel wheel = HopperTimeWheel.get(serverLevel);
        wakeHoppersAroundEntity(serverLevel, wheel, self);
    }

    private static void wakeHoppersAroundEntity(ServerLevel level, HopperTimeWheel wheel,
                                                MinecartHopper cart) {
        AABB box = cart.getBoundingBox();
        if (box == null) return;

        int minX = Mth.ceil(box.minX) - 1;
        int maxX = Mth.floor(box.maxX);
        int minY = Mth.ceil(box.minY) - 1;
        int maxY = Mth.floor(box.maxY);
        int minZ = Mth.ceil(box.minZ) - 1;
        int maxZ = Mth.floor(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos query = new BlockPos(x, y, z);
                    AABB queryBox = new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0);
                    if (!queryBox.intersects(box)) continue;
                    wakeHoppersUsingQueryCube(level, wheel, query);
                }
            }
        }
    }

    private static void wakeHoppersUsingQueryCube(ServerLevel level, HopperTimeWheel wheel,
                                                  BlockPos query) {
        for (Direction direction : Direction.values()) {
            BlockPos pos = query.relative(direction.getOpposite());
            if (!level.isLoaded(pos)) continue;
            if (!(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) continue;
            Direction facing = hopper.getBlockState().getValue(HopperBlock.FACING);
            boolean canInput = pos.above().equals(query);
            boolean canOutput = pos.relative(facing).equals(query);
            if (canInput || canOutput) {
                wheel.entityWake(pos);
            }
        }
    }
}
