package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Pre-allocate the backing map of {@link CompoundTag} (chunk.tagMapCapacity).
 * <p>
 * The original no-arg-constructor swap paid the preallocation for EVERY
 * {@code new CompoundTag()} - including the tens of thousands of transient,
 * never-written tags the worldgen workers create during chunk generation,
 * which showed up as the top worldgen allocation site in the generation JFR
 * (the fastutil map's Object[] backing allocated per tag, most of them never
 * filled). The swap now happens lazily on the FIRST {@code put}: an empty or
 * read-only tag keeps the vanilla lazy map (nothing allocated), and a tag
 * that is actually written swaps its backing map to the preallocated fastutil
 * map at the target capacity - copying the few entries already present - so
 * the filled tags still never rehash. The {@code (Map)} constructor
 * right-sizing is unchanged.
 */
@Mixin(CompoundTag.class)
public abstract class CompoundTagPreallocateMixin {

    @Unique
    private static final Field TAGS_FIELD;

    static {
        Field f = null;
        try {
            // production (intermediary): field_11515; dev (yarn): tags
            try {
                f = CompoundTag.class.getDeclaredField("field_11515");
            } catch (NoSuchFieldException e) {
                f = CompoundTag.class.getDeclaredField("tags");
            }
            f.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot access CompoundTag.tags", e);
        }
        TAGS_FIELD = f;
    }

    // The preallocated fastutil type both this swap and lithium's
    // useFasterCollection install; checking the type skips a repeat swap.
    @Inject(
        method = "put(Ljava/lang/String;Lnet/minecraft/nbt/Tag;)Lnet/minecraft/nbt/Tag;",
        at = @At("HEAD")
    )
    private void serverOptimize$lazyPreallocateTagMap(String key, Tag tag,
                                                      CallbackInfoReturnable<Tag> cir) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || cfg.chunk.tagMapCapacity <= 0) {
            return;
        }
        Map<String, Tag> existing;
        try {
            existing = (Map<String, Tag>) TAGS_FIELD.get(this);
        } catch (IllegalAccessException e) {
            return;
        }
        if (existing instanceof it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap) {
            return; // already the preallocated (or lithium's) map
        }
        try {
            // Right-size on the first write: the empty tags of the generation
            // path never reach here, the filled ones get the rehash-free map.
            it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, Tag> fresh =
                new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>(
                    Math.max(cfg.chunk.tagMapCapacity, existing.size() + 1));
            fresh.putAll(existing);
            TAGS_FIELD.set(this, fresh);
        } catch (IllegalAccessException e) {
            // cannot happen (setAccessible(true) above)
        }
    }

    /**
     * Right-sizing for the {@code CompoundTag(Map)} constructor, which the
     * codec/serializer paths use. Vanilla keeps the map instance it is handed
     * (the tag and the caller then share it), so this may only replace an
     * EMPTY plain HashMap - an empty map carries no state, and its only role
     * was to hint a capacity. Non-empty maps are left completely untouched:
     * replacing one without copying its entries silently produced empty tags,
     * which made level.dat unreadable ("Unknown data version: 0") in an
     * earlier build of this option.
     */
    @Inject(method = "<init>(Ljava/util/Map;)V", at = @At("TAIL"))
    private void serverOptimize$rightSizeTagMap(Map<String, Tag> initial, CallbackInfo ci) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || cfg.chunk.tagMapCapacity <= 0 || !cfg.chunk.rightSizeTagMapConstructor
            || !(initial instanceof java.util.HashMap) || !initial.isEmpty()) {
            return;
        }
        try {
            TAGS_FIELD.set(this, new Object2ObjectOpenHashMap<String, Tag>(cfg.chunk.tagMapCapacity, 0.75f));
        } catch (IllegalAccessException e) {
            // cannot happen (setAccessible(true) above)
        }
    }
}