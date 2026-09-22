package com.server_optimize.util;

import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.ChunkMapAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity stacking display ([entity] stackDisplay, stackDisplayThreshold).
 * <p>
 * Rendering-layer only: the original entities keep full server-side behaviour
 * (AI, collision, damage, potions...) - only their tracking packets are
 * affected. When at least stackDisplayThreshold entities of the SAME type
 * form a CLUSTER for at least {@link #RESIDENCY_TICKS} ticks, the group
 * collapses: every non-representative member is untracked per client (vanilla
 * remove packets), and the representative's VANILLA NAMETAG (custom name)
 * shows the current count - the client renders the nametag attached to the
 * entity every frame, so the count follows the cluster's movement exactly
 * like a player's nametag, with no separate display entity and no position
 * updates to lag. The nametag is refreshed whenever the count changes and
 * removed when the group expands again.
 * <p>
 * A cluster is a connected component of one type within ONE dimension: a
 * member joins the group of its nearest same-type neighbour within
 * {@link #CLUSTER_RADIUS}, and stays while it is within the looser
 * {@code CLUSTER_RADIUS * 2} leave threshold of at least one other member.
 * This is the moving-cluster merge: a whole group carried by a bubble column,
 * flowing water or a minecart stream keeps every member (and its residency
 * clock) while it shifts - a member that truly separates is ejected and its
 * clock restarts, so pass-through traffic never flicks the stack count.
 * <p>
 * The per-level sweeps run the re-cluster pass over the GLOBAL group map, so
 * the groups are scoped to their dimension: without that, the nether's and
 * the end's sweeps detached the overworld's members every rebind, resetting
 * the whole crowd's residency clocks each sweep (this exact bug made a
 * 129-arrow bubble-column stack never mature its count).
 * <p>
 * Bandwidth: N tracked-entity packets for the group become the tracking of
 * the representative only; the count rides its existing nametag.
 * <p>
 * Blacklisted (never stacked): players, item entities, experience orbs,
 * entities with passengers or riding, bosses (wither / ender dragon),
 * primed TNT, falling blocks and the text displays themselves.
 * <p>
 * Threading: every entry point is called from the server thread
 * (ChunkMap.addEntity/removeEntity wrappers and the per-tick sweep), so the
 * maps need no synchronization.
 */
public final class EntityStackTracker {

    /** Recompute period: cluster membership is re-evaluated every N ticks so a
     *  member that drifts out of a cluster rejoins a group / shows again after
     *  at most half a second. */
    private static final int REBIND_INTERVAL_TICKS = 10;

    /** An entity must stay in the same cluster this long before it counts
     *  toward the merge and the displayed count. */
    private static final int RESIDENCY_TICKS = 40;

    /** A member joins a group whose closest member is within this distance
     *  (Euclidean, not block-rounded), so moving clusters stay merged. */
    private static final double CLUSTER_RADIUS = 1.25D;

    /** Re-expand hysteresis: a collapsed group only expands once its resident
     *  count falls below this fraction of the threshold, so a count hovering
     *  at the threshold does not flap tracking packets. */
    private static final double EXPAND_FRACTION = 0.9D;

    /**
     * Vanilla tracking state, mirrored by our ChunkMap wrappers: an entity is
     * in this set exactly while the vanilla ChunkMap tracks it.
     * <p>
     * Needed because collapse/expand run RE-ENTRANTLY: an add can trigger a
     * remove (collapse of the group it joined) and a remove can trigger an add
     * (expand of the group that fell below the threshold). In the middle of
     * such a chain the entity that started it is not tracked yet, so a blind
     * "re-add every non-representative member" hit vanilla's
     * {@code IllegalStateException: Entity is already tracked!} while the
     * player was placed in the world. Identity semantics on purpose.
     */
    private static final java.util.Set<Entity> TRACKED =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Called by the ChunkMap wrapper once the vanilla add/remove has run. */
    public static void markTracked(Entity entity, boolean tracked) {
        if (tracked) {
            TRACKED.add(entity);
        } else {
            TRACKED.remove(entity);
        }
    }

    private EntityStackTracker() {
    }

    // --------------------------------------------------------- group/cluster

    /** One cluster: same-type members of one dimension, joined pairwise. */
    private static final class StackGroup {
        final EntityType<?> type;
        /** Dimension of the group: the per-level sweeps re-cluster the global
         *  map, and a group must only be processed by its own dimension's
         *  sweep (otherwise the other levels detach its members each rebind
         *  and the residency never matures). */
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        Entity representative;
        final List<Entity> members = new ArrayList<>();
        boolean collapsed;
        double centroidX;
        double centroidY;
        double centroidZ;

        StackGroup(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                   EntityType<?> type, Entity representative) {
            this.dimension = dimension;
            this.type = type;
            this.representative = representative;
            this.members.add(representative);
            this.centroidX = representative.getX();
            this.centroidY = representative.getY();
            this.centroidZ = representative.getZ();
        }
    }

    /** type -> its clusters, in the order they were created. */
    private static final Map<EntityType<?>, List<StackGroup>> GROUPS_BY_TYPE = new HashMap<>();
    private static final Map<Entity, StackGroup> ENTITY_GROUP = new HashMap<>();
    private static final Map<Entity, Long> ENTER_TICK = new IdentityHashMap<>();
    private static long tickCounter;
    private static int rebindTick;

    /** Hidden members of the collapsed stacks + their representatives, for the
     *  particle suppression (only a representative's interaction particles are
     *  shown to the clients). Rebuilt on each re-cluster; copy-on-write so the
     *  client-side render thread can read them safely while the server thread
     *  rebuilds. */
    private static final java.util.List<Entity> HIDDEN =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.List<Entity> REPS =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Rebuilds the particle-suppression tables from the collapsed groups. */
    private static void rebuildHidden() {
        HIDDEN.clear();
        REPS.clear();
        for (List<StackGroup> groups : GROUPS_BY_TYPE.values()) {
            for (StackGroup g : groups) {
                if (!g.collapsed) {
                    continue;
                }
                REPS.add(g.representative);
                for (Entity m : g.members) {
                    if (m != g.representative && !m.isRemoved()) {
                        HIDDEN.add(m);
                    }
                }
            }
        }
    }

    /**
     * Whether a particle at this position should be suppressed: it belongs to a
     * hidden member of a collapsed stack (the clients only see the
     * representative's interaction particles). Particles near any representative
     * are ALWAYS kept, so a dense pile's own feedback survives.
     */
    public static boolean shouldSuppressParticle(double x, double y, double z) {
        if (HIDDEN.isEmpty()) {
            return false;
        }
        for (Entity rep : REPS) {
            if (!rep.isRemoved() && Math.abs(rep.getX() - x) <= 0.75D
                && Math.abs(rep.getY() - y) <= 0.75D
                && Math.abs(rep.getZ() - z) <= 0.75D) {
                return false;
            }
        }
        for (Entity m : HIDDEN) {
            if (!m.isRemoved() && Math.abs(m.getX() - x) <= 0.5D
                && Math.abs(m.getY() - y) <= 0.5D
                && Math.abs(m.getZ() - z) <= 0.5D) {
                return true;
            }
        }
        return false;
    }

    /** Whether this entity is a hidden member of a collapsed stack (its
     *  interaction particles are suppressed at the source). Matched by the
     *  entity ID so the CLIENT-side render copies of the hidden arrows are
     *  recognized too (the client spawns its own local particles - water
     *  splashes, trails - that never go through the server's sendParticles). */
    public static boolean isHiddenMember(Entity e) {
        if (HIDDEN.isEmpty()) {
            return false;
        }
        int id = e.getId();
        for (Entity m : HIDDEN) {
            if (m.getId() == id) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- blacklist

    private static boolean shouldEverStack(Entity e) {
        if (e instanceof ServerPlayer) return false;
        if (e instanceof ItemEntity) return false;
        if (e instanceof ExperienceOrb) return false;
        if (e instanceof PrimedTnt) return false;
        if (e instanceof FallingBlockEntity) return false;
        if (e instanceof WitherBoss || e instanceof EnderDragon) return false;
        if (e instanceof net.minecraft.world.entity.Display.TextDisplay) return false;
        if (!e.getPassengers().isEmpty() || e.getVehicle() != null) return false;
        return true;
    }

    private static boolean enabled() {
        ModConfig cfg = ModConfig.INSTANCE;
        return cfg != null && cfg.entity.stackDisplay;
    }

    // --------------------------------------------------------- entry points

    /**
     * Called from the ChunkMap.addEntity wrapper. Returns true when the
     * entity must NOT be tracked (hidden member of a collapsed group).
     */
    public static boolean onEntityAdd(ChunkMap cm, Entity e) {
        if (!enabled() || !shouldEverStack(e)) {
            return false;
        }
        StackGroup existing = ENTITY_GROUP.get(e);
        if (existing != null) {
            // Already registered (re-entrant add from expand): keep tracking
            // only while the group is expanded.
            return existing.collapsed && e != existing.representative;
        }
        return attach(cm, e);
    }

    /** Called from the ChunkMap.removeEntity wrapper (entity left the world
     *  or was killed / unloaded). */
    public static void onEntityRemove(ChunkMap cm, Entity e) {
        if (!enabled()) {
            return;
        }
        StackGroup g = ENTITY_GROUP.remove(e);
        if (g == null) {
            return;
        }
        detach(cm, e, g);
    }

    /** Per-tick sweep: stale entities and cluster membership moves, plus the
     *  per-tick display tracking so the count follows a moving cluster
     *  smoothly (the full re-cluster stays on the rebind interval). */
    public static void onServerTick(MinecraftServer server) {
        if (!enabled()) {
            return;
        }
        tickCounter++;
        if (++rebindTick % REBIND_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            sweep(level.getChunkSource().chunkMap, level);
        }
    }

    // -------------------------------------------------------------- attach

    /** The group (of e's dimension) whose CLOSEST member is within
     *  CLUSTER_RADIUS of e (same type), or null. Pairwise, so a dense cluster
     *  keeps absorbing members even when its centroid is pulled by the rest. */
    private static StackGroup nearestGroup(Entity e) {
        List<StackGroup> groups = GROUPS_BY_TYPE.get(e.getType());
        if (groups == null || groups.isEmpty()) {
            return null;
        }
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension =
            e.level().dimension();
        double join2 = CLUSTER_RADIUS * CLUSTER_RADIUS;
        StackGroup best = null;
        double bestDist2 = join2;
        for (StackGroup g : groups) {
            if (g.dimension != dimension) {
                continue;
            }
            for (Entity m : g.members) {
                double d2 = e.distanceToSqr(m);
                if (d2 <= bestDist2) {
                    bestDist2 = d2;
                    best = g;
                }
            }
        }
        return best;
    }

    /** Adds e to the nearest cluster (or starts a new one); collapses the
     *  group at the threshold. Returns true when e must stay hidden (collapsed
     *  and not the rep). */
    private static boolean attach(ChunkMap cm, Entity e) {
        StackGroup g = nearestGroup(e);
        if (g == null) {
            g = new StackGroup(e.level().dimension(), e.getType(), e);
            GROUPS_BY_TYPE.computeIfAbsent(e.getType(), ignored -> new ArrayList<>()).add(g);
        }
        ENTITY_GROUP.put(e, g);
        if (!g.members.contains(e)) {
            g.members.add(e);
        }
        // Keep a surviving residency clock: when the member is re-attached without
        // a real removal in between (the registration sweep re-joining a member
        // whose group entry was lost, or a re-cluster rebind), starting over would
        // reset the whole crowd's residency every sweep and a moving stack would
        // never mature its count.
        Long previous = ENTER_TICK.get(e);
        ENTER_TICK.put(e, previous != null ? previous : tickCounter);
        recentre(g);
        int threshold = ModConfig.INSTANCE.entity.stackDisplayThreshold;
        if (!g.collapsed && residentCount(g) >= threshold) {
            collapse(cm, g);
        }
        return g.collapsed && e != g.representative;
    }

    /** Recomputes the group centroid from its members. */
    private static void recentre(StackGroup g) {
        if (g.members.isEmpty()) {
            return;
        }
        double sx = 0.0D;
        double sy = 0.0D;
        double sz = 0.0D;
        for (Entity m : g.members) {
            sx += m.getX();
            sy += m.getY();
            sz += m.getZ();
        }
        g.centroidX = sx / g.members.size();
        g.centroidY = sy / g.members.size();
        g.centroidZ = sz / g.members.size();
    }

    // -------------------------------------------------------------- detach

    /** Removes e from its group; promotes a new representative when the old
     *  one leaves, and expands the group below the hysteresis threshold. */
    private static void detach(ChunkMap cm, Entity e, StackGroup g) {
        g.members.remove(e);
        ENTER_TICK.remove(e);
        if (g.members.isEmpty()) {
            List<StackGroup> groups = GROUPS_BY_TYPE.get(g.type);
            if (groups != null) {
                groups.remove(g);
                if (groups.isEmpty()) {
                    GROUPS_BY_TYPE.remove(g.type);
                }
            }
            clearRepName(g);
            return;
        }
        if (g.representative == e) {
            // The old representative leaves: clear its nametag so the count does
            // not linger on a now-hidden (or re-tracked) member.
            if (e.hasCustomName()) {
                e.setCustomName(null);
            }
            promote(cm, g);
        }
        recentre(g);
        int threshold = ModConfig.INSTANCE.entity.stackDisplayThreshold;
        if (g.collapsed && residentCount(g) < expandThreshold(threshold)) {
            expand(cm, g);
        }
    }

    private static int expandThreshold(int threshold) {
        return Math.max(2, (int) (threshold * EXPAND_FRACTION));
    }

    // ------------------------------------------------- collapse / expand

    /** Stops tracking every non-representative member (broadcasts the
     *  vanilla remove packet to their trackers) and shows the count display.
     *  Only members vanilla currently tracks are removed - a member that is
     *  still mid-add (re-entrant collapse) is left alone. */
    private static void collapse(ChunkMap cm, StackGroup g) {
        g.collapsed = true;
        for (Entity m : new ArrayList<>(g.members)) {
            if (m != g.representative && TRACKED.contains(m)) {
                ((ChunkMapAccessor) cm).serverOptimize$callRemoveEntity(m);
            }
        }
        updateRepName(g);
    }

    /** Re-tracks every hidden member and removes the count display. Members
     *  that vanilla already tracks are skipped - that is the fix for the
     *  re-entrant "Entity is already tracked!" crash. */
    private static void expand(ChunkMap cm, StackGroup g) {
        g.collapsed = false;
        for (Entity m : new ArrayList<>(g.members)) {
            if (m != g.representative && !TRACKED.contains(m)) {
                try {
                    ((ChunkMapAccessor) cm).serverOptimize$callAddEntity(m);
                    TRACKED.add(m);
                } catch (IllegalStateException alreadyTracked) {
                    // bookkeeping disagreed with vanilla: believe vanilla
                    TRACKED.add(m);
                }
            }
        }
        clearRepName(g);
    }

    /** The representative left the group: pick the first remaining member as
     *  the new representative; when collapsed, re-track it right away. */
    private static void promote(ChunkMap cm, StackGroup g) {
        Entity newRep = g.members.get(0);
        g.representative = newRep;
        if (g.collapsed) {
            if (!TRACKED.contains(newRep)) {
                try {
                    ((ChunkMapAccessor) cm).serverOptimize$callAddEntity(newRep);
                    TRACKED.add(newRep);
                } catch (IllegalStateException alreadyTracked) {
                    TRACKED.add(newRep);
                }
            }
            updateRepName(g);
        }
    }

    // --------------------------------------------------------------- sweep

    /** Re-evaluates every registered entity: removes dead/foreign ones,
     *  re-centroids the clusters, drops members that left their cluster
     *  (restarting their residency) and keeps the count displays in step. */
    private static void sweep(ChunkMap cm, ServerLevel level) {
        // Register entities that were already tracked when the feature was enabled or
        // that loaded before a restart: ChunkMap.addEntity only fires for new adds, so a
        // pre-existing crowd would never join a group otherwise and never merge. Copied
        // because collapsing below removes entries from the map while iterating.
        for (Entity e : level.getAllEntities()) {
            if (e.isRemoved() || e.level().dimension() != level.dimension()
                || ENTITY_GROUP.containsKey(e) || !shouldEverStack(e)) {
                continue;
            }
            // attach starts the residency clock, so a pre-existing entity must stay in the
            // cluster for RESIDENCY_TICKS before it counts, exactly like a freshly added one.
            if (attach(cm, e) && TRACKED.contains(e)) {
                ((ChunkMapAccessor) cm).serverOptimize$callRemoveEntity(e);
                markTracked(e, false);
            }
        }
        // Re-cluster: re-centroid each group, drop members beyond the leave threshold,
        // re-attach them (their clock restarts unless they rejoin the same cluster),
        // and keep the tracking state in step.
        for (List<StackGroup> groups : new ArrayList<>(GROUPS_BY_TYPE.values())) {
            for (StackGroup g : new ArrayList<>(groups)) {
                if (g.dimension != level.dimension()) {
                    continue;
                }
                if (g.members.isEmpty() || g.representative.isRemoved()) {
                    groups.remove(g);
                    clearRepName(g);
                    continue;
                }
                recentre(g);
                // Leave threshold is deliberately looser than the join radius: a moving
                // cluster (bubble column, flowing water) shifts together and must keep its
                // members (and their residency clocks); only a real separation beyond the
                // hysteresis threshold ejects a member.
                double leave2 = CLUSTER_RADIUS * 2.0D;
                double leave2Sq = leave2 * leave2;
                for (Entity m : new ArrayList<>(g.members)) {
                    if (m.isRemoved() || m.level().dimension() != level.dimension()) {
                        ENTITY_GROUP.remove(m);
                        detach(cm, m, g);
                        continue;
                    }
                    // Pairwise membership: the member stays while it is within the leave
                    // threshold of at least one other member (the cluster-chain).
                    boolean stays = false;
                    for (Entity other : g.members) {
                        if (other != m && m.distanceToSqr(other) <= leave2Sq) {
                            stays = true;
                            break;
                        }
                    }
                    if (!stays) {
                        // The member drifted away from the cluster. Rebind it - but if it
                        // lands back in the SAME group (the moving-cluster case: the whole
                        // group shifted), its residency keeps running; only a real change
                        // of cluster restarts it.
                        Long clock = ENTER_TICK.get(m);
                        boolean wasTracked = ((ChunkMapAccessor) cm)
                            .serverOptimize$getEntityMap().containsKey(m.getId());
                        ENTITY_GROUP.remove(m);
                        detach(cm, m, g);
                        StackGroup newG = ENTITY_GROUP.get(m);
                        if (newG == null) {
                            attach(cm, m);
                            newG = ENTITY_GROUP.get(m);
                        }
                        if (newG == g && clock != null) {
                            // Stayed with the same cluster: the residency continues.
                            ENTER_TICK.put(m, clock);
                        }
                        if (newG != null && newG.collapsed && wasTracked) {
                            ((ChunkMapAccessor) cm).serverOptimize$callRemoveEntity(m);
                        } else if (newG == null || !newG.collapsed && !wasTracked) {
                            ((ChunkMapAccessor) cm).serverOptimize$callAddEntity(m);
                        }
                    }
                }
                // Keep the count displays in step: collapsed groups get one with the
                // current count and centroid, expanded groups lose theirs. A still-open
                // group whose resident count matured past the threshold collapses here too
                // (fast-forming clusters - or moving ones whose members all arrived within
                // one residency window - never trip the collapse in attach).
                int threshold = ModConfig.INSTANCE.entity.stackDisplayThreshold;
                int residents = residentCount(g);
                if (!g.collapsed && residents >= threshold) {
                    collapse(cm, g);
                } else if (g.collapsed) {
                    if (residents < expandThreshold(threshold)) {
                        expand(cm, g);
                    } else {
                        updateRepName(g);
                    }
                }
            }
        }
        rebuildHidden();
    }

    // -------------------------------------------------------------- display

    /** Members that have been in the cluster long enough to count. Removed
     *  entities do not count (the sweep detaches them at the next rebind, but
     *  between rebinds a just-killed member must not inflate the displayed
     *  count). */
    private static int residentCount(StackGroup g) {
        if (ENTER_TICK.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Entity m : g.members) {
            if (m.isRemoved()) {
                continue;
            }
            Long entered = ENTER_TICK.get(m);
            if (entered != null && tickCounter - entered >= RESIDENCY_TICKS) {
                count++;
            }
        }
        return count;
    }

    /** Puts the current resident count as the representative's custom name
     *  (the vanilla nametag): the client renders it attached to the entity
     *  every frame, so the count follows the cluster's movement exactly like
     *  a player's nametag - no separate display entity, no per-tick position
     *  updates, no interpolation lag. */
    private static void updateRepName(StackGroup g) {
        Entity rep = g.representative;
        if (rep == null || rep.isRemoved()) {
            return;
        }
        if (g.collapsed) {
            rep.setCustomName(Component.literal(String.valueOf(residentCount(g))));
            rep.setCustomNameVisible(true);
        } else {
            rep.setCustomName(null);
        }
    }

    /** Clears the representative's nametag, if any. Also resets the custom-name
     *  visibility flag: a flag left on after the name is cleared makes the
     *  client render the entity's TYPE name (the stray "箭" nametags). */
    private static void clearRepName(StackGroup g) {
        Entity rep = g.representative;
        if (rep == null || rep.isRemoved()) {
            return;
        }
        rep.setCustomNameVisible(false);
        if (rep.hasCustomName()) {
            rep.setCustomName(null);
        }
    }

    /** Only used by tests/debug; resets all stacking state. */
    public static void reset() {
        for (List<StackGroup> groups : GROUPS_BY_TYPE.values()) {
            for (StackGroup g : groups) {
                clearRepName(g);
            }
        }
        GROUPS_BY_TYPE.clear();
        ENTITY_GROUP.clear();
        ENTER_TICK.clear();
        rebindTick = 0;
        tickCounter = 0;
    }
}