package com.server_optimize.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Canonicalises NBT key names while reading (chunk.internTagNames).
 * <p>
 * The 15.8.10 profile showed the first version of this (a shared
 * {@code ConcurrentHashMap}) as a 2.59% self-time hotspot: the lookup cost ate
 * most of the allocation win. This version uses a per-thread open-addressing
 * table instead - no shared state, no contention, no boxing, no rehashing, and
 * an insertion is just a store. Each thread keeps its own canonical strings,
 * which is fine: the win is that duplicate names die immediately, and a name
 * seen twice on the same thread (the overwhelmingly common case - chunk NBT
 * repeats the same tens of keys) is what gets reused.
 * <p>
 * Memory is bounded by the table size: once a probe window finds no free slot,
 * the value is returned as-is instead of growing.
 */
public final class NbtIntern {

    private static final int TABLE_SIZE = 1024;
    private static final int MAX_PROBES = 8;

    private static final ThreadLocal<String[]> TABLES = ThreadLocal.withInitial(() -> new String[TABLE_SIZE]);

    private static final AtomicLong hits = new AtomicLong();
    private static final AtomicLong inserts = new AtomicLong();

    private NbtIntern() {
    }

    public static long hits() {
        return hits.get();
    }

    public static long inserts() {
        return inserts.get();
    }

    public static void clear() {
        TABLES.remove();
    }

    /**
     * Returns a canonical instance of {@code value} when interning is enabled
     * (limit &gt; 0), otherwise the argument itself.
     */
    public static String intern(String value, int limit) {
        if (limit <= 0 || value == null || value.isEmpty()) {
            return value;
        }
        String[] table = TABLES.get();
        int mask = table.length - 1;
        int index = spread(value.hashCode()) & mask;
        for (int probe = 0; probe < MAX_PROBES; probe++) {
            String existing = table[index];
            if (existing == null) {
                table[index] = value;
                inserts.incrementAndGet();
                return value;
            }
            if (existing.equals(value)) {
                hits.incrementAndGet();
                return existing;
            }
            index = (index + 1) & mask;
        }
        return value;
    }

    /** Same spreader as HashMap, so String hashCodes distribute well. */
    private static int spread(int h) {
        return h ^ (h >>> 16);
    }
}
