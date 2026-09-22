package com.server_optimize.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.server_optimize.config.ModConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.Strategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

/**
 * Fast NBT parse path for block-state paletted containers (chunk.fastChunkParsing).
 *
 * Vanilla SerializableChunkData.parse() decodes every section's block_states
 * through the full codec stack: RecordCodecBuilder dynamic dispatch, listOf
 * element wrappers, ExtraCodecs.orElsePartial, DataResult plumbing and the
 * generic unpack. With 127 sections per 2032-height chunk that is the bulk of
 * the load-side CPU.
 *
 * This codec replaces the vanilla one only inside parse() (the redirect is
 * scoped to that method) and:
 *  - short-circuits the overwhelmingly common cases: missing palette
 *    (vanilla orElsePartial fallback) and a single "minecraft:air" entry
 *    without storage -> the ready-made air container, zero codec work;
 *  - otherwise reads palette entries directly (each entry still decoded with
 *    the vanilla BlockState.CODEC for full compatibility) and hands the raw
 *    list + storage to the vanilla PalettedContainer.unpack(), skipping the
 *    codec framework layers.
 *
 * Failures fall back to the same air container the vanilla orElsePartial
 * would produce, so a decode problem can never corrupt a chunk.
 */
public final class FastPaletteCodec {

    private FastPaletteCodec() {
    }

    public static Codec<PalettedContainer<BlockState>> blockStates(PalettedContainerFactory factory) {
        Strategy<BlockState> strategy = factory.blockStatesStrategy();
        BlockState fallback = factory.defaultBlockState();
        Codec<PalettedContainer<BlockState>> vanilla = factory.blockStatesContainerCodec();
        return new Codec<PalettedContainer<BlockState>>() {
            @Override
            public <T> DataResult<com.mojang.datafixers.util.Pair<PalettedContainer<BlockState>, T>> decode(
                DynamicOps<T> ops, T input) {
                return parse(ops, input)
                    .map(a -> com.mojang.datafixers.util.Pair.of(a, input));
            }

            @Override
            public <T> DataResult<PalettedContainer<BlockState>> parse(DynamicOps<T> ops, T input) {
                if (!(input instanceof CompoundTag tag)) {
                    return DataResult.error(() -> "FastPaletteCodec: expected a CompoundTag");
                }
                try {
                    ListTag paletteTag = tag.getListOrEmpty("palette");
                    if (paletteTag.isEmpty()) {
                        return airResult(strategy, fallback);
                    }
                    // Single-entry palette without storage: the shared
                    // empty-section / single-block case. Build the container
                    // directly instead of running the whole unpack codec path
                    // (no per-entry DataResult, no LongStream, no temporary
                    // list) - by far the most common shape in a loaded world.
                    if (paletteTag.size() == 1 && !tag.contains("data")) {
                        CompoundTag entry = paletteTag.getCompound(0).orElse(null);
                        if (entry != null) {
                            BlockState single = directState(entry);
                            if (single != null) {
                                return singleResult(single, strategy);
                            }
                            Optional<BlockState> o = BlockState.CODEC.parse(NbtOps.INSTANCE, entry).result();
                            if (o.isPresent()) {
                                return singleResult(o.get(), strategy);
                            }
                        }
                        return airResult(strategy, fallback);
                    }
                    List<BlockState> palette = new ArrayList<>(paletteTag.size());
                    for (Tag t : paletteTag) {
                        // Registry fast path: an entry without Properties maps
                        // straight to the block's default state, no codec work.
                        if (t instanceof CompoundTag c) {
                            BlockState direct = directState(c);
                            if (direct != null) {
                                palette.add(direct);
                                continue;
                            }
                        }
                        DataResult<BlockState> r = BlockState.CODEC.parse(NbtOps.INSTANCE, t);
                        Optional<BlockState> o = r.result();
                        if (o.isEmpty()) {
                            return airResult(strategy, fallback);
                        }
                        palette.add(o.get());
                    }
                    Optional<LongStream> storage = tag.getLongArray("data")
                        .map(java.util.Arrays::stream);
                    DataResult<PalettedContainer<BlockState>> result = PalettedContainer.unpack(strategy,
                        new PalettedContainerRO.PackedData<>(palette, storage));
                    return result;
                } catch (Exception e) {
                    return airResult(strategy, fallback);
                }
            }

            @Override
            public <T> DataResult<T> encode(PalettedContainer<BlockState> input, DynamicOps<T> ops, T prefix) {
                return encodeStart(ops, input)
                    .map(x -> (T) prefix)
                    .map(x -> x);  // keep prefix tag as the container
            }

            @Override
            public <T> DataResult<T> encodeStart(DynamicOps<T> ops, PalettedContainer<BlockState> container) {
                if (!(ops instanceof NbtOps)
                    || ModConfig.INSTANCE == null || !ModConfig.INSTANCE.chunk.fastChunkParsing) {
                    return vanilla.encodeStart(ops, container);
                }
                try {
                    // Uniform section fast path: single palette entry, no
                    // packed storage (all-air / single-block). This is the
                    // dominant shape in pre-generated terrain and avoids the
                    // whole codec chain (CompressorHolder + DataResult.map +
                    // setLifecycle) that write() pays per section.
                    PalettedContainerRO.PackedData<BlockState> packed = container.pack(strategy);
                    if (packed.storage().isEmpty()) {
                        java.util.List<BlockState> palette = packed.paletteEntries();
                        if (palette.size() == 1) {
                            CompoundTag tag = new CompoundTag();
                            ListTag paletteList = new ListTag();
                            paletteList.add(writeState(palette.get(0)));
                            tag.put("palette", paletteList);
                            return DataResult.success((T) tag);
                        }
                    }
                    return vanilla.encodeStart(ops, container);
                } catch (Exception e) {
                    return vanilla.encodeStart(ops, container);
                }
            }
        };
    }

    /**
     * Registry fast path for a palette entry: "Name" -> Block -> default
     * state. Entries with a "Properties" map (or anything else non-trivial)
     * return null and go through the vanilla BlockState codec unchanged, so
     * only the trivial entries avoid the codec allocation.
     */
    private static BlockState directState(CompoundTag entry) {
        try {
            if (entry.contains("Properties")) {
                return null;
            }
            String name = entry.getString("Name").orElse("");
            if (name.isEmpty()) {
                return null;
            }
            Identifier id = Identifier.tryParse(name);
            if (id == null) {
                return null;
            }
            Block block = BuiltInRegistries.BLOCK.get(id)
                .map(net.minecraft.core.Holder.Reference::value).orElse(null);
            return block == null ? null : block.defaultBlockState();
        } catch (Exception e) {
            return null;
        }
    }

    private static PalettedContainer<BlockState> air(Strategy<BlockState> strategy, BlockState fallback) {
        return new PalettedContainer<>(fallback, strategy);
    }

    private static DataResult<PalettedContainer<BlockState>> airResult(Strategy<BlockState> strategy,
        BlockState fallback) {
        return DataResult.success(air(strategy, fallback));
    }

    private static DataResult<PalettedContainer<BlockState>> singleResult(BlockState single,
        Strategy<BlockState> strategy) {
        return DataResult.success(new PalettedContainer<>(single, strategy));
    }

    /**
     * Write one BlockState as a vanilla "palette" entry ("Name" + optional
     * "Properties"). Mirrors the vanilla BlockState codec NBT layout.
     */
    private static Tag writeState(BlockState state) {
        CompoundTag tag = new CompoundTag();
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        tag.putString("Name", id.toString());
        if (!state.getValues().isEmpty()) {
            CompoundTag props = new CompoundTag();
            state.getValues().forEach((prop, value) ->
                props.putString(prop.getName(), String.valueOf(value)));
            tag.put("Properties", props);
        }
        return tag;
    }
}
