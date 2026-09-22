package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.ChunkAccessLightCorrectAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Clean loaded chunks stay clean (chunk.skipCleanChunkSaves).
 *
 * The LevelChunk(ServerLevel, ProtoChunk, PostLoadProcessor) constructor —
 * the disk-load assembly path — copies the light state from the ProtoChunk
 * via setLightCorrect(), which unconditionally calls markUnsaved(), and then
 * calls markUnsaved() again at the end. Both dirty a chunk that was just
 * loaded from disk unchanged, so it gets written back on unload (making save
 * throughput equal load throughput).
 *
 * Two injections:
 *  <ol>
 *    <li>@{@link #serverOptimize$skipLoadMarkUnsaved} redirects the trailing
 *        markUnsaved() (the second dirty).</li>
 *    <li>@{@link #serverOptimize$skipLoadSetLightCorrect} redirects the
 *        setLightCorrect() call so the field is set WITHOUT the internal
 *        markUnsaved — the light state is copied, the chunk stays clean.
 *        The field is written through a mixin @Accessor (direct putfield),
 *        never reflection, so there is no failure path that silently falls
 *        back to the dirty vanilla call.</li>
 *  </ol>
 *
 * Both are skipped only when the stored light is complete (isLightCorrect),
 * the same condition the trusted-light skip uses. Real modifications still
 * mark chunks through setBlockState, and chunks without complete light keep
 * the vanilla mark.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkLoadCleanMixin {

    @Unique
    private static void serverOptimize$setLightCorrectWithoutSave(LevelChunk self, boolean value) {
        // Direct putfield via the accessor — no reflection, no fallback.
        ((ChunkAccessLightCorrectAccessor) (Object) self).serverOptimize$setLightCorrect(value);
    }

    @Unique
    private static boolean serverOptimize$shouldStayClean(LevelChunk self) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.skipCleanChunkSaves) {
            return false;
        }
        return self.isLightCorrect();
    }

    @Redirect(
        method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setLightCorrect(Z)V")
    )
    private void serverOptimize$skipLoadSetLightCorrect(LevelChunk self, boolean value) {
        ModConfig cfg = ModConfig.INSTANCE;
        // value==true: disk-load ProtoChunk already carries complete stored
        // light (NBT isLightOn). Copy the flag WITHOUT markUnsaved. The
        // trailing markUnsaved() call in the same constructor is handled by
        // skipLoadMarkUnsaved. value==false (fresh generation) keeps the
        // vanilla dirty mark so the new chunk still gets saved.
        if (cfg != null && cfg.chunk.skipCleanChunkSaves && value) {
            serverOptimize$setLightCorrectWithoutSave(self, value);
        } else {
            self.setLightCorrect(value);
        }
    }

    @Redirect(
        method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;markUnsaved()V")
    )
    private void serverOptimize$skipLoadMarkUnsaved(LevelChunk self) {
        if (!serverOptimize$shouldStayClean(self)) {
            self.markUnsaved();
        }
    }
}