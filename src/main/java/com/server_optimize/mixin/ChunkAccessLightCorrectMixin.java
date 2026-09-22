package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Light-correct no-op save suppression (chunk.skipCleanChunkSaves).
 * <p>
 * {@link ChunkAccess#setLightCorrect(boolean)} unconditionally calls
 * {@code markUnsaved()} — including when the flag does not actually change.
 * A chunk loaded from disk with complete stored light already has
 * lightCorrect = true (set by SerializableChunkData.read). If the light
 * engine later calls setLightCorrect(true) again (e.g. propagation-confirm
 * path), the vanilla code marks the chunk dirty even though its light state
 * is identical to what was just loaded. That chunk is then written back on
 * unload, making save throughput equal load throughput (ChunkRegionWrite ≈
 * ChunkRegionRead in the loading JFR) and doubling the NBT serialization
 * allocation pressure.
 * <p>
 * This injects at the HEAD of setLightCorrect and no-ops the call when:
 * {@code value == true && this.isLightCorrect() == true} — i.e. the flag is
 * already true and stays true, so nothing changes and no save is needed.
 * Everything else (true→false during light invalidation, false→true after
 * real propagation, all writes on generated chunks) keeps the vanilla path.
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessLightCorrectMixin {

    @Inject(method = "setLightCorrect", at = @At("HEAD"), cancellable = true)
    private void serverOptimize$skipNoopLightCorrect(boolean value, CallbackInfo ci) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.skipCleanChunkSaves) {
            return;
        }
        ChunkAccess self = (ChunkAccess)(Object)this;
        if (value && self.isLightCorrect()) {
            // Flag already true, stays true: no state change, no save needed.
            ci.cancel();
        }
    }
}