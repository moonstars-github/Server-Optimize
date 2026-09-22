package com.server_optimize.util;

import com.server_optimize.config.ModConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server- and client-side particle throttling ([particle.limit.block]).
 * <p>
 * Each rule is "pattern = cap": "*" (all particles), "namespace:*" (all of
 * one namespace) or "namespace:path" (one particle type). A particle's
 * effective per-block cap is the minimum of every matching rule. When a
 * block is over budget, the remaining allowance is distributed to the
 * sub-categories weighted by min(window count, own cap) - a minority
 * particle type is never starved by another's flood.
 * <p>
 * Enforcement happens on the server at {@code ServerLevel.sendParticles}
 * and on the client at {@code ClientLevel.doAddParticle}: once a block
 * already holds its cap of particles, further spawns are not
 * sent/processed/rendered. Counts approximate live particles with a decaying
 * window of emitted particles (half-life ~4 ticks) per block; the server and
 * the client keep independent windows (both apply the same rules).
 */
public final class ParticleThrottle {

    /** Per-tick decay of the emission window (half-life ~4.3 ticks). */
    private static final double DECAY = 0.85;
    /** A block whose window is at least this many ticks stale resets to 0. */
    private static final int WINDOW_TICKS = 12;
    /** Cleanup cadence / staleness for block entries. */
    private static final int CLEANUP_EVERY = 200;
    private static final int ENTRY_STALE_TICKS = 60;

    private static final Map<ParticleType<?>, String> NAME_CACHE = new ConcurrentHashMap<>();

    /**
     * Split cache for "namespace:path" names. The split used to run on every
     * emission check, and JFR showed it as two of the hottest allocation sites
     * (StringLatin1.newString 4.50% and Arrays.copyOfRange 3.34% of all allocation
     * pressure). Particle names repeat constantly, so the split is computed once per
     * distinct name and reused; entries are bounded.
     */
    private static final ConcurrentHashMap<String, String[]> NAME_SPLIT = new ConcurrentHashMap<>();

    private static String[] splitName(String name) {
        String[] cached = NAME_SPLIT.get(name);
        if (cached != null) {
            return cached;
        }
        int colon = name.indexOf(':');
        String[] split = colon > 0
            ? new String[] {name.substring(0, colon), name.substring(colon + 1)}
            : new String[] {null, null};
        if (NAME_SPLIT.size() < 4096) {
            String[] previous = NAME_SPLIT.putIfAbsent(name, split);
            if (previous != null) {
                return previous;
            }
        }
        return split;
    }

    /** Rule tree node: root (ns=null,path=null), namespace, or leaf path. */
    static final class Node {
        final String ns;
        final String path;
        int cap = Integer.MAX_VALUE;
        final Map<String, Node> children = new java.util.HashMap<>();

        Node(String ns, String path) {
            this.ns = ns;
            this.path = path;
        }

        boolean matches(String typeName) {
            if (ns == null) {
                return true;
            }
            if (path == null) {
                return typeName.startsWith(ns + ":");
            }
            return typeName.equals(ns + ":" + path);
        }
    }

    private static volatile Node root;

    /** Server-side block counts (ServerLevel.sendParticles). */
    private static final Long2ObjectOpenHashMap<BlockState> SERVER_BLOCKS = new Long2ObjectOpenHashMap<>();
    /** Client-side block counts (ClientLevel.doAddParticle). */
    private static final Long2ObjectOpenHashMap<BlockState> CLIENT_BLOCKS = new Long2ObjectOpenHashMap<>();
    private static long lastCleanupTick;

    static final class BlockState {
        long lastTick;
        final Object2IntOpenHashMap<String> counts = new Object2IntOpenHashMap<>();
    }

    private ParticleThrottle() {
    }

    /** Rebuild the rule tree from the config's ordered rules. */
    public static void refresh(java.util.List<ModConfig.ParticleConfig.ParticleRule> rules) {
        Node r = new Node(null, null);
        boolean hasRoot = false;
        if (rules != null) {
            for (ModConfig.ParticleConfig.ParticleRule rule : rules) {
                String p = rule.pattern;
                if ("*".equals(p)) {
                    r.cap = rule.limit;
                    hasRoot = true;
                } else if (p.endsWith(":*")) {
                    String ns = p.substring(0, p.length() - 2);
                    if (!ns.isEmpty()) {
                        r.children.computeIfAbsent(ns, k -> new Node(ns, null)).cap = rule.limit;
                    }
                } else {
                    int colon = p.indexOf(':');
                    if (colon > 0 && colon < p.length() - 1) {
                        String ns = p.substring(0, colon);
                        String path = p.substring(colon + 1);
                        Node nsNode = r.children.computeIfAbsent(ns, k -> new Node(ns, null));
                        nsNode.children.computeIfAbsent(path, k -> new Node(ns, path)).cap = rule.limit;
                    }
                }
            }
        }
        if (!hasRoot) {
            r.cap = 1000;
        }
        root = r;
    }

    /**
     * Server-side entry: {@code ServerLevel.sendParticles}.
     *
     * @return the adjusted particle count for this emission (0 = drop it).
     */
    /**
     * True when at least one real (non-bot) player is close enough that a particle at
     * this position could be visible at all. The bound is the view distance in blocks
     * (chunks x 16), deliberately wider than the 32 block radius vanilla sends within,
     * so dropping emissions beyond it cannot change what any client sees.
     */
    public static boolean anyPlayerNearby(ServerLevel level, double x, double y, double z) {
        if (level.getServer() == null) {
            return true;
        }
        int viewDistance = level.getServer().getPlayerList().getViewDistance();
        double limit = (double) viewDistance * 16.0D;
        double limitSq = limit * limit;
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (isBotPlayer(player)) {
                continue;
            }
            if (player.distanceToSqr(x, y, z) <= limitSq) {
                return true;
            }
        }
        return false;
    }

    /** Bot players (carpet style fake players) must not keep particles alive. */
    private static boolean isBotPlayer(net.minecraft.server.level.ServerPlayer player) {
        if (player.getClass() == net.minecraft.server.level.ServerPlayer.class) {
            return false;
        }
        String name = player.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("fake") || name.contains("bot");
    }

    public static int throttle(ServerLevel level, ParticleOptions options,
                               double x, double y, double z, int count) {
        return throttleImpl(SERVER_BLOCKS, level, options, x, y, z, count);
    }

    /**
     * Client-side entry: {@code ClientLevel.doAddParticle}. Takes a plain
     * {@link Level} so the server can load this class without the client-only
     * {@code ClientLevel} on its classpath (the client mixin passes the
     * ClientLevel as a Level).
     *
     * @return true when the particle should be spawned (block still under cap).
     */
    public static boolean throttleClient(Level level, ParticleOptions options,
                                         double x, double y, double z) {
        return throttleImpl(CLIENT_BLOCKS, level, options, x, y, z, 1) >= 1;
    }

    private static int throttleImpl(Long2ObjectOpenHashMap<BlockState> blocks, Level level,
                                    ParticleOptions options, double x, double y, double z, int count) {
        if (count <= 0 || root == null) {
            return count;
        }
        ParticleType<?> type = options.getType();
        String name = NAME_CACHE.computeIfAbsent(type, t -> {
            Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(t);
            return key == null ? "unknown:unknown" : key.toString();
        });
        // World game time is the shared tick source (server and client).
        long tick = level.getLevelData().getGameTime();
        long key = blockKey(x, y, z);
        BlockState st = blocks.computeIfAbsent(key, k -> new BlockState());
        decay(st, tick);
        cleanup(tick);
        int allowed = weightedAllow(st, name, root);
        int emit = Math.min(count, Math.max(0, allowed));
        if (emit > 0) {
            st.counts.addTo(name, emit);
        }
        return emit;
    }

    /** Decay the emission window toward the current tick. */
    private static void decay(BlockState st, long tick) {
        if (st.lastTick == tick) {
            return;
        }
        long gap = tick - st.lastTick;
        st.lastTick = tick;
        if (gap >= WINDOW_TICKS) {
            st.counts.clear();
            return;
        }
        if (gap > 0) {
            double f = Math.pow(DECAY, gap);
            var it = st.counts.object2IntEntrySet().fastIterator();
            while (it.hasNext()) {
                var e = it.next();
                e.setValue((int) (e.getIntValue() * f));
            }
        }
    }

    /** Periodic removal of stale block entries (particles are transient). */
    private static void cleanup(long tick) {
        if (tick - lastCleanupTick < CLEANUP_EVERY) {
            return;
        }
        lastCleanupTick = tick;
        SERVER_BLOCKS.long2ObjectEntrySet().removeIf(e -> tick - e.getValue().lastTick > ENTRY_STALE_TICKS);
        CLIENT_BLOCKS.long2ObjectEntrySet().removeIf(e -> tick - e.getValue().lastTick > ENTRY_STALE_TICKS);
    }

    private static long blockKey(double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        return ((long) bx << 42) | ((long) by << 21) | (bz & 0x1FFFFF);
    }

    /**
     * Layered budget allocation: walk the type's rule path leaf-to-root. Each
     * ancestor whose subtree is over its cap distributes its budget to its
     * children weighted by min(window count, child cap); a type that is not an
     * explicitly configured child shares the implicit "everything else"
     * weight. The type's target is the minimum of every layer's share; emission
     * is allowed while the window count stays below the target.
     */
    private static int weightedAllow(BlockState st, String name, Node r) {
        int typeCount = st.counts.getInt(name);
        String[] split = splitName(name);
        String ns = split[0];
        String path = split[1];
        Node nsNode = ns != null ? r.children.get(ns) : null;
        Node pathNode = nsNode != null && path != null ? nsNode.children.get(path) : null;

        int target = r.cap;
        if (nsNode != null) {
            target = Math.min(target, nsNode.cap);
            // Layer A: namespace budget (this type's share within the ns).
            target = applyLayer(st, nsNode, pathNode, target);
            // Layer B: root budget (this namespace's share).
            target = applyLayer(st, r, nsNode, target);
        } else {
            // Type under an unconfigured namespace: only the root budget.
            target = applyLayer(st, r, null, target);
        }
        int allow = target - typeCount;
        return allow > 0 ? allow : 0;
    }

    /**
     * If {@code node}'s subtree is over its cap, distribute the cap to its
     * children weighted by min(window count, child cap). Returns the (capped)
     * share for {@code typeChild}; a null typeChild (or one not in the explicit
     * children) shares the implicit "everything else" weight.
     */
    private static int applyLayer(BlockState st, Node node, Node typeChild, int target) {
        int cap = node.cap;
        if (cap <= 0) {
            return target;
        }
        int subCount = subtreeCount(st, node);
        if (subCount < cap) {
            return target;
        }
        int totalW = 0;
        int myW = -1;
        int childSum = 0;
        for (Node child : node.children.values()) {
            int cc = subtreeCount(st, child);
            int w = Math.min(cc, child.cap);
            totalW += w;
            childSum += cc;
            if (child == typeChild) {
                myW = w;
            }
        }
        int other = subCount - childSum;
        int otherW = Math.min(other, cap);
        totalW += otherW;
        if (myW < 0) {
            myW = otherW; // type is inside the implicit "everything else"
        }
        if (totalW > 0) {
            long share = (long) cap * Math.min(myW, cap) / totalW;
            if (share < target) {
                return (int) share;
            }
        }
        return target;
    }

    /** Sum of window counts of all types under {@code node}. */
    private static int subtreeCount(BlockState st, Node node) {
        if (node.ns == null) {
            int sum = 0;
            for (var it = st.counts.object2IntEntrySet().fastIterator(); it.hasNext();) {
                sum += it.next().getIntValue();
            }
            return sum;
        }
        if (node.path == null) {
            String prefix = node.ns + ":";
            int sum = 0;
            for (var it = st.counts.object2IntEntrySet().fastIterator(); it.hasNext();) {
                var e = it.next();
                if (e.getKey().startsWith(prefix)) {
                    sum += e.getIntValue();
                }
            }
            return sum;
        }
        return st.counts.getInt(node.ns + ":" + node.path);
    }

    public static boolean enabled() {
        return root != null && root.cap > 0 && root.cap < Integer.MAX_VALUE;
    }
}