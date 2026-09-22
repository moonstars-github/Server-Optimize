package com.server_optimize.mixin.accessor;

import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Direct field access to {@link ChunkAccess#isLightCorrect()} (private in
 * ChunkAccess, field_34549 in intermediary).
 * <p>
 * Used by LevelChunkLoadCleanMixin to copy the light-correct flag during
 * the disk-load constructor WITHOUT the vanilla {@code markUnsaved()}. The
 * accessor writes the field directly (mixin-generated putfield), so there is
 * no reflection that can throw and silently fall back to the dirty path —
 * unlike the previous {@code getDeclaredField("field_34549")} approach.
 */
@Mixin(ChunkAccess.class)
public interface ChunkAccessLightCorrectAccessor {

    @Accessor("isLightCorrect")
    void serverOptimize$setLightCorrect(boolean value);
}
