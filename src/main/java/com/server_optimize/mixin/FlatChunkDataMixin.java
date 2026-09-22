package com.server_optimize.mixin;

import com.mojang.serialization.Codec;
import com.server_optimize.config.ModConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

/**
 * Flat chunk data format (chunk.flatChunkData).
 * <p>
 * {@link SerializableChunkData#parse} reads several root fields through
 * {@link CompoundTag#read(String, Codec)}, which allocates a DataResult +
 * Pair per call even for trivial fields. This mixin @Redirects those calls
 * to direct typed access where the codec is a plain registry lookup or a
 * simple decode:
 * <ul>
 *   <li>{@code Status} → {@link BuiltInRegistries#CHUNK_STATUS} lookup from
 *       the raw string ({@code Registry.getOptional} returns
 *       {@code Optional<ChunkStatus>} directly — no DataResult).</li>
 *   <li>{@code blending_data} / {@code below_zero_retrogen} → skip the codec
 *       entirely when the tag is absent (the common case for non-blended,
 *       non-retrogen chunks).</li>
 * </ul>
 * Sections/heightmaps/ticks/entities still use the vanilla codec; those are
 * already parsed as compact lists and the codec overhead there is small.
 */
@Mixin(SerializableChunkData.class)
public abstract class FlatChunkDataMixin {

    @Unique
    private static Optional<?> serverOptimize$flatRead(CompoundTag tag, String key, Codec<?> codec) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.flatChunkData) {
            return tag.read(key, codec);
        }

        switch (key) {
            case "Status": {
                // Registry.getOptional(String id) returns Optional<ChunkStatus>
                // — the same type parse() expects from the codec, without any
                // DataResult/Pair allocation.
                String statusStr = tag.getStringOr("Status", "");
                if (statusStr.isEmpty()) {
                    return Optional.of(ChunkStatus.EMPTY);
                }
                Identifier id = Identifier.tryParse(statusStr);
                if (id == null) {
                    return Optional.of(ChunkStatus.EMPTY);
                }
                return BuiltInRegistries.CHUNK_STATUS
                    .getOptional(id)
                    .map(s -> (ChunkStatus) s);
            }
            case "blending_data":
            case "below_zero_retrogen": {
                // Absent is the overwhelmingly common case; return empty
                // without touching the codec (parse() uses orElse(null)).
                if (!tag.contains(key)) {
                    return Optional.empty();
                }
                return tag.read(key, codec);
            }
            case "block_ticks":
            case "fluid_ticks": {
                if (!tag.contains(key)) {
                    return Optional.of(java.util.List.of());
                }
                return tag.read(key, codec);
            }
            default:
                return tag.read(key, codec);
        }
    }

    @Redirect(
        method = "parse",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;read(Ljava/lang/String;Lcom/mojang/serialization/Codec;)Ljava/util/Optional;")
    )
    private static Optional<?> serverOptimize$flatReadRedirect(CompoundTag tag, String key, Codec<?> codec) {
        return serverOptimize$flatRead(tag, key, codec);
    }
}