package com.server_optimize.client.debug;

import com.server_optimize.client.mixin.DebugScreenEntriesAccessor;
import com.server_optimize.client.mixin.DebugScreenEntryListAccessor;
import com.server_optimize.config.ModConfig;
import com.server_optimize.hopper.HopperTimeWheel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ServerOptimizeDebugEntries {
    public static final Identifier HOPPER_COUNT_DISPLAY =
        Identifier.fromNamespaceAndPath("server-optimize", "hopper_count_display");
    public static final Identifier HOPPER_COUNT_TOTAL =
        Identifier.fromNamespaceAndPath("server-optimize", "hopper_count_total");
        public static final Identifier HOPPER_COUNT_PER_QUERY =
        Identifier.fromNamespaceAndPath("server-optimize", "hopper_count_per_query");
        // removed orphaned line

    private static final DebugEntryCategory SERVER_OPTIMIZE_CATEGORY =
        new DebugEntryCategory(Component.literal("ServerOptimize"), 10000.0F);

    private static final DebugScreenEntry HOPPER_DISPLAY_ENTRY =
        new HopperCountEntry(HOPPER_COUNT_DISPLAY, HopperCountSection.REALTIME);
    private static final DebugScreenEntry HOPPER_TOTAL_ENTRY =
        new HopperCountEntry(HOPPER_COUNT_TOTAL, HopperCountSection.TOTAL);
        private static final DebugScreenEntry HOPPER_PER_QUERY_ENTRY =
        new HopperCountEntry(HOPPER_COUNT_PER_QUERY, HopperCountSection.PER_QUERY);
        // removed orphaned line

    private ServerOptimizeDebugEntries() {
    }

    public static void register() {
        Map<Identifier, DebugScreenEntry> entries =
            DebugScreenEntriesAccessor.serverOptimize$getEntriesById();
        entries.putIfAbsent(HOPPER_COUNT_DISPLAY, HOPPER_DISPLAY_ENTRY);
        entries.putIfAbsent(HOPPER_COUNT_TOTAL, HOPPER_TOTAL_ENTRY);
                entries.putIfAbsent(HOPPER_COUNT_PER_QUERY, HOPPER_PER_QUERY_ENTRY);
    }

    public static void ensureStatuses(DebugScreenEntryList entryList) {
        register();
        Map<Identifier, DebugScreenEntryStatus> statuses =
            ((DebugScreenEntryListAccessor) entryList).serverOptimize$getAllStatuses();

        if (!statuses.containsKey(HOPPER_COUNT_DISPLAY)) {
            statuses.put(HOPPER_COUNT_DISPLAY, displayStatus(
                ModConfig.INSTANCE == null || ModConfig.INSTANCE.hopper.hopperCountDisplay
            ));
        }
        if (!statuses.containsKey(HOPPER_COUNT_TOTAL)) {
            statuses.put(HOPPER_COUNT_TOTAL, displayStatus(
                ModConfig.INSTANCE != null && ModConfig.INSTANCE.hopper.hopperCountTotal
            ));
        }
                if (!statuses.containsKey(HOPPER_COUNT_PER_QUERY)) {
            statuses.put(HOPPER_COUNT_PER_QUERY, displayStatus(
                ModConfig.INSTANCE != null && ModConfig.INSTANCE.hopper.hopperCountPerQuery
            ));
        }

        entryList.rebuildCurrentList();
    }

    public static void reorderCurrentList(DebugScreenEntryList entryList) {
        List<Identifier> enabled =
            ((DebugScreenEntryListAccessor) entryList).serverOptimize$getCurrentlyEnabled();
        enabled.sort(Comparator.comparingInt(ServerOptimizeDebugEntries::sortRank));
    }

    private static int sortRank(Identifier id) {
        if (id.equals(HOPPER_COUNT_TOTAL)) return 0;
        if (id.equals(HOPPER_COUNT_DISPLAY)) return 1;
        if (id.equals(HOPPER_COUNT_PER_QUERY)) return 2;
        return Integer.MAX_VALUE;
    }

    private static DebugScreenEntryStatus displayStatus(boolean enabled) {
        return enabled ? DebugScreenEntryStatus.IN_OVERLAY : DebugScreenEntryStatus.NEVER;
    }

    private enum HopperCountSection {
        REALTIME,
        TOTAL,
        PER_QUERY
    }

    private static final class HopperCountEntry implements DebugScreenEntry {
        private final Identifier id;
        private final HopperCountSection section;

        private HopperCountEntry(Identifier id, HopperCountSection section) {
            this.id = id;
            this.section = section;
        }

        @Override
        public void display(
                DebugScreenDisplayer displayer,
                Level level,
                LevelChunk clientChunk,
                LevelChunk serverChunk) {
            HopperTimeWheel.DebugStats stats = hopperStats(level);
            if (stats == null) return;

            switch (section) {
                case REALTIME -> addRealtime(displayer, stats);
                case TOTAL -> {
                    displayer.addToGroup(HOPPER_COUNT_DISPLAY, "HopperCountTotal: Processing: " + stats.totalActive
                        + ", Sleeping: " + stats.sleepingCount
                        + ", Wakeup: " + stats.wokeCount
                        + ", Resleep: " + stats.sleptCount
                        + ", Checking: " + stats.checkedCount);
                }
                case PER_QUERY -> {
                    List<String> lines = new ArrayList<>();
                    lines.add("HopperProcessQuery: " + stats.currentBucket);
                    for (int i = 0; i < stats.bucketCounts.length; i++) {
                        lines.add(i + ": Processing: " + stats.bucketCounts[i]
                            + ", Wakeup: " + stats.wokeByBucket[i]
                            + ", Resleep: " + stats.sleptByBucket[i]);
                    }
                    displayer.addToGroup(id, lines);
                }
            }
        }

        @Override
        public DebugEntryCategory category() {
            return SERVER_OPTIMIZE_CATEGORY;
        }
    }

    private static void addRealtime(DebugScreenDisplayer displayer, HopperTimeWheel.DebugStats stats) {
        int bucket = stats.currentBucket;
        int checked = stats.checkedByBucket[bucket];
        int woke = stats.wokeByBucket[bucket];
        int slept = stats.sleptByBucket[bucket];
        double hitRate = checked == 0 ? 0.0D : woke * 100.0D / checked;
        displayer.addToGroup(HOPPER_COUNT_DISPLAY, "HopperCountRealtime: Processing: " + stats.bucketCounts[bucket]
            + ", Sleeping: " + stats.sleepingCount
            + ", Wakeup: " + woke
            + ", Resleep: " + slept
            + ", Checking: " + checked
            + ", CheckingHitRate: " + String.format(Locale.ROOT, "%.1f%%", hitRate));
    }

    private static HopperTimeWheel.DebugStats hopperStats(Level level) {
        // Dedicated server: whole-server stats synced by the mod (both sides
        // present); nothing falls through when the client is on a dedicated
        // server without the sync channel.
        HopperTimeWheel.DebugStats synced = HopperStatsClientCache.latest();
        if (synced != null) return synced;

        // Singleplayer / LAN host: read the integrated server's wheels
        // locally, aggregated across every loaded dimension.
        if (level == null) return null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getSingleplayerServer() == null) return null;

        return HopperTimeWheel.aggregateServer(minecraft.getSingleplayerServer());
    }
}


