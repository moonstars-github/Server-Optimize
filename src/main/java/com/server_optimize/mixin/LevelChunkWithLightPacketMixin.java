package com.server_optimize.mixin;

import com.server_optimize.networking.SectionCulling;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;

/**
 * Builds the culled chunk payload cache entry when the vanilla chunk packet
 * is constructed on the server.
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class LevelChunkWithLightPacketMixin {

    @Inject(
        method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
        at = @At("RETURN")
    )
    private void serverOptimize$buildCulled(
        LevelChunk chunk, LevelLightEngine lightEngine, BitSet blockSkyLight, BitSet blockLight,
        CallbackInfo ci
    ) {
        SectionCulling.onChunkPacketBuilt((ClientboundLevelChunkWithLightPacket) (Object) this, chunk);
    }
}
