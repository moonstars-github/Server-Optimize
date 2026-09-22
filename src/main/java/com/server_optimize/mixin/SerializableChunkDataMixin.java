package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.ChunkAccessLightCorrectAccessor;
import com.server_optimize.util.FastPaletteCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;

/**
 * Save serialization allocation trimming (chunk.saveWritePreallocatedTags).
 *
 * SerializableChunkData.write() creates the root CompoundTag with the
 * vanilla no-arg constructor (HashMap with default capacity 16), then fills
 * it with dozens of keys (heightmaps, all 131 sections, block entities...),
 * forcing repeated HashMap rehashes. The root tag is redirected to the
 * package-private CompoundTag(Map) constructor with a pre-sized HashMap
 * (512 entries), eliminating the rehash chain.
 *
 * The NBT output is byte-identical: only the backing map capacity changes.
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {

    @Unique
    private static final MethodHandle NEW_TAG;

    static {
        MethodHandle h;
        try {
            java.lang.reflect.Constructor<CompoundTag> ctor =
                CompoundTag.class.getDeclaredConstructor(Map.class);
            ctor.setAccessible(true);
            h = MethodHandles.lookup().unreflectConstructor(ctor);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        NEW_TAG = h;
    }

    @Redirect(
        method = "write",
        at = @At(value = "NEW", target = "Lnet/minecraft/nbt/CompoundTag;")
    )
    private CompoundTag serverOptimize$preallocatedRootTag() {
        if (ModConfig.INSTANCE == null || !ModConfig.INSTANCE.chunk.saveWritePreallocatedTags) {
            return new CompoundTag();
        }
        try {
            // 1024 initial capacity + 0.75 load factor = 768 entries before resize.
            // A full chunk's root tag has ~300 entries (131 sections x 2 + misc),
            // so no resize ever. 1024 is generously safe.
            return (CompoundTag) NEW_TAG.invoke(new HashMap<String, Tag>(1024));
        } catch (Throwable t) {
            return new CompoundTag();
        }
    }

    /**
     * Fast block-states parse path (chunk.fastChunkParsing): inside parse()
     * the per-section block_states codec is swapped for the hand-rolled
     * FastPaletteCodec (air-section short-circuit + direct palette read +
     * vanilla unpack). write() and every other caller keep the vanilla codec.
     */
    @Redirect(
        method = "parse",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainerFactory;blockStatesContainerCodec()Lcom/mojang/serialization/Codec;")
    )
    private static com.mojang.serialization.Codec<?> serverOptimize$fastBlockStatesCodec(PalettedContainerFactory factory) {
        if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.chunk.fastChunkParsing) {
            return FastPaletteCodec.blockStates(factory);
        }
        return factory.blockStatesContainerCodec();
    }

    /**
     * Fast block-states encode path (chunk.fastChunkParsing): the same
     * hand-rolled codec is used in write() so the per-section
     * block_states encode of uniform (single-palette, no-storage) sections
     * skips the codec chain (CompressorHolder + DataResult.map/setLifecycle,
     * ~9% of save-pool allocation - the largest single category there).
     */
    @Redirect(
        method = "write",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainerFactory;blockStatesContainerCodec()Lcom/mojang/serialization/Codec;")
    )
    private static com.mojang.serialization.Codec<?> serverOptimize$fastBlockStatesCodecWrite(PalettedContainerFactory factory) {
        if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.chunk.fastChunkParsing) {
            return FastPaletteCodec.blockStates(factory);
        }
        return factory.blockStatesContainerCodec();
    }

    /**
     * Disk-load clean-state fix (chunk.skipCleanChunkSaves).
     * <p>
     * When a chunk was saved as a full LevelChunk (ChunkType.LEVELCHUNK —
     * every pre-generated / persisted chunk), {@link SerializableChunkData#read}
     * constructs the LevelChunk directly via the big
     * {@code LevelChunk(Level, ChunkPos, UpgradeData, ...)} constructor. That
     * constructor does NOT copy the light-correct flag, so the chunk starts
     * with isLightCorrect = false even though its NBT has complete stored light.
     * <p>
     * The light engine then sees light is missing and calls
     * {@code setLightCorrect(true)} → the vanilla method unconditionally
     * {@code markUnsaved()}, dirtying a chunk whose data is byte-for-byte what
     * was read from disk. It gets written back on unload (write-back ≈ load
     * throughput, ~99.97% of saves were light=true chunks in 15.5).
     * <p>
     * Here at read() tail we restore the true light state (from the NBT-derived
     * {@code lightCorrect} getter) on BOTH the ImposterProtoChunk and its
     * wrapped LevelChunk directly via the accessor — no markUnsaved — and
     * clear the wrapped chunk's leftover dirty flag. The later
     * {@code setLightCorrect(true)} is then suppressed by
     * ChunkAccessLightCorrectMixin (value && already true), so the chunk stays
     * clean until a real player edit.
     * <p>
     * Only the ImposterProtoChunk branch (full saved chunks) is touched;
     * partially-generated chunks (ProtoChunk branch) and fresh generation are
     * handled by ChunkStatusTasksFullMixin / stay vanilla-dirty respectively.
     */
    @Inject(method = "read", at = @At("TAIL"))
    private void serverOptimize$fixDiskLoadedLight(
        ServerLevel level, PoiManager poiManager, RegionStorageInfo info, ChunkPos pos,
        CallbackInfoReturnable<ProtoChunk> cir
    ) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.skipCleanChunkSaves) {
            return;
        }
        ProtoChunk result = cir.getReturnValue();
        if (result instanceof ImposterProtoChunk imp) {
            // ImposterProtoChunk.setLightCorrect() delegates to the wrapped
            // LevelChunk and does NOT update its own isLightCorrect field, so
            // isLightCorrect() stays false on the imposter itself. When the
            // imposter is later promoted by ChunkStatusTasks.method_60553 the
            // LevelChunk(ServerLevel, ProtoChunk, ...) constructor calls
            // setLightCorrect(proto.isLightCorrect()) with that false and the
            // vanilla path unconditionally markUnsaved()s a disk-identical
            // chunk. Write the flag on BOTH objects directly (accessor, no
            // markUnsaved) and clear the wrapped chunk's leftover dirty mark.
            boolean light = ((SerializableChunkData) (Object) this).lightCorrect();
            ((ChunkAccessLightCorrectAccessor) (Object) imp).serverOptimize$setLightCorrect(light);
            LevelChunk wrapped = imp.getWrapped();
            ((ChunkAccessLightCorrectAccessor) (Object) wrapped).serverOptimize$setLightCorrect(light);
            wrapped.tryMarkSaved();
            if (ModConfig.INSTANCE != null && ModConfig.INSTANCE.log.chunkIO) {
                com.server_optimize.ServerOptimize.LOGGER.info("[diag] readTAIL: imposter light={} wrappedClass={}",
                    light, wrapped.getClass().getSimpleName());
            }
        }
    }
}
