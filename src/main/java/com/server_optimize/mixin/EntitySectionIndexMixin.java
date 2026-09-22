package com.server_optimize.mixin;

import com.server_optimize.index.HittableSpaceIndex;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Three-level hittable-entity index on {@link EntitySection}: 16^3 section
 * (L1) -> 4^3 sub-region (L2) -> single block (L3). Only entities with
 * isPickable() == true are stored; arrows/snowballs/tridents stay out, so
 * projectile hit tests never scan them (breaker of the O(N^2) arrow cloud).
 * Buckets are keyed by (L2 << 6) | L3 of the block position (section origin
 * is 16-aligned, so blockPos & 15 is the in-section coordinate).
 * Maintained on add/remove; stale buckets self-clean during iteration.
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionIndexMixin<T extends EntityAccess> implements HittableSpaceIndex {

    @Unique
    private int serverOptimize$hittableCount;

    @Unique
    private Int2ObjectOpenHashMap<List<Entity>> serverOptimize$hittableBuckets;

    /** (L2 6-bit in 4^3) << 6 | (L3 6-bit single block). */
    @Unique
    private static int serverOptimize$bucketKey(int bx, int by, int bz) {
        int l2 = (((bx & 15) >> 2) << 4) | (((by & 15) >> 2) << 2) | ((bz & 15) >> 2);
        int l3 = ((bx & 3) << 4) | ((by & 3) << 2) | (bz & 3);
        return (l2 << 6) | l3;
    }

    /**
     * Projectile types that cannot interact with each other (isPickable() ==
     * false): arrows, snowballs, eggs, tridents, ender pearls, experience
     * bottles, llama spit, small/dragon fireballs, wither skulls. Excluded
     * from the index so projectile hit tests never scan them. Fireballs /
     * wind charges (REDIRECTABLE_PROJECTILE) and non-projectiles stay in.
     */
    @Unique
    private static final java.util.Set<EntityType<?>> serverOptimize$UNHITTABLE_PROJECTILES = java.util.Set.of(
        EntityType.ARROW, EntityType.SPECTRAL_ARROW, EntityType.SNOWBALL, EntityType.EGG,
        EntityType.ENDER_PEARL, EntityType.EXPERIENCE_BOTTLE, EntityType.TRIDENT,
        EntityType.LLAMA_SPIT, EntityType.SMALL_FIREBALL, EntityType.DRAGON_FIREBALL,
        EntityType.WITHER_SKULL);

    /**
     * Indexability check driven by the unhittable-projectile list and the
     * item-entity exclusion: everything (non-projectiles and other
     * projectiles) is indexed by default, and only the listed
     * mutually-unhittable projectiles plus minecraft:item (ItemEntity) are
     * excluded. The caller's predicate filters at query time.
     * <p>
     * Verified: ItemEntity has no isPickable() override, so it inherits
     * Entity.isPickable() == false and canBeHitByProjectile() == false -
     * projectiles never hit item entities, so they do not belong in the
     * index. LivingEntity, EndCrystal and REDIRECTABLE projectiles stay in.
     */
    private boolean serverOptimize$isIndexable(EntityAccess access) {
        if (!(access instanceof Entity e)) {
            return false;
        }
        if (e instanceof Projectile p) {
            return !serverOptimize$UNHITTABLE_PROJECTILES.contains(p.getType());
        }
        return !(e instanceof ItemEntity);
    }

    @Inject(method = "add", at = @At("RETURN"))
    private void serverOptimize$onAdd(EntityAccess entity, CallbackInfo ci) {
        if (!serverOptimize$isIndexable(entity)) {
            return;
        }
        Entity e = (Entity) entity;
        this.serverOptimize$hittableCount++;
        if (this.serverOptimize$hittableBuckets == null) {
            this.serverOptimize$hittableBuckets = new Int2ObjectOpenHashMap<>();
        }
        int key = serverOptimize$bucketKey(
            (int) Math.floor(e.getX()), (int) Math.floor(e.getY()), (int) Math.floor(e.getZ()));
        this.serverOptimize$hittableBuckets.computeIfAbsent(key, k -> new it.unimi.dsi.fastutil.objects.ObjectArrayList<>())
            .add(e);
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void serverOptimize$onRemove(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof Entity e)) {
            return;
        }
        if (this.serverOptimize$hittableCount <= 0 && this.serverOptimize$hittableBuckets == null) {
            return;
        }
        // Remove regardless of current isPickable() (the stored bucket may be
        // stale): scan the map lazily - removals are rare relative to ticks.
        serverOptimize$removeFromBuckets(e);
    }

    @Unique
    private void serverOptimize$removeFromBuckets(Entity e) {
        if (this.serverOptimize$hittableBuckets == null) {
            return;
        }
        var it = this.serverOptimize$hittableBuckets.int2ObjectEntrySet().fastIterator();
        int removed = 0;
        while (it.hasNext()) {
            Int2ObjectMap.Entry<List<Entity>> entry = it.next();
            List<Entity> bucket = entry.getValue();
            if (bucket.remove(e)) {
                removed++;
                if (bucket.isEmpty()) {
                    it.remove();
                }
            }
        }
        if (removed > 0) {
            this.serverOptimize$hittableCount -= removed;
        }
    }

    @Override
    public boolean serverOptimize$hasHittable() {
        return this.serverOptimize$hittableCount > 0;
    }

    @Override
    public void serverOptimize$iterateHittableInBox(AABB box, int secX, int secY, int secZ, Consumer<Entity> consumer) {
        if (this.serverOptimize$hittableBuckets == null || this.serverOptimize$hittableBuckets.isEmpty()) {
            return;
        }
        // entity.fastSubSectionEntityScan (default on): start from the 4^3
        // SubSection buckets directly. When off, fall back to sweeping the
        // section's whole bucket list (same results, more bucket visits).
        if (com.server_optimize.config.ModConfig.INSTANCE != null
            && !com.server_optimize.config.ModConfig.INSTANCE.entity.fastSubSectionEntityScan) {
            serverOptimize$iterateHittableInBoxLegacy(box, consumer);
            return;
        }
        // Section block origin (16-aligned).
        int sx0 = secX << 4, sy0 = secY << 4, sz0 = secZ << 4;
        // Block span of the box inside this section, clamped to [0,15], padded
        // by 1 block so entities whose ORIGIN block sits just outside the box
        // (but whose AABB reaches into it) are still visited. The box padding
        // is directional: entities are found by their origin bucket, and their
        // AABB test below decides the actual intersection.
        int bminX = Math.max(0, net.minecraft.util.Mth.floor(box.minX) - 1 - sx0);
        int bmaxX = Math.min(15, net.minecraft.util.Mth.floor(box.maxX) + 1 - sx0);
        int bminY = Math.max(0, net.minecraft.util.Mth.floor(box.minY) - 1 - sy0);
        int bmaxY = Math.min(15, net.minecraft.util.Mth.floor(box.maxY) + 1 - sy0);
        int bminZ = Math.max(0, net.minecraft.util.Mth.floor(box.minZ) - 1 - sz0);
        int bmaxZ = Math.min(15, net.minecraft.util.Mth.floor(box.maxZ) + 1 - sz0);
        if (bminX > bmaxX || bminY > bmaxY || bminZ > bmaxZ) {
            return;
        }

        // SubSection (4^3) start: iterate only the L2 buckets covered by the
        // box span, and within each one only the single-block (L3) buckets the
        // box actually covers - never the section's whole 16^3 coordinate
        // range. Cross-region box coverage is handled by the caller invoking
        // this per covered section; the bucket key encodes both L2 and L3, so
        // a single map lookup per block position is all that is needed.
        for (int l2x = bminX >> 2; l2x <= bmaxX >> 2; l2x++) {
            int l2xo = l2x << 2;
            int bx0 = Math.max(bminX, l2xo), bx1 = Math.min(bmaxX, l2xo + 3);
            for (int l2y = bminY >> 2; l2y <= bmaxY >> 2; l2y++) {
                int l2yo = l2y << 2;
                int by0 = Math.max(bminY, l2yo), by1 = Math.min(bmaxY, l2yo + 3);
                int l2 = (l2x << 4) | (l2y << 2);
                for (int l2z = bminZ >> 2; l2z <= bmaxZ >> 2; l2z++) {
                    int l2zo = l2z << 2;
                    int bz0 = Math.max(bminZ, l2zo), bz1 = Math.min(bmaxZ, l2zo + 3);
                    int l2base = (l2 | l2z) << 6;
                    for (int bx = bx0; bx <= bx1; bx++) {
                        int bxBits = (bx & 3) << 4;
                        for (int by = by0; by <= by1; by++) {
                            int byBits = (by & 3) << 2;
                            for (int bz = bz0; bz <= bz1; bz++) {
                                List<Entity> bucket = this.serverOptimize$hittableBuckets
                                    .get(l2base | bxBits | byBits | (bz & 3));
                                if (bucket == null || bucket.isEmpty()) {
                                    continue;
                                }
                                serverOptimize$visitBucket(bucket, l2base | bxBits | byBits | (bz & 3), box, consumer);
                                if (bucket.isEmpty()) {
                                    this.serverOptimize$hittableBuckets.remove(l2base | bxBits | byBits | (bz & 3));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Legacy path (entity.fastSubSectionEntityScan = false): sweep every
     * non-empty bucket of this section - same box/AABB semantics, but no
     * SubSection start, so empty buckets outside the box are visited too.
     * Entities that moved out of their bucket are lazily re-homed during
     * iteration, so buckets self-clean.
     */
    @Unique
    private void serverOptimize$iterateHittableInBoxLegacy(AABB box, Consumer<Entity> consumer) {
        var it = this.serverOptimize$hittableBuckets.int2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Int2ObjectMap.Entry<List<Entity>> entry = it.next();
            List<Entity> bucket = entry.getValue();
            serverOptimize$visitBucket(bucket, entry.getIntKey(), box, consumer);
            if (bucket.isEmpty()) {
                it.remove();
            }
        }
    }

    /**
     * Iterates one non-empty block bucket: lazy-re-homes entities that moved
     * out of it (still emitting them when their AABB enters the box, exactly
     * like the previous whole-section sweep) and emits entities whose AABB
     * intersects the box. The AABB test - not the origin bucket - decides
     * membership, so entities whose bounding box crosses sub-region or block
     * boundaries are found wherever they are (cross-region correctness).
     */
    @Unique
    private void serverOptimize$visitBucket(List<Entity> bucket, int key, AABB box, Consumer<Entity> consumer) {
        for (int i = 0; i < bucket.size(); i++) {
            Entity e = bucket.get(i);
            int curKey = serverOptimize$bucketKey(
                (int) Math.floor(e.getX()), (int) Math.floor(e.getY()), (int) Math.floor(e.getZ()));
            if (curKey != key) {
                // Moved within the section: re-home lazily, still emit if the
                // AABB enters the box (count unchanged - same section).
                bucket.remove(i--);
                this.serverOptimize$hittableBuckets
                    .computeIfAbsent(curKey, k -> new it.unimi.dsi.fastutil.objects.ObjectArrayList<>())
                    .add(e);
                if (e.getBoundingBox().intersects(box)) {
                    consumer.accept(e);
                }
                continue;
            }
            if (e.getBoundingBox().intersects(box)) {
                consumer.accept(e);
            }
        }
    }
}