package com.server_optimize.index;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.function.Consumer;

/**
 * Three-level spatial index of "hittable" entities per section
 * (16^3 section -> 4^3 sub-region -> single block).
 * <p>
 * Only entities {@link Entity#isPickable()} == true (LivingEntity, players,
 * REDIRECTABLE projectiles like fireballs/wind charges) are indexed; arrows,
 * snowballs, tridents etc. (isPickable() == false) are never stored, so a
 * projectile's hit test only ever sees candidates that can actually be hit -
 * the dense-arrow-cloud O(N^2) mutual scan collapses to O(N x local hittable
 * density).
 * <p>
 * The index is maintained on add/remove (exact) and lazily refreshed on
 * query (an entity that moved out of its bucket is dropped from the bucket
 * during iteration), so it is always correct even without per-tick moves.
 * Entity-vs-block collision is a separate path (Level.clip) and is untouched.
 */
public interface HittableSpaceIndex {

    /** Whether this section has no hittable entities at all (L1 skip). */
    boolean serverOptimize$hasHittable();

    /**
     * Visits hittable entities whose bounding boxes intersect {@code box}.
     * Starts from the 4^3 sub-region (SubSection) buckets directly - it never
     * walks the section's full 16^3 coordinate span - and only inspects the
     * single-block buckets covered by the box (plus a 1-block origin pad so
     * entities whose origin sits just outside the box but whose AABB enters
     * it are still found). {@code sectionX/Y/Z} are the SECTION coordinates
     * of this section (needed to map world-space box bounds to in-section
     * block coordinates). Entities that moved out of their bucket are lazily
     * re-homed during iteration, so buckets self-clean; boxes spanning
     * several sections are handled by the caller invoking this once per
     * covered section.
     * 直接从 4^3 子区(SubSection)桶起算访问与 box 相交的可命中实体——从不
     * 遍历 section 的整个 16^3 坐标范围,只检查 box 覆盖(外加 1 block 的
     * origin 余量,保证 origin 在 box 外但 AABB 伸入 box 的实体也被找到)
     * 的单方块桶。sectionX/Y/Z 是本 section 的 section 坐标(用于把世界
     * 坐标 box 映射到 section 内 block 坐标)。迭代时对移出桶的实体惰性
     * 重归位,桶自清理;box 跨多个 section 时由调用方对每个被覆盖的
     * section 各调用一次。
     */
    void serverOptimize$iterateHittableInBox(AABB box, int sectionX, int sectionY, int sectionZ,
                                             Consumer<Entity> consumer);
}