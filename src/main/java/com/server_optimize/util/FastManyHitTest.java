package com.server_optimize.util;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.ClassInstanceMultiMapAccessor;
import com.server_optimize.mixin.accessor.EntitySectionAccessor;
import com.server_optimize.mixin.accessor.LevelAccessor;
import com.server_optimize.mixin.accessor.LevelEntityGetterAdapterAccessor;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Fast many-hit projectile test (entity.projectile.fastArrowHitTest).
 * <p>
 * Replaces the vanilla {@code ProjectileUtil.getManyEntityHitResult} inner
 * loop - the hot path of arrows and of the melee/attack sweep - with a
 * zero-allocation scan:
 * <ul>
 *   <li>candidates are iterated directly over each section's backing entity
 *       list (no candidate List, no per-call consumer/lambda layers);</li>
 *   <li>the per-candidate ray test is an inlined slab identical to vanilla
 *       {@code AABB.clip} (verified bit-exact), and the containment check
 *       mirrors {@code AABB.contains} (min-inclusive, max-exclusive);</li>
 *   <li>the predicate (for projectiles it chains into {@code canHitEntity}) is
 *       run early - it is cheap and rejects most box-overlapping candidates,
 *       so only predicate-passed candidates pay for the expensive slab;</li>
 *   <li>for projectile callers, candidates that are themselves unhittable
 *       projectiles (EntityType not in the REDIRECTABLE_PROJECTILE tag; arrows,
 *       snowballs, tridents ... have isPickable() == false) are skipped before
 *       box overlap / predicate - cutting the dense-arrow O(N^2) candidate
 *       scan to O(N x hittable);</li>
 *   <li>only actual hits allocate an {@link EntityHitResult}; the margin /
 *       line-of-sight branch keeps the vanilla {@code clipIncludingBorder}
 *       check.</li>
 * </ul>
 * The candidate set and result order (x -&gt; y -&gt; z section order, dragon
 * parts last) match vanilla.
 */
public final class FastManyHitTest {

    private static final double EPSILON = 1.0E-7;

    private FastManyHitTest() {
    }

    /**
     * Fast replacement for {@code ProjectileUtil.getManyEntityHitResult}.
     *
     * @return the collected hits, or {@code null} when the entity section
     * storage cannot be reached (caller should fall back to vanilla).
     */
    public static Collection<EntityHitResult> getManyEntityHitResult(Level level, Entity entity,
                                                                     Vec3 from, Vec3 to, AABB box,
                                                                     Predicate<Entity> predicate, float margin,
                                                                     ClipContext.Block blockClip,
                                                                     boolean checkContainment) {
        double sx = from.x, sy = from.y, sz = from.z;
        double dx = to.x - sx, dy = to.y - sy, dz = to.z - sz;

        LevelEntityGetter<Entity> getter = ((LevelAccessor) level).serverOptimize$invokeGetEntities();
        if (!(getter instanceof LevelEntityGetterAdapter<Entity> adapter)) {
            return null;
        }
        EntitySectionStorage<Entity> storage;
        try {
            storage = ((LevelEntityGetterAdapterAccessor<Entity>) adapter).serverOptimize$getSectionStorage();
        } catch (Throwable t) {
            return null;
        }
        if (storage == null) {
            return null;
        }

        ArrayList<EntityHitResult> list = new ArrayList<>();

        // Skip unhittable projectile candidates (arrows, snowballs, tridents,
        // ...) for projectile callers: vanilla canHitEntity always rejects
        // them because isPickable() is false unless the type is in the
        // REDIRECTABLE_PROJECTILE tag. With the three-level spatial index
        // (fastIndexedProjectileScan) those candidates are never even
        // iterated - the index only stores isPickable() entities.
        boolean indexed = ModConfig.INSTANCE != null
            && ModConfig.INSTANCE.entity.projectile.fastIndexedProjectileScan;
        boolean skipUnhittable = !indexed
            && ModConfig.INSTANCE != null
            && ModConfig.INSTANCE.entity.projectile.skipUnhittableProjectiles
            && entity instanceof Projectile;
        // Per-call last-type cache: candidates in one section are usually the
        // same EntityType (all arrows), so the tag lookup runs once per type.
        EntityType<?> lastType = null;
        boolean lastRedirectable = false;

        // Vanilla grace range (XZ +-2, Y -4/+0), visited in x -> y -> z order
        // to match the section traversal order used by
        // forEachAccessibleNonEmptySection / lithium.
        int secMinX = SectionPos.posToSectionCoord(box.minX - 2.0);
        int secMaxX = SectionPos.posToSectionCoord(box.maxX + 2.0);
        int secMinY = SectionPos.posToSectionCoord(box.minY - 4.0);
        int secMaxY = SectionPos.posToSectionCoord(box.maxY);
        int secMinZ = SectionPos.posToSectionCoord(box.minZ - 2.0);
        int secMaxZ = SectionPos.posToSectionCoord(box.maxZ + 2.0);

        for (int x = secMinX; x <= secMaxX; x++) {
            for (int y = secMinY; y <= secMaxY; y++) {
                for (int z = secMinZ; z <= secMaxZ; z++) {
                    EntitySection<Entity> section = storage.getSection(SectionPos.asLong(x, y, z));
                    if (section == null) {
                        continue;
                    }
                    if (indexed && section instanceof com.server_optimize.index.HittableSpaceIndex hsi
                        && hsi.serverOptimize$hasHittable()) {
                        // Indexed path: start from the 4^3 SubSection buckets of
                        // THIS section directly - no section-wide 16^3 scan, no
                        // unhittable projectiles ever iterated. The box span is
                        // clamped to this section inside the index; boxes
                        // spanning several sections are covered by the outer
                        // x/y/z section loop calling this once per section
                        // (cross-region). Entities whose AABB crosses bucket or
                        // section boundaries are matched by the AABB test, not
                        // by their origin bucket.
                        hsi.serverOptimize$iterateHittableInBox(box, x, y, z, e -> {
                            if (e == entity) {
                                return;
                            }
                            if (predicate.test(e)) {
                                testCandidate(e, list, level, entity, from, to,
                                    sx, sy, sz, dx, dy, dz, checkContainment, margin, blockClip);
                            }
                        });
                        continue;
                    }
                    if (indexed) {
                        continue; // no hittable entities in this section (L1 skip)
                    }
                    // Direct indexed iteration over the backing list: no
                    // candidate List, no per-call consumer/lambda layers.
                    ClassInstanceMultiMap<Entity> sectionStorage;
                    try {
                        sectionStorage = ((EntitySectionAccessor<Entity>) section).serverOptimize$getStorage();
                    } catch (Throwable t) {
                        return null; // accessor unavailable: fall back to vanilla
                    }
                    List<Entity> all = ((ClassInstanceMultiMapAccessor<Entity>) sectionStorage)
                        .serverOptimize$getAllInstances();
                    for (int i = 0; i < all.size(); i++) {
                        Entity e = all.get(i);
                        if (e == entity) {
                            continue;
                        }
                        if (skipUnhittable && e instanceof Projectile) {
                            EntityType<?> t = e.getType();
                            if (t != lastType) {
                                lastType = t;
                                lastRedirectable = t.is(EntityTypeTags.REDIRECTABLE_PROJECTILE);
                            }
                            if (!lastRedirectable) {
                                continue; // this projectile cannot be hit by a projectile
                            }
                        }
                        AABB b = e.getBoundingBox();
                        // Exact mirror of AABB.intersects (strict bounds).
                        if (b.minX < box.maxX && b.maxX > box.minX
                            && b.minY < box.maxY && b.maxY > box.minY
                            && b.minZ < box.maxZ && b.maxZ > box.minZ
                            // The predicate (chains into canHitEntity) is cheap
                            // and rejects most box-overlapping candidates
                            // (arrows do not hit every other arrow), so it runs
                            // BEFORE the much more expensive slab clip.
                            && predicate.test(e)) {
                            testCandidate(e, list, level, entity, from, to,
                                sx, sy, sz, dx, dy, dz, checkContainment, margin, blockClip);
                        }
                    }
                }
            }
        }

        // Ender dragon parts are added by vanilla's getEntities(Entity, AABB,
        // Predicate) after the section query; replicate the same order.
        for (EnderDragonPart part : level.dragonParts()) {
            if (part == entity || part.parentMob == entity) {
                continue;
            }
            if (!part.getBoundingBox().intersects(box)) {
                continue;
            }
            if (!predicate.test(part)) {
                continue;
            }
            testCandidate(part, list, level, entity, from, to,
                sx, sy, sz, dx, dy, dz, checkContainment, margin, blockClip);
        }
        return list;
    }

    /**
     * Per-candidate logic mirroring vanilla getManyEntityHitResult:
     * containment first, then the exact ray/AABB clip, then the margin +
     * line-of-sight branch. The slab and containment are allocation-free; only
     * a real hit allocates an EntityHitResult. The predicate is applied by
     * the caller before this (it is cheap and rejects most box-overlapping
     * candidates, so running it first avoids the expensive slab for them).
     */
    private static void testCandidate(Entity e, ArrayList<EntityHitResult> list, Level level, Entity entity,
                                      Vec3 from, Vec3 to,
                                      double sx, double sy, double sz,
                                      double dx, double dy, double dz,
                                      boolean checkContainment, float margin,
                                      ClipContext.Block blockClip) {
        AABB eb = e.getBoundingBox();
        if (checkContainment && contains(eb, from)) {
            list.add(new EntityHitResult(e, from));
            return;
        }
        double t = clip(eb.minX, eb.minY, eb.minZ, eb.maxX, eb.maxY, eb.maxZ, sx, sy, sz, dx, dy, dz);
        if (t >= 0.0) {
            list.add(new EntityHitResult(e, new Vec3(sx + dx * t, sy + dy * t, sz + dz * t)));
            return;
        }
        if (margin <= 0.0f) {
            return;
        }
        double t2 = clip(eb.minX - margin, eb.minY - margin, eb.minZ - margin,
            eb.maxX + margin, eb.maxY + margin, eb.maxZ + margin,
            sx, sy, sz, dx, dy, dz);
        if (t2 < 0.0) {
            return;
        }
        Vec3 hit = new Vec3(sx + dx * t2, sy + dy * t2, sz + dz * t2);
        Vec3 center = eb.getCenter();
        BlockHitResult bhr = level.clipIncludingBorder(
            new ClipContext(hit, center, blockClip, ClipContext.Fluid.NONE, entity));
        if (bhr.getType() != HitResult.Type.MISS) {
            center = bhr.getLocation();
        }
        double t3 = clip(eb.minX, eb.minY, eb.minZ, eb.maxX, eb.maxY, eb.maxZ,
            hit.x, hit.y, hit.z, center.x - hit.x, center.y - hit.y, center.z - hit.z);
        if (t3 >= 0.0) {
            list.add(new EntityHitResult(e, new Vec3(hit.x + (center.x - hit.x) * t3,
                hit.y + (center.y - hit.y) * t3,
                hit.z + (center.z - hit.z) * t3)));
        }
    }

    /** Exact mirror of {@code AABB.contains(Vec3)}: min inclusive, max exclusive. */
    static boolean contains(AABB b, Vec3 p) {
        return p.x >= b.minX && p.x < b.maxX
            && p.y >= b.minY && p.y < b.maxY
            && p.z >= b.minZ && p.z < b.maxZ;
    }

    /**
     * Exact mirror of {@code AABB.clip} face-slab algorithm on raw doubles.
     * Returns the hit parameter t in (0,1], or -1 when the ray misses the box.
     */
    static double clip(double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ,
                       double sx, double sy, double sz,
                       double dx, double dy, double dz) {
        double t = 1.0;
        boolean hit = false;
        if (dx > EPSILON) {
            double nt = clipFace(t, minX, minY, maxY, minZ, maxZ, sx, sy, sz, dx, dy, dz);
            if (nt < t) {
                t = nt;
                hit = true;
            }
        } else if (dx < -EPSILON) {
            double nt = clipFace(t, maxX, minY, maxY, minZ, maxZ, sx, sy, sz, dx, dy, dz);
            if (nt < t) {
                t = nt;
                hit = true;
            }
        }
        if (dy > EPSILON) {
            double nt = clipFace(t, minY, minX, maxX, minZ, maxZ, sy, sx, sz, dy, dx, dz);
            if (nt < t) {
                t = nt;
                hit = true;
            }
        } else if (dy < -EPSILON) {
            double nt = clipFace(t, maxY, minX, maxX, minZ, maxZ, sy, sx, sz, dy, dx, dz);
            if (nt < t) {
                t = nt;
                hit = true;
            }
        }
        if (dz > EPSILON) {
            double nt = clipFace(t, minZ, minX, maxX, minY, maxY, sz, sx, sy, dz, dx, dy);
            if (nt < t) {
                t = nt;
                hit = true;
            }
        } else if (dz < -EPSILON) {
            double nt = clipFace(t, maxZ, minX, maxX, minY, maxY, sz, sx, sy, dz, dx, dy);
            if (nt < t) {
                t = nt;
                hit = true;
            }
        }
        return hit ? t : -1;
    }

    private static double clipFace(double t, double faceCoord,
                                   double loA, double hiA, double loB, double hiB,
                                   double originCoord, double originA, double originB,
                                   double dirX, double dirA, double dirB) {
        double tCrossing = (faceCoord - originCoord) / dirX;
        double aAtCrossing = originA + tCrossing * dirA;
        double bAtCrossing = originB + tCrossing * dirB;
        if (tCrossing > 0.0 && tCrossing < t) {
            if (aAtCrossing > loA && aAtCrossing < hiA && bAtCrossing > loB && bAtCrossing < hiB) {
                return tCrossing;
            }
        }
        return t;
    }
}