package com.server_optimize.util;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Per-tick region-state cache for projectile block collision
 * (entity.projectile.fastProjectileBlockScan).
 * <p>
 * Caches whether a 16^3 section - or one 4^3 sub-region of it - is entirely
 * air, keyed by {@code SectionPos.asLong} (and {@code (sectionKey << 6) | l2}
 * for sub-regions, where l2 is the 6-bit 4^3 bucket index). Cleared once per
 * tick (server tick start), so it always reflects the current tick's world
 * state; a repeated query for the same region is one map lookup. A miss
 * computes the state from the chunk and stores it - there is no queue and no
 * per-candidate cost when regions do not overlap.
 * <p>
 * Sub-region emptiness is computed lazily only when the containing section is
 * known non-empty (fully-air sections make every sub-region air by
 * definition). Sections outside the world's vertical range count as air.
 */
public final class ProjectileRegionCache {

    private ProjectileRegionCache() {
    }

    private static final byte UNKNOWN = 0;
    private static final byte AIR = 1;
    private static final byte NON_AIR = 2;

    private static final Long2ByteOpenHashMap CACHE = new Long2ByteOpenHashMap(64);

    /** Clear at the start of every server tick (MinecraftServer.tickChildren). */
    public static void clear() {
        CACHE.clear();
    }

    /** Whether the whole 16^3 section at (sx, sy, sz) contains only air. */
    public static boolean isSectionAllAir(ServerLevel level, int sx, int sy, int sz) {
        long key = SectionPos.asLong(sx, sy, sz);
        byte v = CACHE.get(key);
        if (v == AIR) return true;
        if (v == NON_AIR) return false;
        boolean empty = computeSectionAllAir(level, sx, sy, sz);
        CACHE.put(key, empty ? AIR : NON_AIR);
        return empty;
    }

    /**
     * Whether the 4^3 sub-region (l2x, l2y, l2z) of the section at (sx, sy, sz)
     * contains only air. Prefers SubSection granularity: the containing
     * section's section-level state is checked/cached first (a fully-air
     * section implies every sub-region is air).
     */
    public static boolean isSubSectionAllAir(ServerLevel level, int sx, int sy, int sz,
                                             int l2x, int l2y, int l2z) {
        long sectionKey = SectionPos.asLong(sx, sy, sz);
        byte sv = CACHE.get(sectionKey);
        if (sv == AIR) return true;
        if (sv != NON_AIR) {
            boolean secEmpty = computeSectionAllAir(level, sx, sy, sz);
            CACHE.put(sectionKey, secEmpty ? AIR : NON_AIR);
            if (secEmpty) return true;
        }
        long key = (sectionKey << 6) | (l2x << 4) | (l2y << 2) | l2z;
        byte v = CACHE.get(key);
        if (v == AIR) return true;
        if (v == NON_AIR) return false;
        boolean empty = computeSubSectionAllAir(level, sx, sy, sz, l2x, l2y, l2z);
        CACHE.put(key, empty ? AIR : NON_AIR);
        return empty;
    }

    private static boolean computeSectionAllAir(ServerLevel level, int sx, int sy, int sz) {
        LevelChunk chunk = level.getChunk(sx, sz);
        int idx = sy - chunk.getMinSectionY();
        LevelChunkSection[] sections = chunk.getSections();
        if (idx < 0 || idx >= sections.length) {
            return true; // outside the world's vertical range: nothing to hit
        }
        LevelChunkSection section = sections[idx];
        return section == null || section.hasOnlyAir();
    }

    private static boolean computeSubSectionAllAir(ServerLevel level, int sx, int sy, int sz,
                                                   int l2x, int l2y, int l2z) {
        LevelChunk chunk = level.getChunk(sx, sz);
        int idx = sy - chunk.getMinSectionY();
        LevelChunkSection[] sections = chunk.getSections();
        if (idx < 0 || idx >= sections.length) {
            return true;
        }
        LevelChunkSection section = sections[idx];
        if (section == null || section.hasOnlyAir()) {
            return true;
        }
        int bx0 = l2x << 2, by0 = l2y << 2, bz0 = l2z << 2;
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                for (int dz = 0; dz < 4; dz++) {
                    if (!section.getBlockState(bx0 + dx, by0 + dy, bz0 + dz).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}