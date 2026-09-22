package com.server_optimize.config;

import com.server_optimize.ServerOptimize;
import com.moandjiezana.toml.Toml;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    public static ModConfig INSTANCE;

    /** Server-preference rule: chunk transmission optimization active. */
    public static boolean enabled() {
        return INSTANCE != null && INSTANCE.chunk.sectionCulling;
    }

    public HopperConfig hopper = new HopperConfig();
    public TweakConfig tweak = new TweakConfig();
    public ExplosionConfig explosion = new ExplosionConfig();
    public EntityConfig entity = new EntityConfig();
    public ParticleConfig particle = new ParticleConfig();
    public ChunkConfig chunk = new ChunkConfig();
    public SafetyConfig safety = new SafetyConfig();
    public LogConfig log = new LogConfig();
    public CommandConfig command = new CommandConfig();
    public ThreadConfig thread = new ThreadConfig();
    public NetConfig net = new NetConfig();
    public ClientConfig client = new ClientConfig();

    public static class HopperConfig {
        public boolean enabled = true;
        // Tweakmore compatibility: derive TransferCooldown from the time wheel
        // only when getUpdateTag is requested, instead of writing the field
        // during every hopper tick.
        public boolean tweakmoreCompat = true;
        // Suppress item data packets for hoppers. When true, hopper inventory
        // changes are not sent to clients unless a player opens the hopper GUI.
        // Reduces network traffic when players walk past many hoppers.
        public boolean suppressItemPackets = true;
        // Client F3 debug display switches. They only change on-screen debug
        // output and do not affect hopper mechanics.
        public boolean hopperCountDisplay = true;
        public boolean hopperCountTotal = true;
        public boolean hopperCountPerQuery = false;
    }

    public static class TweakConfig {
        // Disable the vanilla container-minecart speed penalty caused by
        // inventory contents. Only affects the experimental minecart movement
        // behavior; the legacy minecart behavior remains vanilla.
        public boolean disableMinecartContentSlowdown = false;
    }

    /**
     * Particle throttling ([particle]). Ordered rules: the first rule in the
     * config file takes precedence over later ones. A particle's effective
     * per-block cap is the minimum of every matching rule; when a category is
     * over budget the block's remaining allowance is distributed to its
     * sub-categories weighted by min(actual count, own cap) - so a minority
     * particle type is never starved by a flood of another type.
     */
    public static class ExplosionConfig {
        // Entity damage-density block cache, applied ON TOP of the vanilla
        // grid-ray algorithm (destruction behavior unchanged). Vanilla
        // getSeenPercent casts one clip ray per entity per sampling point;
        // this caches the result per block of the entity (per explosion), so
        // dense crowds on the same blocks share one ray instead of paying one
        // each. Slight approximation: all entities on one block share the
        // density of the first one sampled, which only affects the boundary
        // of shielded areas.
        // 实体伤害密度按方块缓存(叠加在原版网格射线之上,破坏行为不变)。
        // 原版 getSeenPercent 对每个实体的每个采样点发一条 clip 射线;
        // 本项按实体所在方块缓存结果(每次爆炸),同方块密集实体共享一条
        // 射线而非各自支付。轻微近似:同方块实体共享首个采样实体的密度,
        // 仅影响遮挡区域边界。
        public boolean densityBlockCache = true;
        // Per-explosion block-resistance cache for the destruction rays.
        // Vanilla casts ~1352 grid rays stepping 0.3 blocks, re-evaluating
        // getBlockExplosionResistance for the SAME block many times from
        // overlapping rays. This caches the resistance per block (per
        // explosion) - each block pays its resistance computation once.
        // Semantics are identical (a block's resistance does not change during
        // one explosion). Only non-empty (air/fluid default) results skip.
        // 每次爆炸的方块阻力缓存(作用于破坏射线)。原版发射约 1352 条网格
        // 射线按 0.3 方块步进,重叠射线会对同一方块多次重复计算
        // getBlockExplosionResistance。本项按方块缓存阻力(每次爆炸)——每个
        // 方块只计算一次阻力。语义完全一致(单次爆炸中方块阻力不变)。
        // 仅空气/流体的默认空结果不缓存。
        public boolean resistanceCache = true;
        // Air-volume fast path (P1). Both ray phases only read the world
        // through block/fluid lookups; when everything they can touch is air
        // those lookups all answer the same thing:
        //  - getSeenPercent counts rays that MISS and never consults the damage
        //    calculator, so with no block along the rays it is exactly 1.0F;
        //  - calculateExplodedPositions rebuilds analytically: for air the
        //    resistance is Optional.empty() in every vanilla calculator (the
        //    entity-based one keeps it empty), so the power only loses the
        //    fixed per-step constant, while the destroy decision still comes
        //    from the real calculator with the air state. The one
        //    random.nextFloat() per shell ray is consumed in the same order, so
        //    the world RNG sequence is unchanged.
        // Conservative: any non-air palette entry, unloaded chunk, missing
        // section or custom damage calculator keeps the vanilla/Lithium path.
        // 空气体积快速路径(P1)。两个射线阶段只通过方块/流体查询读世界;当
        // 其可能触及的范围全是空气时,这些查询答案恒定:
        //  - getSeenPercent 统计的是"未命中"且不查伤害计算器 ⇒ 无方块时恒为
        //    1.0F;
        //  - calculateExplodedPositions 可解析重建:空气的阻力在所有原版计算器
        //    中都是 Optional.empty()(实体版经 Optional.map 仍为空),因此威力
        //    只按固定步长衰减;而"是否破坏"仍由真实计算器+空气状态判定。每条
        //    外壳射线的 random.nextFloat() 按相同顺序消耗,世界 RNG 序列不变。
        // 保守回退:出现任何非空气调色板项、未加载区块、缺失 section 或自定义
        // 伤害计算器时,一律走原版/Lithium 路径。
        public boolean airVolumeFastPath = true;
    }

    public static class ParticleConfig {
        /**
         * Only create particles where a real player can actually see them.
         * Server-side ServerLevel.sendParticles builds the packet and walks the player
         * list even when nobody is around; with this on, an emission is dropped when no
         * non-bot player is within render distance (view distance in chunks x 16 blocks,
         * a superset of the 32 block radius vanilla actually sends within, so nothing a
         * client could see is ever suppressed). Bot players (carpet style fake players)
         * do not count. Works in single-player (integrated server) and on servers.
         * 粒子仅在有真人玩家能看到时创建:服务端 sendParticles 即使附近没人也会构包并遍历
         * 玩家列表;开启后若渲染距离(视距x16 方块)内没有非 bot 玩家则丢弃该次发射。渲染距离
         * 是原版 32 方块发包范围的超集,因此不会抑制任何客户端本可见的粒子。bot(carpet 等
         * 假玩家)不计入。单人世界与服务器均生效。
         */
        public boolean onlyNearbyPlayers = true;

        /** Ordered rules: first match chain in file order builds the tree. */
        public java.util.List<ParticleRule> rules = new java.util.ArrayList<>(
            java.util.List.of(new ParticleRule("*", 1000)));

        /** One "pattern = limit" line in the [particle] section. */
        public static final class ParticleRule {
            /** "*", "namespace:*" or "namespace:path". */
            public final String pattern;
            /** Per-block particle cap. */
            public final int limit;

            public ParticleRule(String pattern, int limit) {
                this.pattern = pattern;
                this.limit = limit;
            }
        }
    }

    /**
     * Entity optimizations. Nested by entity sub-domain: [entity.projectile]
     * for projectile (arrow / fireball / snowball ...) hit-test options;
     * future sub-tables (e.g. [entity.armor_stand]) for other entity types.
     */
    public static class EntityConfig {
        // Fast entity-section scan. Replaces the vanilla entity-section box
        // query (which allocates a wrapping iterator per call and tests every
        // AABB through a method call) with direct indexed iteration over the
        // backing list and an inlined strict-bound AABB/box overlap test.
        // Semantically identical - benefits every box-based entity query, not
        // just projectiles.
        // 实体区块快速扫描。原版实体 section 盒查询每次调用分配包装迭代器,
        // 并以方法调用测试每个 AABB;本项改为直访底层列表的下标遍历,
        // 并内联严格边界的 AABB/盒相交测试。语义完全一致,惠及所有
        // 盒状实体查询(不限于弹射物)。
        public boolean fastEntitySectionScan = true;

        // Entity stacking display ([entity] stackDisplay): when at least
        // stackDisplayThreshold entities of the SAME type occupy the SAME
        // block, the server sends only ONE of them (the representative) to
        // every client and shows a "xN" nametag above it. Rendering-layer
        // only: ALL entities keep their full vanilla behavior server-side
        // (AI, collision, damage, AOE) - the client just never learns about
        // the hidden ones. The count nametag is sent as a vanilla
        // ClientboundSetEntityDataPacket (no entity data is actually
        // modified), so vanilla clients display it natively. Hidden entities'
        // own name tags are never sent. Blacklisted (never stacked): players,
        // item entities, experience orbs, entities with passengers / riding,
        // bosses (wither / ender dragon) and primed TNT.
        // 实体堆叠显示([entity] stackDisplay):当同一方块内同类实体数量 >=
        // stackDisplayThreshold 时,服务器只向客户端发送其中一只(代表实体),
        // 并在其上方以 nametag 形式显示 "xN" 计数。仅渲染层:所有实体在
        // 服务端保持完整原版行为(AI、碰撞、伤害、范围效果)——客户端只是
        // 不知道被隐藏实体的存在。计数 nametag 以原版数据包发送(不真正
        // 修改实体数据),原版客户端原生显示。被隐藏实体的原 nametag 不再
        // 发送。黑名单(永不堆叠):玩家、物品实体、经验球、带乘客/骑乘
        // 的实体、Boss(凋灵/末影龙)与已点燃 TNT。
        public boolean stackDisplay = false;
        // Minimum count of same-type entities in one block before the stack
        // collapses to a single representative (default 64 - conservative,
        // only mob farms / dedicated grinders hit it in normal play).
        // 单方块内同类实体达到该数量后折叠为单只代表实体显示(默认 64,
        // 保守——正常游玩只有刷怪塔/刷怪机才会触及)。
        public int stackDisplayThreshold = 64;

        // SubSection-granularity entity collision range (applies to EVERY
        // entity query, not just projectiles). When on, box queries against
        // the three-level hittable index start from the 4^3 sub-region
        // (SubSection) buckets and descend to single-block buckets - they no
        // longer walk the section's 16^3 coordinate span. Combined with
        // [entity.projectile] fastIndexedProjectileScan, the projectile scan
        // changes from "16^3 section -> 4^3 sub-region -> single block" to
        // "4^3 sub-region -> single block". When off, the section's full
        // bucket list is swept as before (same results, more bucket visits).
        // 4^3 SubSection 粒度的实体碰撞范围(对所有实体查询生效,不限于
        // 弹射物)。开启时,对三级可命中索引的盒查询直接从 4^3 子区
        // (SubSection)桶起算、下探到单方块桶——不再遍历 section 的整个
        // 16^3 坐标范围。配合 [entity.projectile] fastIndexedProjectileScan
        // 时,弹射物扫描由 "16^3 section -> 4^3 子区 -> 单方块" 变为
        // "4^3 子区 -> 单方块"。关闭时退回遍历该 section 的全部桶
        // (结果一致,桶访问更多)。
        public boolean fastSubSectionEntityScan = true;

        public ProjectileConfig projectile = new ProjectileConfig();

        /** Projectile hit-test optimizations (shared by every projectile). */
        public static class ProjectileConfig {
            // Fast arrow / many-hit projectile test. Replaces the vanilla
            // ProjectileUtil.getManyEntityHitResult inner loop (used by arrows
            // and by getHitEntitiesAlong melee/attack sweeps): the vanilla code
            // builds a candidate List and allocates an AABB + Optional + Vec3
            // per candidate. Here candidates are streamed from the entity
            // sections directly (no List) and the ray test is an inlined
            // zero-allocation slab with the exact vanilla AABB.clip semantics;
            // only actual hits allocate an EntityHitResult. The margin /
            // line-of-sight branch keeps the vanilla block-clip check.
            // 快速箭/多目标弹射物命中检测。替换原版
            // ProjectileUtil.getManyEntityHitResult 内层(箭与近战横扫共用):
            // 原版每候选构建候选 List 并分配 AABB + Optional + Vec3。
            // 本项改为直接从实体区块流式取候选(不建 List),射线测试用
            // 零分配内联 slab(与原版 AABB.clip 逐位一致),仅命中实体
            // 分配 EntityHitResult。margin/视线分支保留原版方块检测。
            public boolean fastArrowHitTest = true;

            // Skip unhittable projectile candidates for projectile callers.
            // Vanilla: a projectile can only be hit by another projectile if
            // its EntityType is in the REDIRECTABLE_PROJECTILE tag
            // (fireball / wind_charge / breeze_wind_charge). The projectile
            // ids whose collision computation is skipped (they are never hit
            // by another projectile - isPickable() == false):
            //   minecraft:arrow, minecraft:spectral_arrow, minecraft:snowball,
            //   minecraft:egg, minecraft:ender_pearl,
            //   minecraft:experience_bottle, minecraft:trident,
            //   minecraft:llama_spit, minecraft:small_fireball,
            //   minecraft:dragon_fireball, minecraft:wither_skull
            // When the hit test caller is itself a Projectile, such candidates
            // are skipped before the box-overlap / predicate work - cutting
            // the O(N^2) arrow-vs-arrow candidate scan in dense arrow clouds
            // to O(N x hittable). Melee sweeps (entity = player) are
            // unaffected (they can hit arrows); mod projectiles that
            // intentionally hit other projectiles should disable this.
            // 弹射物调用方跳过"碰撞计算被跳过的弹射物"候选。弹射物 id 列表:
            //   minecraft:arrow(箭) / minecraft:spectral_arrow(光灵箭) /
            //   minecraft:snowball(雪球) / minecraft:egg(鸡蛋) /
            //   minecraft:ender_pearl(末影珍珠) /
            //   minecraft:experience_bottle(经验瓶) /
            //   minecraft:trident(三叉戟) / minecraft:llama_spit(羊驼口水) /
            //   minecraft:small_fireball(小火球) /
            //   minecraft:dragon_fireball(末影龙火球) /
            //   minecraft:wither_skull(凋灵骷髅头)——它们 isPickable() 恒 false,
            //   任何弹射物的 canHitEntity() 判定恒拒绝它们。当命中测试调用方
            //   本身是弹射物时,可在盒相交/判定之前跳过这类候选——密集箭雨中
            //   把 O(N^2) 的箭-箭候选扫描降为 O(N x 可命中目标)。近战横扫
            //   (调用方为玩家)不受影响(可命中箭);故意命中其它弹射物的 mod
            //   弹射物应关闭此项。
            public boolean skipUnhittableProjectiles = true;

            // Three-level spatial index of hittable entities per section
            // (16^3 section -> 4^3 sub-region -> single block). Only entities
            // with isPickable() == true are indexed (LivingEntity, players,
            // REDIRECTABLE projectiles such as fireballs); arrows, snowballs,
            // tridents etc. (isPickable() == false) are never stored, so a
            // projectile's hit test only ever scans candidates that can
            // actually be hit - the dense-arrow-cloud O(N^2) mutual scan
            // collapses to O(N x local hittable density). Index is maintained
            // on add/remove and lazily self-cleans on query; entity-vs-block
            // collision (Level.clip) is a separate path and unaffected.
            // 每 section 的三级可命中实体空间索引(16^3 section -> 4^3 子区 ->
            // 单方块)。只索引 isPickable() == true 的实体(生物、玩家、
            // REDIRECTABLE 弹射物如火焰弹);箭、雪球、三叉戟等
            // (isPickable() == false) 从不入索引,因此弹射物命中测试只扫描
            // 真正可被命中的候选——密集箭云的 O(N^2) 互相扫描塌缩为
            // O(N x 局部可命中密度)。索引在 add/remove 时维护、查询时惰性
            // 自清理;实体-方块碰撞(Level.clip)是独立路径,不受影响。
            public boolean fastIndexedProjectileScan = true;

            // Skip the block-collision sweep for projectiles whose swept
            // volume sits entirely inside AIR sections (or all-empty 4^3
            // sub-regions when the section is not fully air). A per-tick
            // region-state cache remembers each queried 4^3/section once, so
            // dense clouds sharing the same volume collapse thousands of
            // lookups into one; a miss falls back to the vanilla sweep (no
            // queue, no added cost when regions do not overlap). Only server
            // levels take the fast path (client-side rendering copies always
            // run vanilla). SubSection granularity is preferred; when the
            // horizontal or vertical speed exceeds blockScanSubSectionSpeed
            // (m/s), whole 16^3 sections are judged instead.
            // 弹射物的扫掠体积完全位于空气 Section(或 section 非全空时的空
            // 4^3 子区)内时跳过方块碰撞扫掠。per-tick 区域状态缓存对每个
            // 被查询的 4^3/Section 只判定一次,密集云共享同一体积时上千次
            // 查询塌缩为一次;未命中回退原版扫掠(无队列,区域不重合时零
            // 额外开销)。仅服务器端走快速路径(客户端渲染副本始终原版)。
            // 优先判定 SubSection;当水平或竖直速度超过 blockScanSubSectionSpeed
            // (m/s) 时改判整个 16^3 Section。
            public boolean fastProjectileBlockScan = true;
            public double blockScanSubSectionSpeed = 8.0;
        }
    }

    public static class ChunkConfig {
        // Skip natural spawning in chunks whose 4 neighbours are not fully
        // loaded (non-blocking spawn). NaturalSpawner force-loads candidate
        // chunks with getChunkBlocking, blocking the server thread while the
        // player flies into ungenerated areas - a tick-drop amplifier.
        // 区块或其4邻居未完全加载时跳过该区块自然刷怪(非阻塞刷怪)。
        // 原版刷怪检查会用 getChunkBlocking 强制加载候选区块,飞行进入
        // 未生成区域时阻塞服务器线程,放大掉刻。已加载区域(刷怪塔)不受影响。
        public boolean skipSpawningOnUnreadyChunks = true;
        // Aggregate player chunk-scheduling updates by movement speed.
        // Vanilla re-registers the player's chunk tickets (and recomputes the
        // whole view-distance graph) on every chunk-section change; while
        // flying at 200+ m/s that happens every 1-2 ticks and is a major
        // server-thread cost. Fast players submit their position every N
        // ticks instead (edge chunks load a few ticks later, imperceptible
        // at speed); slow players keep the vanilla per-tick behavior.
        // Teleports / dimension changes always flush immediately.
        // 按玩家移动速度聚合区块调度更新。原版在玩家每次跨区块时重新注册
        // 区块票并重算整个视距图;200+ m/s 飞行时每 1-2 tick 一次,是服务器
        // 线程的主要开销之一。快速玩家改为每 N tick 提交一次位置(边缘区块
        // 晚几个 tick 加载,高速时无感);低速玩家保持原版每 tick 行为。
        // 传送/维度切换总是立即提交。
        public boolean aggregateMovementScheduling = true;
        // Speed (m/s) below which the player keeps vanilla per-tick updates.
        // 低于该速度(m/s)的玩家保持原版每 tick 更新。
        public double movementSchedulingSpeedThreshold = 16.0;
        // Upper bound for the per-player submission interval (ticks).
        // interval = clamp(speed / threshold, 2, max).
        // 玩家提交间隔上限(tick)。interval = clamp(速度/阈值, 2, 上限)。
        public int movementSchedulingMaxInterval = 4;
        // Extra rounds the chunk-loading pump may run per tick when there is a
        // generation/loading backlog (pre-generation, flying into new areas).
        // Vanilla runs the distance-manager updates and the generation-task
        // feeding once per tick; on the client the tick is render-paced, so with
        // a backlog the worldgen workers starve between ticks (Chunky
        // pre-generation shows ~30% CPU). Extra rounds feed the workers more per
        // tick; with no backlog the pump reports no work immediately and the
        // extra rounds cost almost nothing. 1 = vanilla.
        // 每 tick 有生成/加载积压时,区块加载泵额外多跑的轮数。原版每 tick 只
        // 跑一次距离管理器更新+生成任务喂送;客户端 tick 受渲染节奏约束,积压时
        // 世界生成 Worker 会在 tick 间挨饿(Chunky 预生成时 CPU 仅 ~30%)。
        // 额外轮数每 tick 喂更多任务;无积压时泵立即报告无事可做,几乎零开销。
        // 1 = 原版行为。
        public int genUpdatesPerTick = 4;
        // Cache the surface-build biome per column. Vanilla re-creates a memoized
        // biome supplier (and its lambda) on every updateY, so a surface-rule biome
        // condition evaluated at several y levels of a column re-samples the biome
        // source that many times, and each per-y memoize wrapper + lambda is an
        // allocation (two of the largest worldgen allocation sites in the generation
        // JFR). The vanilla overworld / nether / end biome sources are 2D (the y is
        // ignored), so one sample per column is equivalent.
        // 地表构建按列缓存生态群系。原版在每次 updateY 重建记忆化 supplier(及其
        // lambda),地表规则生态群系条件在一列的多个 y 级被求值时会重复采样生态群系
        // 源,且每次重建的 memoize 包装与 lambda 都是分配(生成 JFR 里世界生成最大的
        // 两个分配点)。原版主世界/下界/末地的生态群系源均为 2D(y 被忽略),每列采样
        // 一次等价。其他模组的 3D 生态群系源会产生分歧(使用该列 y=0 的生态群系)。
        public boolean genSurfaceBiomeColumnCache = true;
        // Merge chunk writes to the same region file into batches.
        // Vanilla StorageIoWorker.writeResult() processes one chunk per
        // executor task; this batches up to 64 entries per task, reducing
        // executor overhead and making disk writes more sequential.
        // 合并同一 region 文件的区块写入为批次。原版 StorageIoWorker
        // writeResult() 每任务处理一块;本优化每任务最多处理 64 块,
        // 减少执行器开销,使磁盘写入更连续。
        public boolean mergedWrite = true;
        /**
         * O(1) memo for "is this chunk entity-ticking" (replaces the removed
         * entity-ticking cache). The query runs once per tracked entity per tick and
         * entities cluster in chunks, so the answer is remembered per chunk position in
         * a small direct-mapped table - nothing precomputed, nothing allocated - and the
         * table is dropped whenever the distance manager recomputes ticket levels.
         * O(1) 备忘:实体 ticking 判定每实体每 tick 查一次且实体按块聚集,因此按块位置
         * 记住 vanilla 的结果(小型直接映射表,零分配、零预计算),票据等级重算时清空。
         */
        public boolean entityTickingMemo = true;
        // Reuse per-chunk thread-safety objects (ReentrantLock / Semaphore /
        // ThreadingDetector) instead of creating them per chunk generation.
        // Allocation saving ~5-8% during chunk loading storms.
        public boolean reuseSyncObjects = true;
        // Build chunk packets off the server thread (async packet build).
        // Vanilla serializes all 131 sections on the server thread inside
        // PlayerChunkSender.sendChunk; under a chunk-loading storm that is
        // the dominant server-thread cost and the main cause of tick drops.
        // 区块包构建移出服务器线程。原版在服务器线程串行序列化全部 section,
        // 区块加载风暴时这是服务器线程最大开销,也是掉刻主因。
        public boolean asyncPacketBuild = true;
        // Reuse per-thread region-file read buffers (ByteBuffer for the
        // compressed chunk header). Small allocation saving on IO workers.
        // 复用每线程的区块文件读取缓冲(压缩区块头的 ByteBuffer)。
        // IO 线程少量分配节省。
        public boolean ioBufferReuse = true;
        // Async chunk-packet builder thread count. 0 = auto
        // (2..4 based on CPU count). Only used when asyncPacketBuild is on.
        // 异步区块包构建线程数。0 = 自动(按 CPU 数取 2~4)。
        // 仅在 asyncPacketBuild 开启时生效。
        public int packetBuilderThreads = 0;
        // Serialize unloaded chunk saves off the server thread. While flying,
        // the chunks left behind are unloaded and deep-copied (all sections)
        // on the server thread - the largest single cost in the loading JFR.
        // Only chunks no longer tracked by the ChunkMap (immutable) are moved
        // to a small daemon pool; live periodic saves stay synchronous.
        // 卸载区块的保存序列化移出服务器线程。飞行时身后区块被卸载并在
        // 服务器线程全量深拷贝(全部 section)——加载 JFR 中最大单栈开销。
        // 仅当区块已不被 ChunkMap 追踪(数据不可变)才移入小规模守护线程池;
        // 活跃区块的周期保存保持同步,避免与主线程写入竞争。
        public boolean asyncChunkSaving = true;
        // Pre-size the root CompoundTag of SerializableChunkData.write() to
        // avoid HashMap rehashes while filling dozens of keys. The NBT output
        // is byte-identical; only the backing map capacity changes.
        // 预分配区块保存根 CompoundTag 的 map 容量,避免填充几十个键时
        // HashMap 反复扩容。NBT 输出逐字节一致,仅内部容量变化。
        public boolean saveWritePreallocatedTags = true;
        // Trusted-light skip: chunks loaded from disk whose persisted status
        // is at/after LIGHT and whose stored light is marked complete
        // (pre-generated worlds) skip the INITIALIZE_LIGHT / LIGHT status
        // tasks entirely - no light-source rescan, no lightCorrect(false),
        // no section re-registration. Chunks without complete stored light
        // keep the vanilla path. Light data was already queued into the light
        // engine by SerializableChunkData.read(), and FULL chunks never
        // participate in propagation anyway.
        // 可信光照跳过:从磁盘加载且持久状态 >= LIGHT、存储光照标记完整
        // (预生成世界)的区块,完全跳过 INITIALIZE_LIGHT / LIGHT 状态任务
        // ——不重扫光源、不置 lightCorrect(false)、不重注册 section。
        // 光照不完整的区块(新生成)保持原版路径。光照数据已由
        // SerializableChunkData.read() 排入光照引擎,FULL 区块本就不参与传播。
        public boolean skipLoadedLight = true;
        // Fast block-states parse path inside SerializableChunkData.parse():
        // air-section short-circuit + direct palette read + vanilla unpack,
        // skipping the codec framework layers (biggest load-side CPU cost).
        // 区块解析快路径:空气段短路 + palette 直读 + 原版 unpack,
        // 跳过 codec 框架层(加载侧最大 CPU 开销)。
        public boolean fastChunkParsing = true;
        // Clean loaded chunks stay clean: the LevelChunk disk-load
        // constructor unconditionally marks every loaded chunk dirty, so
        // chunks unloaded behind a flying player get written back even
        // though nothing changed - save throughput equal to load throughput
        // (the round-8 memory blowup: save pool held every pending unload).
        // With this on, chunks whose stored light is complete are not marked
        // on load; real modifications still mark them normally.
        // 加载块保持干净:磁盘加载构造无条件标脏,飞行身后卸载块即使没改动
        // 也写盘——保存吞吐=加载吞吐(round8 内存爆炸根因)。开启后光照完整
        // 的加载块不再无条件标脏;真实修改仍正常标脏。
        public boolean skipCleanChunkSaves = true;
        // DataFixer upgrade skip: chunks already at the current DataVersion
        // skip the vanilla upgrade pass (which still walks the whole NBT tree
        // as a no-op). Older chunks keep the vanilla upgrade path.
        // 跳过 DataFixer 升级:已达当前 DataVersion 的块不再走原版升级遍历
        // (no-op 仍遍历整棵树)。旧版本块保持原版升级。
        public boolean skipDataFixerUpgrade = true;
        // Shared block-state palette containers: sections with identical
        // palettes reuse the same PalettedContainer (copy-on-write guards
        // mutations). Shrinks per-chunk memory and GC scan/relocation
        // traffic - the memory-bandwidth bottleneck.
        //
        // DEFAULT OFF: sharing mutable containers is unsafe in a modded
        // environment - any code path that mutates the container directly
        // (bypassing LevelChunkSection.setBlockState) corrupts every section
        // sharing it and shows up as broken terrain. Kept as an experimental
        // option.
        // 共享方块状态 palette 容器:相同 palette 的 section 复用同一容器
        // (写时复制保护修改)。缩小单块内存与 GC 扫描/搬移流量
        public boolean skipCompletedChunks = true;
        // Reuse the outer buffered stream around every NBT compressed
        // chunk write/read: NbtIo.createCompressorStream /
        // createDecompressorStream wrap every chunk in a fresh
        // BufferedOutputStream / BufferedInputStream (an 8 KiB allocation
        // each). Save/load storms allocate one pair per chunk; the buffers
        // are per-thread reusable (the IO workers each own a thread and fully
        // consume the stream). The inner GZIP compressor stream is still
        // created fresh each time.
        // 复用 NBT 压缩写/读的外层缓冲流:原版每块写读都新建
        // BufferedOutputStream / BufferedInputStream(各 8 KiB 分配)。
        // 保存/加载风暴每块一对;缓冲可每线程复用(IO 线程各占一线程且
        // 流被完整消费)。内部 GZIP 压缩流仍每次新建。
        public boolean writeStreamReuse = true;
        // Region write-back cache: the RegionFile's FileChannel is replaced with
        // an in-memory mirror (chunk.regionFileCaching). Reads and writes both
        // hit the mirror; the mirror is flushed to the real region file as a
        // whole-file atomic .tmp + ATOMIC_MOVE on vanilla auto-save, /save-all,
        // region close and server shutdown. No per-chunk disk seek on load, no
        // cache invalidation on writes; crash window equals the auto-save period.
        // 区块回写缓存:将 RegionFile 的文件通道替换为内存镜像。读和写都走
        // 镜像,镜像在自动保存 / save-all / 区域关闭 / 关服时以整文件原子
        // .tmp + ATOMIC_MOVE 方式回写磁盘。载入无逐块磁盘寻址,写入无缓存
        // 失效;崩溃窗口与原版自动保存周期一致。
        public boolean regionFileCaching = true;
        // Batch chunk status chain: replace thenComposeAsync with thenCompose
        // in ChunkHolder.updateFutures. The status steps run synchronously on
        // the completing thread instead of submitting a new ForkJoinTask per
        // step. Eliminates the AsyncComposeTask allocation for every step in
        // the status chain — especially beneficial when most steps are no-ops
        // (skipped by skipLoadedLight / skipCompletedChunks / skipBiomes /
        // skipStructureReferences). Default=true.
        // 批量区块状态链:将 ChunkHolder.updateFutures 中的 thenComposeAsync
        // 替换为 thenCompose,状态步骤在完成线程上同步执行而非每步提交新
        // ForkJoinTask。消除每步的 AsyncComposeTask 分配——当多数步骤为
        // no-op 时(被 skipLoadedLight/skipCompletedChunks 等跳过)收益最大。
        public boolean batchChain = true;
        // Batch region loading: read all chunks in a region file at once
        // instead of one chunk at a time. Uses the write-back cache mirror
        // to read all 1024 chunk headers + data in one pass, then submits
        // them as a single batch to the Worker-Main pool. Eliminates per-chunk
        // CompletableFuture chain overhead and reduces ForkJoinTask allocation.
        // 批量区域加载:一次性读取区域文件内的所有区块,而非逐个读取。
        // 利用回写缓存镜像一次性读取全部 1024 个区块的头和数据,
        // 然后作为单个批次提交到 Worker-Main 池。
        public boolean regionBatchLoader = true;
        // Flat chunk data format: skip the NBT codec layer for the root
        // CompoundTag of SerializableChunkData. Known fields (xPos, zPos,
        // Status, sections, block_entities, heightmaps, etc.) are read
        // directly via getInt/getString/getTag, eliminating the DataResult
        // + Pair intermediate objects that account for ~14% of allocation.
        // 扁平区块数据格式:跳过 SerializableChunkData 根 CompoundTag 的
        // NBT codec 层。已知字段直接通过 getInt/getString/getTag 读取,
        // 消除 ~14% 分配的 DataResult + Pair 中间对象。
        public boolean flatChunkData = true;
        // Pre-allocate the backing map of CompoundTag to this initial capacity
        // on the no-arg constructor. Chunk root tags carry ~30 keys, so with
        // the vanilla default (16) they resize 1-2 times; every inner section
        // tag resizes once too. HashMap.resize was ~22% of allocation pressure
        // during the loading storm. 0 = vanilla default.
        // 预分配 CompoundTag 底层 map 的初始容量。区块根 tag 约 30 个键,
        // 原版默认 16 会扩容 1-2 次,每个 section tag 也各扩容一次。
        // 加载风暴时 HashMap.resize 约占分配压力 22%。0 = 原版默认。
        public int tagMapCapacity = 16;
    // Right-size the map handed to the CompoundTag(Map) constructor too
    // (codec/serializer path), so it does not pay HashMap.resize while filling.
    public boolean rightSizeTagMapConstructor = true;
    // Canonicalise NBT key names read during chunk load: keep the first instance
    // per distinct name and hand it out again (bounded cache). Value semantics
    // are unchanged; duplicates become garbage immediately.
    public boolean internTagNames = true;
    public int internTagNamesLimit = 8192;
        // Parallel chunk pipeline: move chunk registration + packet building
        // + sending off the Server thread onto the Worker-Main pool. The
        // Server thread only submits the initial load request and receives
        // the final "ready" signal. Requires thread-safe ChunkMap access.
        // 并行区块管线:将区块注册+包构建+发送移出服务器线程到 Worker-Main
        // 池。服务器线程只提交初始加载请求和接收最终"就绪"信号。
        public boolean parallelChunkPipeline = true;
        // Batch packet send: group chunk packets by region and send them
        // as a single batch packet. Reduces Netty send calls from
        // per-chunk (1024) to per-region (1) and eliminates per-packet
        // header overhead (~35 bytes/packet).
        // 批量包发送:按区域分组区块包并作为单个批量包发送。将 Netty 发送
        // 调用从每区块(1024)减少到每区域(1),消除每包头部开销(~35 字节/包)。
        public boolean batchPacketSend = true;
        // Throttle light-storage refresh (LightEngine COW copy). Vanilla
        // clones the whole light data map on every propagation refresh; the
        // clone was ~17% of server-side allocation during fast chunk loading.
        // Refreshes are merged: the copy is a full snapshot, so delaying it
        // only lags the published light by a few ticks (no flicker).
        // 节流光照存储刷新(光照引擎写时复制)。原版每次传播刷新都克隆整个
        // 光照数据 map(快速区块加载时占服务端分配约 17%)。合并刷新:copy
        // 是全量快照,延迟只让光照发布滞后几个 tick(无闪烁)。
        public boolean noLightClone = true;
        // Light refresh decision signal: the pending light queue size.
        // Vanilla LightEngine.runLightUpdates() drains the whole queue and
        // then clones the whole light data map (the COW publish). Skipping
        // the batch defers propagation (queues keep their entries) so light
        // converges a few ticks later. Skip only when the queue is large
        // (chunk-loading floods from a fast player); small queues (block
        // changes near a slow player) always process, so one flying player
        // no longer delays light updates for everyone.
        // 光照刷新决策信号:待处理光照队列大小。原版 runLightUpdates 每次
        // 处理整个队列并克隆整个光照数据 map(写时复制发布)。跳过整批只是
        // 延迟传播(队列保留条目),光照晚几个 tick 收敛。仅在队列较大时
        // (快速玩家造成的区块加载洪峰)跳过;小队列(慢速玩家附近的方块
        // 变化)总是处理——单个飞行玩家不再拖慢所有人的光照更新。
        public int lightSkipQueueThreshold = 256;
        // Chunk packet transmission optimization (server-preference rule:
        // the client always decodes optimized chunk packets if the server
        // sends them; this option only controls whether the client requests
        // them and whether the server produces them). Implementation:
        // section culling: air + below-surface sections are dropped and the
        // client fills them with air (like unloaded chunks). Compression of
        // the kept data is handled globally by [net] useZstd.
        // 区块包传输优化(服务端优先原则:服务端发送优化包时客户端总是能解析;
        // 本项只控制客户端是否请求、服务端是否生成)。实现:Section 剔除
        // (空气 + 地表以下 section 不上传,客户端用空气填充,如同未加载)。
        // 保留数据的压缩由 [net] 组 useZstd 全局处理。
        public boolean sectionCulling = true;
        // Reuse per-thread build buffers for culled chunk payloads (reduces
        // netty ByteBuf allocations on the server build path, measured ~20%
        // of server thread allocation during chunk loading storms).
        // 复用每线程的剔除区块构建缓冲(减少服务端构建路径的 netty ByteBuf
        // 分配,区块加载风暴时实测约占服务器线程分配 20%)。
        public boolean cullingBufferReuse = true;
        // High-speed section culling extension (chunk.sectionCulling).
        // When the closest player has kept a horizontal speed >=
        // sectionCullingHighSpeedThreshold (m/s) for at least
        // sectionCullingHighSpeedTicks consecutive ticks, non-empty sections
        // whose top face is fully covered by the MOTION_BLOCKING heightmap
        // (surface >= section top on every column: the section top is hidden
        // behind other sections) are dropped from the chunk packet too. Air
        // sections and below-surface sections are already dropped at any
        // speed; this only broadens culling to fully-cap-hidden sections for
        // players flying past fast, where the missing sections are
        // imperceptible but the bandwidth saving is large (every dropped
        // section is ~16 KiB before compression on a 2032-height world).
        // 高速 Section 剔除扩展。当最近玩家水平速度连续 >=
        // sectionCullingHighSpeedThreshold(m/s) 达 sectionCullingHighSpeedTicks
        // 个 tick,顶面被 MOTION_BLOCKING 高度图完全覆盖(每列 surface >=
        // section 顶,即顶面被其它 Section 挡住)的非空 Section 也从区块包中
        // 剔除。空气 Section 与地表以下 Section 在任何速度下本已剔除;此项
        // 仅对快速掠过的玩家把剔除扩展到"顶面完全被封"的 Section——高速下
        // 缺失这些 Section 不可察觉,但带宽节省巨大(2032 高度世界每个
        // Section 压缩前约 16 KiB)。
        public double sectionCullingHighSpeedThreshold = 64.0;
        public int sectionCullingHighSpeedTicks = 40;
    }

    /**
     * Client-side command-history settings (command_history.txt). These are
     * purely client-side and are only generated in the CLIENT's config file;
     * the dedicated server does not emit the [client] group.
     */
    public static class ClientConfig {
        // Command history dedup: when a command is executed and already
        // appears in command_history.txt, remove all its other records and
        // write the current one as the last line (move-to-end). Default off
        // (vanilla keeps duplicates, only consecutive ones are collapsed).
        // 命令历史去重:执行命令时若 command_history.txt 已含该命令,移除
        // 其中所有该命令的其他记录,再把本次命令写入最后一行(移至末尾)。
        // 默认关(原版保留重复,仅相邻相同被合并)。
        public boolean commandHistoryDeduplicate = false;
        // Command history: commands that are incorrect (parse errors, entity
        // selector type mismatch e.g. requiring a single entity but selecting
        // multiple) are removed from command_history.txt when exiting the
        // server or singleplayer world. "Entity not found" is NOT incorrect
        // (the command was incomplete) and stays. Default off.
        // 命令历史:命令不正确(语法/解析错误、实体选择器类型不匹配,如要求
        // 单个实体却选中多个)时,退出服务器或单人世界时从 command_history.txt
        // 移除它们。"未找到实体"不算不正确(是命令不完整),保留。默认关。
        public boolean commandHistoryDropError = false;
        // Command history: /tell and /say are treated as chat content and are
        // never saved to command_history.txt. Default off.
        // 命令历史:把 /tell 和 /say 视为聊天内容,不保存到 command_history.txt。
        // 默认关。
        public boolean commandHistoryDropTalk = false;
    }

    /**
     * Data persistence safety settings. Soft-save prevents corrupted files
     * from crashes by writing to .tmp + fsync + atomic rename.
     */
    public static class SafetyConfig {
        // Software-level safe file write (copy-on-write semantics).
        // For NbtIo.writeCompressed / write (level.dat, player data, etc.):
        // write to a .tmp file, fsync, then atomic rename to the target.
        // For region files: fsync every 32-chunk batch via writeChunk.
        // Prevents torn writes and partial-file corruption from crashes.
        // 软件层面安全写入(写时复制语义)。对 NbtIo 路径(level.dat、
        // player data 等): 写 .tmp 文件、fsync、原子 rename 到目标。
        // 对 region 文件: 每 32 块批次 fsync 一次。防止崩溃导致的
        // 写入撕裂和文件损坏。
        public boolean softSaveFileWrite = true;
    }

    /**
     * Global network compression settings. The vanilla
     * network-compression-threshold (server.properties) still decides
     * whether/when compression is enabled and the packet size threshold;
     * only the algorithm is swapped to ZSTD. Requires the mod on both sides.
     *
     * Default OFF: compression setup runs during the login/configuration
     * phase, before any post-join capability handshake, so the client cannot
     * know whether the peer (e.g. a vanilla server) supports ZSTD when it
     * swaps the codec. With the default on, a client would send ZSTD streams
     * to a vanilla zlib server and the connection would die. ZSTD is an
     * explicit opt-in for mod-to-mod setups; vanilla-server clients keep zlib.
     * 全局网络压缩设置。原版 network-compression-threshold(server.properties)
     * 仍控制是否启用压缩及包大小阈值;仅算法替换为 ZSTD。需要双端安装本模组。
     *
     * 默认关闭:压缩在登录/配置阶段建立,早于任何加入后能力握手,客户端无法在
     * 切换编解码器时得知对端(如原版服务器)是否支持 ZSTD。若默认开启,客户端会
     * 向原版 zlib 服务器发送 ZSTD 流导致连接断开。ZSTD 为双端专属的显式可选
     * 配置;连原版服务器时客户端保持 zlib。
     */
    public static class NetConfig {
        // Use ZSTD instead of the vanilla zlib for network compression.
        // Off by default so a client with this mod still joins vanilla
        // servers (they speak zlib).
        // 网络压缩改用 ZSTD(替代原版 zlib)。默认关闭以保证安装本模组的
        // 客户端仍能进入原版服务器(原版用 zlib)。
        public boolean useZstd = true;
        // Server-side ZSTD compression level (1-22, default 4).
        // 服务端 ZSTD 压缩等级(1-22,默认 4)。
        public int zstdLevel = 4;
        // Chunk send rate boost: chunks processed per tick by PlayerChunkSender.
        // Vanilla defaults to a low rate (START=4, max ~8-20) to avoid
        // overwhelming slow clients. Raising it lets fast clients (especially
        // localhost) receive chunks faster. The packets are standard
        // ClientboundLevelChunkWithLightPacket — fully compatible with vanilla
        // clients, no handshake needed (unlike ZSTD which swaps the codec).
        // 0 = vanilla behavior.
        // 区块发送速率提升:PlayerChunkSender 每 tick 处理的区块数。
        // 原版默认很低(起始 4,上限 ~8-20)以免压垮慢客户端。提高后让快
        // 客户端(尤其本地)更快接收区块。发送的是标准区块包——对原版
        // 客户端完全兼容,无需握手(不像 ZSTD 会替换编解码器)。0 = 原版。
        public int chunkSendMaxPerTick = 64;
        // Batch entity position packets into one vanilla ClientboundBundlePacket
        // per player per tick. Vanilla sends one move_entity_pos /
        // entity_position_sync packet per moving entity per tick; with many
        // entities (armor-stand farms, mob farms) that is a packet storm
        // (~150k packets/s in the armor-stand benchmark) that floods the
        // connection and tanks client FPS. The server queues position-only
        // packets per listener and sends a single bundle at tick end; the
        // vanilla client decodes bundles natively (no custom protocol).
        // 实体位置包聚合:每玩家每 tick 将实体位置同步包聚合为一个原版
        // ClientboundBundlePacket。原版每 tick 每个移动实体发一个
        // move_entity_pos/entity_position_sync 包;实体多时(盔甲架农场、
        // 刷怪塔)形成包风暴(盔甲架基准 ~15 万包/s),淹没连接并拖垮客户端
        // FPS。服务器按连接缓存仅位置类包,在 tick 末尾合并发送;原版客户端
        // 原生支持解析 bundle(无需自定义协议)。
        public boolean entityPacketBatching = true;
        // Use UDP instead of the vanilla TCP for the network transport.
        // Falls back to TCP when UDP is unavailable (client or server
        // disabled / unsupported / handshake fails). Default ON.
        // 使用 UDP 替代原版 TCP 网络传输。UDP 不可用(客户端或服务端
        // 关闭/不支持/握手失败)时回退原版 TCP 连接。默认开启。
        public boolean useUDP = true;
        // UDP port the server listens on for the mod-to-mod UDP transport.
        // 0 = use the same port as the vanilla TCP server-port (TCP and UDP
        // are different protocols and can share a port number). >0 forces a
        // dedicated UDP port.
        // 服务器 UDP 监听端口。0 = 与原版 TCP server-port 相同端口(TCP 与
        // UDP 是不同传输层协议,可共用端口号)。>0 使用独立 UDP 端口。
        public int udpPort = 0;
        // Force UDP even when the client has net.useUDP disabled. Server-only:
        // the client config never generates these two keys (forceUseUDP /
        // forceUseZSTD). A client without the mod cannot negotiate and keeps
        // the vanilla TCP transport.
        // 强制 UDP:即使客户端关闭了 useUDP 仍使用 UDP。仅服务端配置——
        // 客户端配置文件不生成这两个键(forceUseUDP/forceUseZSTD)。
        // 未装本 mod 的客户端无法协商,仍走原版 TCP。
        public boolean forceUseUDP = false;
        // Force ZSTD even when the client has net.useZstd disabled (server-only).
        // 强制 ZSTD:即使客户端关闭 useZstd 仍使用 ZSTD(仅服务端)。
        public boolean forceUseZSTD = false;
    }

    /**
     * Diagnostic logging switches. All default off - logging every chunk
     * load/save is expensive and only meant for leak/correctness debugging.
     * 诊断日志开关。全部默认关闭——逐块记录加载/保存很贵,仅用于
     * 泄漏/正确性调试。
     */
    public static class LogConfig {
        // Log every chunk disk load (dimension + chunk pos + byte size after
        // decompression) and save. When on, also includes a 30 s summary of
        // load/save counts, map sizes, and heap usage in the existing diag
        // line. Use this to distinguish "chunks loaded but never saved" from
        // "chunks loaded and saved but never unloaded".
        // 记录每个区块的磁盘加载(维度 + 区块坐标 + 解压后字节数)与保存。
        // 开启后,现有 30 s 诊断行还会包含加载/保存计数、map 大小与堆用量。
        // 用于区分"加载了但没保存"与"加载且保存了但没卸载"。
        public boolean chunkIO = false;
        // Log chunk loads/saves only in the periodic 30 s summary line, not
        // per chunk. Cheaper than chunkIO and enough to see the load/save
        // balance (leak signature: loads >> saves while map size grows).
        // 仅在 30 s 周期摘要行里记录加载/保存计数,不逐块打日志。比
        // chunkIO 便宜,足够看出加载/保存平衡(泄漏特征:map 增长时
        // 加载数 >> 保存数)。
        public boolean chunkSummary = false;
        // Toggle the diag line (heapUsed, culledCache, player count,
        // per-dimension map sizes). Off by default — useful only for leak
        // profiling and memory auditing.
        // 控制诊断行(heapUsed、culledCache、玩家数、维度 map 大小)。
        // 默认关闭——仅用于泄漏分析和内存审计。
        public boolean diagInfo = false;
        // Diag line output interval in ticks (20 ticks = 1 second).
        // Default 600 ticks = 30 seconds. Only used when diagInfo = true.
        // 诊断行输出间隔(tick)。20 tick = 1 秒。默认 600 = 30 秒。
        // 仅在 diagInfo = true 时生效。
        public int diagInfoInterval = 600;
        // Toggle light-refresh throttle log line every 10 s (speed, queue
        // size, threshold, active state). Off by default — useful only for
        // tuning the skip-queue threshold.
        // 控制每 10s 的光照节流日志行(速度、队列大小、阈值、激活状态)。
        // 默认关闭——仅用于调优 lightSkipQueueThreshold。
        public boolean lightThrottle = false;
        // Show chunk load rate (chunks/s) in the diag line. Counts
        // chunks delivered to players by PlayerChunkSender. Requires diagInfo.
        // 在诊断行中显示区块加载速率(区块/s)。统计 PlayerChunkSender
        // 发送给玩家的区块。需要同时开启 diagInfo。
        public boolean chunkLoadRate = false;
        // Log UDP transport connection events (listener bind, probe,
        // migration, tunnel install, active). Off by default - routine INFO
        // lines; warnings/errors are always logged.
        // 记录 UDP 传输连接事件(监听绑定、连通性探测、迁移、隧道安装、
        // 激活)。默认关闭——属于常规 INFO 日志;warn/error 始终记录。
        public boolean udpConnectionLog = false;
        // Log ZSTD compression negotiation events (request, ack, codec swap).
        // Off by default - routine INFO lines; warnings/errors are always logged.
        // 记录 ZSTD 压缩协商事件(请求、ack、编解码器切换)。
        // 默认关闭——属于常规 INFO 日志;warn/error 始终记录。
        public boolean zstdConnectionLog = false;
    }

    public static class CommandConfig {
        // Enable /serveroptimize gc command for manual GC invocation.
        // 开启 /serveroptimize gc 命令用于手动执行垃圾回收。
        public boolean commandGC = true;
        // Enable /serveroptimize status net command (network status report).
        // 开启 /serveroptimize status net 命令(网络状态报告)。
        public boolean commandNetworkStatus = true;
        // Enable /serveroptimize status thread (thread / worker usage report).
        // 开启 /serveroptimize status thread(线程/Worker 使用报告)。
        public boolean commandThreadStatus = true;
        // Shell-style command chaining: ";" (always run next), "&&" (run next
        // only if the previous succeeded) and "||" (run next only if the
        // previous failed) between commands. A command is only split when the
        // WHOLE line fails to parse as a single vanilla command - so greedy
        // arguments (/say a;b, /tellraw ... "x;y") keep their vanilla
        // behavior exactly, and only inputs that vanilla would reject get the
        // new chaining semantics. Signed chat commands (1.19+ security chat)
        // are never split (their signature covers the whole line).
        // 类 shell 命令串联:命令之间支持 ";"(总是执行下一条)、"&&"
        // (上一条成功才执行下一条)与 "||"(上一条失败才执行下一条)。
        // 仅当整行无法作为单条原版命令解析时才拆分——greedy 参数
        // (/say a;b、/tellraw ... "x;y") 保持原版行为完全不变,只有原版
        // 会报错的输入才获得新的串联语义。签名聊天命令(1.19+ 安全聊天)
        // 从不拆分(签名覆盖整行)。
        public boolean bashSyntax = true;
    }

    /**
     * Thread affinity management ([thread]). Pins the server thread and the
     * client render thread to dedicated CPU cores (never the two hardware
     * threads of the same physical core), lets the vanilla worker pool bind
     * its threads to the remaining cores, and coordinates core allocation
     * across multiple Minecraft instances on the same machine.
     *
     * Windows uses SetThreadAffinityMask via JNA (single processor group,
     * up to 64 logical CPUs); Linux uses sched_setaffinity; unsupported
     * platforms are detected and disable themselves.
     *
     * DEFAULT OFF: affinity pinning overrides the OS scheduler. Only enable
     * it when the machine is dedicated to Minecraft. When the machine has a
     * memory-bandwidth bottleneck, pinning cannot create bandwidth - it only
     * improves cache locality / reduces migration.
     * 线程亲和性管理([thread])。将服务端线程与客户端渲染线程锁定到独立
     * CPU 核心(绝不绑定同一物理核心的两个超线程),让原版 worker 池的
     * 线程绑定到其余核心,并在同一台机器的多个 Minecraft 实例间协调分配。
     *
     * Windows 经 JNA 使用 SetThreadAffinityMask(单处理器组,最多 64 逻辑核);
     * Linux 使用 sched_setaffinity;不支持的平台自动检测并禁用自身。
     *
     * 默认关闭:绑定覆盖操作系统调度器,仅当机器专用于 Minecraft 时开启。
     * 内存带宽瓶颈的机器上,绑定不能创造带宽——只改善缓存局部性/减少迁移。
     */
    public static class ThreadConfig {
        // Master switch. 总开关。
        public boolean enabled = true;
        // Pin the dedicated/integrated server thread ("Server thread") to one
        // dedicated core. 将服务端主线程锁定到独立核心。
        public boolean pinServerThread = true;
        // Pin the client render thread ("Render thread") to one dedicated
        // core (client only). 将客户端渲染线程锁定到独立核心(仅客户端)。
        public boolean pinRenderThread = true;
        // Rebuild the vanilla worker pool (Util.backgroundExecutor) with a
        // thread factory that pins each worker to a non-dedicated core.
        // Vanilla already sizes worker count from the process CPU affinity
        // (availableProcessors), so with pinWorkers off the worker COUNT still
        // adapts to the affinity mask; this switch additionally pins the
        // worker THREADS. Uses reflection to replace Util.BACKGROUND_EXECUTOR
        // at server start - keep off unless the pinning is actually needed.
        // 重建原版 worker 池(Util.backgroundExecutor),使每个 worker 线程
        // 绑定到非独占核心。worker 数量本就随进程 CPU 亲和性自适应
        // (availableProcessors),关闭本项时数量自适应仍生效;开启后额外
        // 把 worker 线程本身绑定。通过反射在服务端启动时替换
        // Util.BACKGROUND_EXECUTOR——非必要请保持关闭。
        public boolean pinWorkers = true;
        // Logical CPU for the server thread. -1 = auto (first free core,
        // coordinated across instances). 服务端线程绑定的逻辑核。-1 = 自动
        // (第一个空闲核,跨实例协调)。
        public int serverThreadCore = -1;
        // Logical CPU for the client render thread. -1 = auto.
        // 客户端渲染线程绑定的逻辑核。-1 = 自动。
        public int renderThreadCore = -1;
        // Coordinate core allocation across Minecraft instances on this
        // machine: each instance claims cores via a locked ledger file and
        // prefers cores not used by a live instance. Requires a shared
        // directory (config/ by default - point all instances at the same
        // working directory or same config dir for coordination to work).
        // 在同一台机器的多个 Minecraft 实例间协调核心分配:每个实例通过
        // 加锁的台账文件认领核心,优先选择未被存活实例占用的核心。需要共享
        // 目录(默认 config/——各实例需使用相同工作目录或 config 目录)。
        public boolean coordinateMultiInstance = false;
        // Client only: when connected to a REMOTE server (not a local
        // integrated server), the mod does not start its own background
        // workers / pool rebuild, leaving the CPU to the server. Vanilla
        // client threads that rendering needs are never touched.
        // 仅客户端:连接远程服务器(非本机集成服)时,本模组不启动自己的
        // 后台 worker/不重建线程池,把 CPU 留给服务器。渲染所需的原版
        // 客户端线程绝不触碰。
        public boolean disableWorkersWhenConnected = true;

        /**
         * Parallel thread count for the worker pool this mod uses. 0 = follow
         * EnableThreadScheduler: derive from the allowed CPU set (process affinity
         * intersected with CPU sets) minus the cores dedicated to the server/render
         * threads. A positive value caps it further and can never exceed the allowed
         * set; the threads stay pinned by pinWorkers, and no workers of our own are
         * created - the Minecraft worker pool we rebuild is reused.
         * 并行线程数:0 = 跟随 EnableThreadScheduler(可用 CPU 集合 - 专用核);>0 只在其上再
         * 收窄,永不超过可用集合;线程仍由 pinWorkers 绑核;复用本模组的 Worker 池,不新建。
         */
        public int parallelThreads = 0;

        /**
         * A random-tick pass whose sampling is estimated to take less than this, in
         * milliseconds, runs serial instead of on the worker pool: such a pass is not worth
         * waking the pool for, because the hand-out itself costs a noticeable part of it.
         * The estimate is the number of queued sample positions times a per-position cost
         * remembered from the last parallel pass. 0 keeps every pass parallel.
         * 随机刻采样预估小于该毫秒数时直接串行执行,不唤醒线程池;0 = 始终并行。
         */
        public long randomTickParallelThresholdMs = 10L;

        /**
         * Experimental: entity-tick read-side parallelism - pathfinding, target scanning and
         * collision pre-checks computed on the worker pool. Unlike the random-tick pass these
         * cannot overlap the tick: entity sections are mutated by the very movement being
         * ticked, so the scans must run at a barrier at the start of the entity phase, when
         * nothing has moved yet. The first slice of that design (target scanning) is not
         * implemented yet, so this stays off until it lands - see
         * TestResult/实体多线程-设计方案.md.
         * 实验性:实体 tick 读侧并行(寻路/目标扫描/碰撞预检上 Worker)。实现见设计文档,当前默认关。
         */
        public boolean entityTickParallel = false;

        /** Region-based parallelism settings (维度内分块并行), see {@link RegionBasedConfig}. */
        public RegionBasedConfig regionbased = new RegionBasedConfig();

        /**
         * Region-based parallelism within a dimension: split the level's simulation among
         * workers so one dimension can use more than one core. The only way to keep mods
         * compatible while doing that is bytecode patching - a load-time agent that redirects
         * field access (getfield/putfield) of vanilla and mod classes to per-region storage.
         * Experimental: the agent is not implemented yet, so this stays off.
         * 维度内分块并行:是否使用字节码补丁(字段虚拟化 agent)重定向 vanilla 与模组对
         * Level 内部结构的字段访问。agent 尚未实现,默认关。
         */
        public static class RegionBasedConfig {
            /** Master gate: region-based multithreaded ticking within a dimension.
             *  Default off. Engine is built slice by slice; see TestResult/实体多线程-设计方案.md.
             *  区域多线程总开关,默认关。 */
            public boolean enableRegionBasedMultithreadTicking = false;

            /** Whether the engine uses bytecode patching (field-virtualization agent) to
             *  keep vanilla and mod field access compatible with per-region storage.
             *  Experimental, the agent is not implemented yet, off.
             *  是否使用字节码补丁(字段虚拟化 agent),agent 尚未实现,默认关。 */
            public boolean useBytecodePatch = false;
        }

        /**
         * Worker load balancing diagnostics: the pool is a ForkJoinPool, so idle
         * workers steal queued work by construction; this reports queued tasks and
         * active/parked workers so an imbalance is visible instead of silent.
         * Worker 负载均衡诊断:工作窃取由池自带;此项输出队列深度与活跃/停靠线程数。
         */
        public boolean autoBalance = true;

        /**
         * Section-parallel random ticking. The work unit is the section (vanilla's own
         * unit); only the read-only block-state sampling moves to the workers - the
         * sample positions are drawn on the server thread in vanilla order and the
         * randomTick calls are applied serially on it, so the RNG sequence, the
         * visiting order and every world mutation are unchanged. Workers are the
         * common pool this mod sizes from the allowed CPU set.
         * 随机刻 Section 级并行:只有只读的取态搬到 Worker(抽样位置仍在服务端线程按原顺序
         * 抽取,randomTick 仍串行在该线程执行) ⇒ 随机序列、访问顺序与世界修改均不变。
         */
        public boolean randomTickParallel = true;
    }

    private static final Path CONFIG_PATH = Path.of("config", "server-optimize.toml");

    public static ModConfig load() {
        ModConfig config = new ModConfig();

        if (Files.exists(CONFIG_PATH)) {
            try {
                // Normalize: cache the user's key values, rewrite the file as
                // the default template, then write the cached values back.
                // Keeps the config self-healing (missing keys restored,
                // obsolete keys dropped, formatting unified) while preserving
                // every user setting.
                normalizeConfig();

                // Read as text and strip a UTF-8 BOM: toml4j chokes on the
                // BOM that Windows Notepad and some editors write, which
                // would otherwise crash the server at startup.
                String content = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                    content = content.substring(1);
                }
                Toml toml = new Toml().read(stripParticleSection(content));
                parseParticleSection(content, config.particle);
                config.hopper.enabled = toml.getBoolean("hopper.enabled", true);
                config.hopper.tweakmoreCompat = toml.getBoolean("hopper.tweakmoreCompat", true);
                config.hopper.suppressItemPackets = toml.getBoolean("hopper.suppressItemPackets", true);
                config.hopper.hopperCountDisplay = toml.getBoolean("hopper.hopperCountDisplay", true);
                config.hopper.hopperCountTotal = toml.getBoolean("hopper.hopperCountTotal", true);
                config.hopper.hopperCountPerQuery = toml.getBoolean("hopper.hopperCountPerQuery", false);

                config.tweak.disableMinecartContentSlowdown
                    = toml.getBoolean("tweak.disableMinecartContentSlowdown",
                        toml.getBoolean("fix.disableMinecartContentSlowdown", false));
                config.entity.fastEntitySectionScan = toml.getBoolean("entity.fastEntitySectionScan", true);
                config.explosion.densityBlockCache = toml.getBoolean("explosion.densityBlockCache", true);
                config.explosion.resistanceCache = toml.getBoolean("explosion.resistanceCache", true);
                config.explosion.airVolumeFastPath = toml.getBoolean("explosion.airVolumeFastPath", true);
                config.entity.fastSubSectionEntityScan
                    = toml.getBoolean("entity.fastSubSectionEntityScan", true);
                config.entity.projectile.fastArrowHitTest = toml.getBoolean("entity.projectile.fastArrowHitTest", true);
                config.entity.projectile.skipUnhittableProjectiles
                    = toml.getBoolean("entity.projectile.SkipUnhittableProjectiles",
                        toml.getBoolean("entity.projectile.fastSkipUnhittableProjectiles", true));
                config.entity.projectile.fastIndexedProjectileScan
                    = toml.getBoolean("entity.projectile.fastIndexedProjectileScan", true);
                config.entity.projectile.fastProjectileBlockScan
                    = toml.getBoolean("entity.projectile.fastProjectileBlockScan", true);
                config.entity.projectile.blockScanSubSectionSpeed
                    = toml.getDouble("entity.projectile.blockScanSubSectionSpeed", 8.0);
                config.entity.stackDisplay = toml.getBoolean("entity.stackDisplay", false);
                config.entity.stackDisplayThreshold
                    = Math.max(2, Math.toIntExact(toml.getLong("entity.stackDisplayThreshold", 64L)));
                config.chunk.skipSpawningOnUnreadyChunks
                    = toml.getBoolean("chunk.io.skipSpawningOnUnreadyChunks", true);
                config.chunk.aggregateMovementScheduling
                    = toml.getBoolean("chunk.io.aggregateMovementScheduling", true);
                // toml4j getDouble() throws ClassCastException when the TOML
                // value is an integer literal (e.g. "threshold = 16" is parsed
                // as Long, not Double). The exception MUST be caught here or
                // it propagates past the fallback and skips ALL config loading
                // (which resets everything to defaults and rewrites the file).
                try {
                    config.chunk.movementSchedulingSpeedThreshold
                        = toml.getDouble("chunk.io.movementSchedulingSpeedThreshold", 16.0);
                } catch (ClassCastException e) {
                    config.chunk.movementSchedulingSpeedThreshold
                        = toml.getLong("chunk.io.movementSchedulingSpeedThreshold").doubleValue();
                }
                config.chunk.movementSchedulingMaxInterval
                    = Math.toIntExact(toml.getLong("chunk.io.movementSchedulingMaxInterval", 4L));
                config.chunk.genUpdatesPerTick
                    = Math.max(1, Math.toIntExact(toml.getLong("chunk.generate.genUpdatesPerTick", 4L)));
                config.chunk.genSurfaceBiomeColumnCache
                    = toml.getBoolean("chunk.generate.genSurfaceBiomeColumnCache", true);
                config.chunk.mergedWrite = toml.getBoolean("chunk.io.mergedWrite", true);
                config.chunk.entityTickingMemo
                    = toml.getBoolean("chunk.ticking.entityTickingMemo", true);
                config.particle.onlyNearbyPlayers
                    = toml.getBoolean("particle.onlyNearbyPlayers", true);
                config.safety.softSaveFileWrite = toml.getBoolean("safety.softSaveFileWrite", true);

                config.chunk.reuseSyncObjects = toml.getBoolean("chunk.reuseSyncObjects", true);
                config.chunk.asyncPacketBuild = toml.getBoolean("chunk.asyncPacketBuild", true);
                config.chunk.ioBufferReuse = toml.getBoolean("chunk.ioBufferReuse", true);
                config.chunk.packetBuilderThreads = toml.getLong("chunk.packetBuilderThreads", 0L).intValue();
                config.chunk.asyncChunkSaving = toml.getBoolean("chunk.asyncChunkSaving", true);
                config.chunk.saveWritePreallocatedTags = toml.getBoolean("chunk.saveWritePreallocatedTags", true);
                config.chunk.skipLoadedLight = toml.getBoolean("chunk.skipLoadedLight", true);
                config.chunk.fastChunkParsing = toml.getBoolean("chunk.fastChunkParsing", true);
                config.chunk.skipCleanChunkSaves = toml.getBoolean("chunk.skipCleanChunkSaves", true);
                config.chunk.skipDataFixerUpgrade = toml.getBoolean("chunk.skipDataFixerUpgrade", true);
                config.chunk.skipCompletedChunks = toml.getBoolean("chunk.skipCompletedChunks", true);
                config.chunk.writeStreamReuse = toml.getBoolean("chunk.writeStreamReuse", true);
                config.chunk.regionFileCaching = toml.getBoolean("chunk.regionFileCaching", true);
                config.chunk.batchChain = toml.getBoolean("chunk.batchChain", true);
                config.chunk.regionBatchLoader = toml.getBoolean("chunk.regionBatchLoader", true);
                config.chunk.flatChunkData = toml.getBoolean("chunk.flatChunkData", true);
                config.chunk.tagMapCapacity = Math.max(0, Math.toIntExact(toml.getLong("chunk.tagMapCapacity", 16L)));
                config.chunk.rightSizeTagMapConstructor
                    = toml.getBoolean("chunk.rightSizeTagMapConstructor", true);
                config.chunk.internTagNames = toml.getBoolean("chunk.internTagNames", true);
                config.chunk.internTagNamesLimit
                    = Math.max(0, Math.toIntExact(toml.getLong("chunk.internTagNamesLimit", 8192L)));
                config.chunk.parallelChunkPipeline = toml.getBoolean("chunk.parallelChunkPipeline", true);
                config.chunk.batchPacketSend = toml.getBoolean("chunk.batchPacketSend", true);
                config.chunk.noLightClone = toml.getBoolean("chunk.noLightClone", true);
                config.chunk.lightSkipQueueThreshold
                    = Math.toIntExact(toml.getLong("chunk.lightSkipQueueThreshold", 256L));
                // Section culling was removed as a feature: the switch is no longer read from the
                // config and stays permanently off, so every call site takes the vanilla path.
                config.chunk.sectionCulling = false;
                config.chunk.sectionCullingHighSpeedTicks
                    = Math.toIntExact(toml.getLong("chunk.sectionCullingHighSpeedTicks", 40L));
                config.client.commandHistoryDeduplicate = toml.getBoolean("client.CommandHistoryDeduplicate", false);
                config.client.commandHistoryDropError = toml.getBoolean("client.CommandHistoryDropError", false);
                config.client.commandHistoryDropTalk = toml.getBoolean("client.CommandHistoryDropTalk", false);
                config.net.useZstd = toml.getBoolean("net.useZstd", true);
                config.net.zstdLevel = Math.toIntExact(toml.getLong("net.zstdLevel", 4L));
                config.net.chunkSendMaxPerTick = Math.max(0, Math.min(1024, Math.toIntExact(toml.getLong("net.chunkSendMaxPerTick", 64L))));
                config.net.entityPacketBatching = toml.getBoolean("net.entityPacketBatching", true);
                config.net.useUDP = toml.getBoolean("net.useUDP", true);
                config.net.udpPort = Math.max(0, Math.toIntExact(toml.getLong("net.udpPort", 0L)));
                config.net.forceUseUDP = toml.getBoolean("net.forceUseUDP", false);
                config.net.forceUseZSTD = toml.getBoolean("net.forceUseZSTD", false);

                config.log.chunkIO = toml.getBoolean("log.chunkIO", false);
                config.log.chunkSummary = toml.getBoolean("log.chunkSummary", false);
                config.log.diagInfo = toml.getBoolean("log.diagInfo", false);
                config.log.diagInfoInterval = Math.max(20, Math.toIntExact(toml.getLong("log.diagInfoInterval", 600L)));
                config.log.lightThrottle = toml.getBoolean("log.lightThrottle", false);
                config.log.chunkLoadRate = toml.getBoolean("log.chunkLoadRate", false);
                config.log.udpConnectionLog = toml.getBoolean("log.udpConnectionLog", false);
                config.log.zstdConnectionLog = toml.getBoolean("log.zstdConnectionLog", false);
                config.command.commandGC = toml.getBoolean("command.commandGC", true);
                config.command.commandNetworkStatus = toml.getBoolean("command.commandNetworkStatus", true);
                config.command.commandThreadStatus = toml.getBoolean("command.commandThreadStatus", true);
                config.command.bashSyntax = toml.getBoolean("command.bashSyntax", true);


                config.thread.enabled = toml.getBoolean("thread.schedule.EnableThreadScheduler", true);
                config.thread.pinServerThread = toml.getBoolean("thread.schedule.pinServerThread", true);
                config.thread.pinRenderThread = toml.getBoolean("thread.schedule.pinRenderThread", true);
                config.thread.pinWorkers = toml.getBoolean("thread.schedule.pinWorkers", true);
                config.thread.serverThreadCore = Math.toIntExact(toml.getLong("thread.schedule.serverThreadCore", -1L));
                config.thread.renderThreadCore = Math.toIntExact(toml.getLong("thread.schedule.renderThreadCore", -1L));
                config.thread.coordinateMultiInstance
                    = toml.getBoolean("thread.schedule.coordinateMultiInstance", false);
                config.thread.disableWorkersWhenConnected
                    = toml.getBoolean("thread.schedule.disableWorkersWhenConnected", true);
                config.thread.parallelThreads
                    = Math.max(0, Math.toIntExact(toml.getLong("thread.multithread.parallelThreads", 0L)));
                config.thread.autoBalance
                    = toml.getBoolean("thread.multithread.autoBalance", true);
                config.thread.randomTickParallelThresholdMs
                    = Math.max(0L, toml.getLong("thread.multithread.randomTickParallelThresholdMs", 10L));
                config.thread.entityTickParallel
                    = toml.getBoolean("thread.multithread.entityTickParallel", false);
                config.thread.regionbased.enableRegionBasedMultithreadTicking
                    = toml.getBoolean("thread.multithread.regionbased.EnableRegionBasedMultithreadTicking", false);
                config.thread.regionbased.useBytecodePatch
                    = toml.getBoolean("thread.multithread.regionbased.useBytecodePatch", false);
                    config.thread.randomTickParallel                         = toml.getBoolean("thread.multithread.randomTickParallel", true);

            } catch (Exception e) {
                ServerOptimize.LOGGER.error("Failed to load config, using defaults", e);
            }
        } else {
            saveDefault();
        }

        INSTANCE = config;
        c2meCompatibilityOverride(config);
        com.server_optimize.util.ParticleThrottle.refresh(config.particle.rules);
        ServerOptimize.LOGGER.info(
            "Config: hopper={} tweakmoreCompat={} suppressItemPackets={} hopperCountDisplay={} hopperCountTotal={} hopperCountPerQuery={} disableMinecartContentSlowdown={} explosion(densityBlockCache={} resistanceCache={} airVolumeFastPath={}) entity(fastEntitySectionScan={} fastSubSectionEntityScan={} stackDisplay={} stackDisplayThreshold={} projectile(fastArrowHitTest={} SkipUnhittableProjectiles={} fastIndexedProjectileScan={} fastProjectileBlockScan={} blockScanSubSectionSpeed={})) particle(ruleCount={} onlyNearbyPlayers={}) chunk(reuseSyncObjects={} skipSpawningOnUnreadyChunks={} aggregateMovementScheduling={} speedThreshold={} maxInterval={} genUpdatesPerTick={} genSurfaceBiomeColumnCache={} mergedWrite={} skipLoadedLight={} fastChunkParsing={} skipCleanChunkSaves={} skipDataFixerUpgrade={} skipCompletedChunks={} writeStreamReuse={} tagMapCapacity={} rightSizeTagMapConstructor={} internTagNames={} internTagNamesLimit={} noLightClone={} lightSkipQueueThreshold={} sectionCulling={} cullingBufferReuse={} highSpeedThreshold={} highSpeedTicks={}) safety(softSaveFileWrite={}) net(useZstd={} zstdLevel={} entityPacketBatching={} useUDP={} udpPort={}) client(commandHistoryDeduplicate={} commandHistoryDropError={} commandHistoryDropTalk={}) log(chunkIO={} chunkSummary={} diagInfo={} diagInfoInterval={} lightThrottle={} chunkLoadRate={} udpConnectionLog={} zstdConnectionLog={}) command(commandGC={} commandNetworkStatus={} commandThreadStatus={} bashSyntax={}) thread.schedule(EnableThreadScheduler={} pinServerThread={} pinRenderThread={} pinWorkers={} serverThreadCore={} renderThreadCore={} coordinateMultiInstance={} disableWorkersWhenConnected={})",
            config.hopper.enabled,
            config.hopper.tweakmoreCompat,
            config.hopper.suppressItemPackets,
            config.hopper.hopperCountDisplay,
            config.hopper.hopperCountTotal,
            config.hopper.hopperCountPerQuery,
            config.tweak.disableMinecartContentSlowdown,
            config.explosion.densityBlockCache,
            config.explosion.resistanceCache,
            config.explosion.airVolumeFastPath,
            config.entity.fastEntitySectionScan,
            config.entity.fastSubSectionEntityScan,
            config.entity.stackDisplay,
            config.entity.stackDisplayThreshold,
            config.entity.projectile.fastArrowHitTest,
            config.entity.projectile.skipUnhittableProjectiles,
            config.entity.projectile.fastIndexedProjectileScan,
            config.entity.projectile.fastProjectileBlockScan,
            config.entity.projectile.blockScanSubSectionSpeed,
            config.particle.rules.size(),
            config.particle.onlyNearbyPlayers,
            config.chunk.reuseSyncObjects,
            config.chunk.skipSpawningOnUnreadyChunks,
            config.chunk.aggregateMovementScheduling,
            config.chunk.movementSchedulingSpeedThreshold,
            config.chunk.movementSchedulingMaxInterval,
            config.chunk.genUpdatesPerTick,
            config.chunk.genSurfaceBiomeColumnCache,
            config.chunk.mergedWrite,
            config.chunk.skipLoadedLight,
            config.chunk.fastChunkParsing,
            config.chunk.skipCleanChunkSaves,
            config.chunk.skipDataFixerUpgrade,
            config.chunk.skipCompletedChunks,
            config.chunk.writeStreamReuse,
            config.chunk.tagMapCapacity,
            config.chunk.rightSizeTagMapConstructor,
            config.chunk.internTagNames,
            config.chunk.internTagNamesLimit,
            config.chunk.noLightClone,
            config.chunk.lightSkipQueueThreshold,
            config.chunk.sectionCulling,
            config.chunk.cullingBufferReuse,
            config.chunk.sectionCullingHighSpeedThreshold,
            config.chunk.sectionCullingHighSpeedTicks,
            config.safety.softSaveFileWrite,
            config.net.useZstd,
            config.net.zstdLevel,
            config.net.entityPacketBatching,
            config.net.useUDP,
            config.net.udpPort,
            config.client.commandHistoryDeduplicate,
            config.client.commandHistoryDropError,
            config.client.commandHistoryDropTalk,
            config.log.chunkIO,
            config.log.chunkSummary,
            config.log.diagInfo,
            config.log.diagInfoInterval,
            config.log.lightThrottle,
            config.log.chunkLoadRate,
            config.log.udpConnectionLog,
            config.log.zstdConnectionLog,
            config.command.commandGC,
            config.command.commandNetworkStatus,
            config.command.commandThreadStatus,
            config.command.bashSyntax,
            config.thread.enabled,
            config.thread.pinServerThread,
            config.thread.pinRenderThread,
            config.thread.pinWorkers,
            config.thread.serverThreadCore,
            config.thread.renderThreadCore,
            config.thread.coordinateMultiInstance,
            config.thread.disableWorkersWhenConnected
         );

        return config;
    }

    /**
     * Incompatible-mod detection (feature 1: C2ME / Accelerated Recoiling).
     * <p>
     * C2ME: when loaded, disable the optimizations that overlap with its
     * rewritten chunk system / storage. Keeping both active corrupted region
     * files (two writers on the same chunks) in earlier testing. The
     * overrides are runtime-only - the config file is left untouched, so
     * removing C2ME restores the full feature set on the next start.
     * <p>
     * Accelerated Recoiling: rewrites arrow/projectile physics internals
     * (arrow entities, their damage source bookkeeping). This mod's
     * projectile hit-test optimizations (fastArrowHitTest,
     * skipUnhittableProjectiles, fastIndexedProjectileScan,
     * fastProjectileBlockScan) operate on the same code paths and are
     * disabled to avoid duplicated/conflicting logic. Explosion/entity
     * caches that do not touch projectile physics stay active.
     * <p>
     * The detected mod ids are exposed (compatibilityDetectedMods) so the
     * entry point can log a visible warning at startup.
     */
    public static volatile String compatibilityDetectedMods = "";

    private static void c2meCompatibilityOverride(ModConfig config) {
        try {
            FabricLoader fl = FabricLoader.getInstance();
            java.util.List<String> detected = new java.util.ArrayList<>();
            boolean c2me = fl.isModLoaded("c2me") || fl.isModLoaded("c2me-base");
            if (c2me) {
                detected.add("C2ME");
            }
            boolean recoil = fl.isModLoaded("accelerated-recoiling")
                || fl.isModLoaded("acceleratedrecoiling")
                || fl.isModLoaded("accelerated_recoiling");
            if (recoil) {
                detected.add("Accelerated Recoiling");
            }
            compatibilityDetectedMods = String.join(", ", detected);
            if (c2me) {
                config.chunk.aggregateMovementScheduling = false;
                config.chunk.ioBufferReuse = false;
                config.chunk.asyncChunkSaving = false;
                ServerOptimize.LOGGER.info(
                    "C2ME detected: disabled conflicting optimizations (asyncChunkSaving, ioBufferReuse, aggregateMovementScheduling)");
            }
            if (recoil) {
                config.entity.projectile.fastArrowHitTest = false;
                config.entity.projectile.skipUnhittableProjectiles = false;
                config.entity.projectile.fastIndexedProjectileScan = false;
                config.entity.projectile.fastProjectileBlockScan = false;
                config.entity.fastSubSectionEntityScan = false;
                ServerOptimize.LOGGER.info(
                    "Accelerated Recoiling detected: disabled projectile hit-test optimizations (fastArrowHitTest, skipUnhittableProjectiles, fastIndexedProjectileScan, fastProjectileBlockScan, fastSubSectionEntityScan)");
            }
        } catch (Throwable t) {
            ServerOptimize.LOGGER.warn("Failed to check incompatible mods presence, assuming not loaded", t);
        }
    }

    /**
     * Rewrites the config file as the default template and writes the user's
     * cached key values back. Values are loaded into memory first, then the
     * default template is written, then the cached values are applied to it.
     * Keys that do not exist in the default template are dropped together
     * with their values (obsolete options self-prune, no hard-coded key list
     * to keep in sync). If the old file cannot be parsed at all (e.g. toml4j
     * rejects non-ASCII section names), it is regenerated from scratch and
     * the broken content is dropped entirely.
     */
    private static void normalizeConfig() {
        String oldContent;
        try {
            // Step 1: load the user's key values into memory.
            oldContent = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            if (!oldContent.isEmpty() && oldContent.charAt(0) == '\uFEFF') {
                oldContent = oldContent.substring(1);
            }
        } catch (IOException e) {
            ServerOptimize.LOGGER.warn("Failed to read config (keeping existing file)", e);
            return;
        }

        // User's [particle.limit.block] rules are preserved across the
        // rewrite (orders rules; would be dropped by the legal-key pass
        // otherwise, and toml4j cannot parse bare "*" keys). Rule keys are
        // re-quoted so a stale bare "* = 1000" is upgraded to "\"*\" = 1000".
        String particleRaw = normalizeParticleSection(extractParticleSection(oldContent));
        String oldStripped = stripParticleSection(oldContent);

        java.util.Map<String, Object> flat = new java.util.LinkedHashMap<>();
        try {
            // Duplicate keys (e.g. array keys appended twice by older
            // versions) make toml4j throw; drop earlier duplicates first.
            String deduped = dedupeKeys(oldStripped);
            java.util.Map<String, Object> cached = new com.moandjiezana.toml.Toml().read(deduped).toMap();
            flatten("", cached, flat);
        } catch (Exception e) {
            // Unparseable user file: cannot preserve anything reliably, so
            // drop it all and regenerate the default template.
            ServerOptimize.LOGGER.warn("Config unparseable ({}), regenerating from defaults", e.toString());
            saveConfig(new ModConfig());
            return;
        }

        try {
            // Step 2: write the fresh default template.
            saveConfig(new ModConfig());

            // Step 3: the legal key set is whatever the default template
            // actually contains; apply the cached values back only for those.
            String template = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            if (!template.isEmpty() && template.charAt(0) == '\uFEFF') {
                template = template.substring(1);
            }
            java.util.Map<String, Object> tmap = new com.moandjiezana.toml.Toml()
                .read(stripParticleSection(template)).toMap();
            java.util.Map<String, Object> tflat = new java.util.LinkedHashMap<>();
            flatten("", tmap, tflat);
            java.util.Set<String> legal = tflat.keySet();

            String content = template;
            for (java.util.Map.Entry<String, Object> e : flat.entrySet()) {
                // Step 4: keys absent from the default template are dropped.
                if (!legal.contains(e.getKey())) {
                    continue;
                }
                String literal = formatValue(e.getValue());
                if (literal != null) {
                    content = replaceKeyValue(content, e.getKey(), literal);
                }
            }
            // Step 5: restore the user's [particle] rules verbatim.
            content = restoreParticleSection(content, particleRaw);
            writeAtomically(formatConfigContent(content));
        } catch (Exception e) {
            ServerOptimize.LOGGER.warn("Failed to normalize config (keeping existing file)", e);
        }
    }

    /**
     * Drops duplicate "key = value" lines, keeping the first occurrence.
     * Section-aware: duplicates are only dropped within the SAME section
     * ("[hopper] enabled" and "[thread] enabled" are different keys). The
     * previous bare-key dedupe silently deleted the thread section's enabled
     * line, resetting thread.enabled to its default on every normalize.
     */
    private static String dedupeKeys(String content) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        String section = "";
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            java.util.regex.Matcher sec =
                java.util.regex.Pattern.compile("^\\[([^\\]]+)\\]").matcher(trimmed);
            if (sec.find()) {
                section = sec.group(1).trim();
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z0-9_.]+)\\s*=").matcher(trimmed);
            if (m.find()) {
                String key = section.isEmpty() ? m.group(1) : section + "." + m.group(1);
                if (!seen.add(key)) {
                    changed = true;
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        return changed ? out.toString() : content;
    }

    private static void flatten(String prefix, java.util.Map<String, Object> map, java.util.Map<String, Object> out) {
        for (java.util.Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> sub = (java.util.Map<String, Object>) e.getValue();
                flatten(key, sub, out);
            } else {
                out.put(key, e.getValue());
            }
        }
    }

    private static String formatValue(Object value) {
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof String) {
            return "\"" + value + "\"";
        }
        return null; // lists/tables/others are not written back
    }

    /**
     * Replaces the value of a dotted key ("hopper.enabled") inside the config
     * text. The file uses bare keys under section headers, so the dotted key
     * is resolved to its section range and the bare key is replaced only
     * within that range (prevents cross-section collisions). The dotted prefix
     * is the section name itself: "entity.projectile.someOption" lives under
     * the [entity.projectile] header, so a single header lookup suffices for
     * any nesting depth.
     */
    private static String replaceKeyValue(String content, String key, String literal) {
        String[] parts = key.split("\\.");
        if (parts.length == 1) {
            return replaceInRange(content, 0, content.length(), parts[0], literal);
        }
        String sectionName = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 1));
        int secStart = findSectionStart(content, sectionName);
        if (secStart < 0) {
            // No matching section header at all: fall back to a whole-file
            // bare-key replace (best effort, cannot collide with a header).
            return replaceInRange(content, 0, content.length(), parts[parts.length - 1], literal);
        }
        int secEnd = findNextSectionStart(content, secStart + sectionName.length() + 2);
        if (secEnd < 0) {
            secEnd = content.length();
        }
        return replaceInRange(content, secStart, secEnd, parts[parts.length - 1], literal);
    }

    private static int findSectionStart(String content, String section) {
        String header = "[" + section + "]";
        int lineStart = 0;
        for (int i = 0; i <= content.length(); i++) {
            if (i == content.length() || content.charAt(i) == '\n') {
                String line = content.substring(lineStart, i).stripTrailing();
                // Section headers may be indented (e.g. " [particle.limit.block]"
                // inside its parent table); compare on the stripped line.
                String stripped = line.strip();
                if (stripped.equals(header)
                    || stripped.startsWith(header + " ")
                    || stripped.startsWith(header + "\t")
                    || stripped.startsWith(header + "#")) {
                    return lineStart;
                }
                lineStart = i + 1;
            }
        }
        return -1;
    }

    private static int findNextSectionStart(String content, int from) {
        int lineStart = from;
        for (int i = from; i <= content.length(); i++) {
            if (i == content.length() || content.charAt(i) == '\n') {
                String line = content.substring(lineStart, i).stripTrailing();
                String stripped = line.strip(); // headers may be indented
                if (stripped.startsWith("[") && stripped.endsWith("]")) {
                    return lineStart;
                }
                lineStart = i + 1;
            }
        }
        return -1;
    }

    /**
     * The raw "[particle.limit.block]" section text (including the header),
     * or "" when absent. Per-block particle-limit rules use patterns like
     * "*" / "minecraft:*" which toml4j rejects as bare keys, so the section
     * is handled textually. Future particle optimizations live in sibling
     * sub-groups under [particle].
     */
    private static String extractParticleSection(String content) {
        String section = "particle.limit.block";
        int start = findSectionStart(content, section);
        if (start < 0) {
            return "";
        }
        int end = findNextSectionStart(content, start + section.length() + 2);
        if (end < 0) {
            end = content.length();
        }
        return content.substring(start, end);
    }

    /** Removes the "[particle.limit.block]" section so toml4j can parse the rest. */
    private static String stripParticleSection(String content) {
        String sec = extractParticleSection(content);
        if (sec.isEmpty()) {
            return content;
        }
        int start = content.indexOf(sec);
        String head = content.substring(0, start);
        String tail = content.substring(start + sec.length());
        return head + "# [particle.limit.block] rules parsed separately\n" + tail;
    }

    /**
     * Replaces the "[particle.limit.block]" section in {@code content} with
     * {@code userRaw} (the user's lines): keeps custom rule rows across the
     * normalize rewrite. An empty {@code userRaw} keeps the template default.
     */
    private static String restoreParticleSection(String content, String userRaw) {
        String sec = extractParticleSection(content);
        if (sec.isEmpty()) {
            return userRaw.isEmpty() ? content : content + "\n" + userRaw;
        }
        if (userRaw.isEmpty()) {
            return content;
        }
        int start = content.indexOf(sec);
        return content.substring(0, start) + userRaw + content.substring(start + sec.length());
    }

    /**
     * Rebuilds the user's "[particle.limit.block]" section with every rule
     * key properly quoted ("*" / "minecraft:*" are not valid bare TOML keys).
     * Comment lines, the header and empty lines are kept verbatim; rule rows
     * keep their indentation and trailing comments. Used before the section
     * is restored across a normalize rewrite so a stale bare "* = 1000" from
     * older builds is upgraded instead of being written back as-is.
     */
    private static String normalizeParticleSection(String raw) {
        if (raw.isEmpty()) {
            return raw;
        }
        StringBuilder out = new StringBuilder();
        int lineStart = 0;
        for (int i = 0; i <= raw.length(); i++) {
            if (i == raw.length() || raw.charAt(i) == '\n') {
                String line = raw.substring(lineStart, i);
                // A trailing segment (raw ends with '\n') must not produce a
                // stray extra newline on every reload.
                if (!(i == raw.length() && lineStart == raw.length())) {
                    String stripped = line.strip();
                    if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("[")) {
                        out.append(line).append("\n");
                    } else {
                        int hash = line.indexOf('#');
                        String body = hash >= 0 ? line.substring(0, hash) : line;
                        int eq = body.indexOf('=');
                        if (eq > 0) {
                            String pattern = body.substring(0, eq).trim()
                                .replace("\"", "").replace("'", "");
                            String limit = body.substring(eq + 1).trim();
                            String indent = line.substring(0, line.length() - line.stripLeading().length());
                            out.append(indent).append('"').append(pattern).append("\" = ").append(limit);
                            if (hash >= 0) {
                                out.append(line.substring(hash));
                            }
                            out.append("\n");
                        } else {
                            out.append(line).append("\n");
                        }
                    }
                }
                lineStart = i + 1;
            }
        }
        return out.toString();
    }

    /**
     * Parses the "[particle.limit.block]" section's ordered
     * "pattern = limit" lines. File order is priority; a particle's effective
     * per-block cap is the minimum of all matching rules. Falls back to
     * "* = 1000".
     */
    private static void parseParticleSection(String content, ParticleConfig cfg) {
        cfg.rules.clear();
        String sec = extractParticleSection(content);
        if (sec.isEmpty()) {
            cfg.rules.add(new ParticleConfig.ParticleRule("*", 1000));
            return;
        }
        int lineStart = 0;
        for (int i = 0; i <= sec.length(); i++) {
            if (i == sec.length() || sec.charAt(i) == '\n') {
                String line = sec.substring(lineStart, i);
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                line = line.trim();
                if (!line.isEmpty() && !(line.startsWith("[") && line.endsWith("]"))) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String pattern = line.substring(0, eq).trim()
                            .replace("\"", "").replace("'", "");
                        String limitStr = line.substring(eq + 1).trim();
                        try {
                            int limit = Integer.parseInt(limitStr);
                            if (limit > 0 && !pattern.isEmpty()) {
                                cfg.rules.add(new ParticleConfig.ParticleRule(pattern, limit));
                            }
                        } catch (NumberFormatException ignored) {
                            // ignore malformed rule lines (comments etc.)
                        }
                    }
                }
                lineStart = i + 1;
            }
        }
        if (cfg.rules.isEmpty()) {
            cfg.rules.add(new ParticleConfig.ParticleRule("*", 1000));
        }
    }

    private static String replaceInRange(String content, int start, int end, String bare, String literal) {
        String head = content.substring(0, start);
        String body = content.substring(start, end);
        String tail = content.substring(end);
        String quoted = java.util.regex.Pattern.quote(bare);
        String newBody = body.replaceAll(
            "(?m)^(\\s*" + quoted + "\\s*=\\s*)[^\\r\\n]*",
            "$1" + java.util.regex.Matcher.quoteReplacement(literal));
        return head + newBody + tail;
    }

    private static void saveDefault() {
        saveConfig(new ModConfig());
    }

    /**
     * Normalises the generated TOML layout (formatting only, the file stays
     * semantically identical):
     * <ul>
     *   <li>sub-table headers are indented by nesting depth - one space per dot,
     *       so "[chunk.io]" becomes " [chunk.io]" under "[chunk]" - and their
     *       bodies by twice that, matching the existing [entity] /
     *       [entity.projectile] convention;</li>
     *   <li>every "key = value" entry is followed by exactly one blank line, so
     *       the comment block of the next entry stays visually separated;</li>
     *   <li>runs of blank lines collapse to one, trailing blanks are dropped.</li>
     * </ul>
     */
    /**
     * Writes the config through a sibling temp file and then atomically replaces the
     * target, so a crash or a torn write can never leave a half-written config behind.
     * The temp file is named "<config>.tmp" and is removed on failure paths.
     */
    private static void writeAtomically(String content) throws java.io.IOException {
        java.nio.file.Path tmp = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, CONFIG_PATH, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException e) {
            // file system without atomic replace (or the target existed): plain replace
            Files.move(tmp, CONFIG_PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String formatConfigContent(String content) {
        StringBuilder out = new StringBuilder(content.length() + 256);
        int depth = 0;
        boolean blankPending = false;
        for (String raw : content.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                blankPending = out.length() > 0;
                continue;
            }
            if (blankPending) {
                out.append('\n');
                blankPending = false;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                int dots = 0;
                for (int i = 0; i < line.length(); i++) {
                    if (line.charAt(i) == '.') {
                        dots++;
                    }
                }
                depth = dots;
                out.append(" ".repeat(depth)).append(line).append('\n');
                continue;
            }
            out.append("  ".repeat(depth)).append(line).append('\n');
            boolean entry = line.indexOf('=') > 0 && !line.startsWith("#");
            boolean continued = line.endsWith("[") || line.endsWith(",") || line.endsWith("{");
            if (entry && !continued) {
                blankPending = true;
            }
        }
        return out.toString();
    }

    private static void saveConfig(ModConfig config) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            writeAtomically(formatConfigContent(buildConfigContent(config)));
            ServerOptimize.LOGGER.info("Created default config at {}", CONFIG_PATH.toAbsolutePath());
        } catch (IOException e) {
            ServerOptimize.LOGGER.error("Failed to create default config", e);
        }
    }

    private static String buildConfigContent(ModConfig config) {
        return ""
            + "# Server Optimize Mod Configuration\n"
            + "# 服务器优化模组配置\n"
            + "\n"
            + "[hopper]\n"
            + " # Enable hopper time-wheel optimization: idle hoppers sleep until a neighbor\n"
            + " # change or container entity wakes them; active hoppers run on an 8-tick\n"
            + " # circular time wheel.\n"
            + " # 启用漏斗时间轮优化。空闲漏斗休眠,直到邻居变化或容器实体唤醒;\n"
            + " # 活跃漏斗使用 8 tick 环形时间轮调度。\n"
            + " enabled = " + config.hopper.enabled + "\n"
            + "\n"
            + " # Tweakmore compatibility: derive TransferCooldown from the time wheel only\n"
            + " # when a sync/update tag is requested, instead of writing the vanilla\n"
            + " # cooldown field during every hopper tick.\n"
            + " # Tweakmore 兼容:仅在请求同步/更新标签时从时间轮推导 TransferCooldown,\n"
            + " # 漏斗每次 tick 不再写回原版冷却字段。\n"
            + " tweakmoreCompat = " + config.hopper.tweakmoreCompat + "\n"
            + "\n"
            + " # Suppress item data packets for hoppers: inventory changes are not sent to\n"
            + " # clients unless a player opens the hopper GUI. Reduces network traffic.\n"
            + " # 抑制漏斗物品数据包:漏斗物品变化不发送给客户端,除非玩家打开漏斗界面。\n"
            + " # 减少玩家路过大量漏斗时的网络流量。\n"
            + " suppressItemPackets = " + config.hopper.suppressItemPackets + "\n"
            + "\n"
            + " # Client F3 debug display switches (screen only, never affect mechanics).\n"
            + " # 客户端 F3 调试显示开关(仅影响屏幕显示,不影响漏斗机制)。\n"
            + " # Show the per-hopper cooldown state in the F3 debug screen.\n"
            + " # 在 F3 调试界面显示每个漏斗的冷却状态。\n"
            + " hopperCountDisplay = " + config.hopper.hopperCountDisplay + "\n"
            + " # Also show the total active-hopper count in the F3 debug screen.\n"
            + " # 在 F3 调试界面额外显示活跃漏斗总数。\n"
            + " hopperCountTotal = " + config.hopper.hopperCountTotal + "\n"
            + " # When enabled, hopper counts are fetched per query instead of cached\n"
            + " # per tick (slightly fresher, slightly more work).\n"
            + " # 启用后每次查询实时获取漏斗计数,而非每 tick 缓存(数据稍新,开销稍大)。\n"
            + " hopperCountPerQuery = " + config.hopper.hopperCountPerQuery + "\n"
            + "\n"
            + "[tweak]\n"
            + " # Disable the vanilla container-minecart slowdown caused by inventory\n"
            + " # contents. Only affects the experimental minecart movement behavior;\n"
            + " # the legacy minecart behavior remains vanilla.\n"
            + " # 关闭原版容器矿车因物品内容导致的减速。仅影响实验性矿车移动行为,\n"
            + " # 旧版矿车行为保持原版。\n"
            + " disableMinecartContentSlowdown = " + config.tweak.disableMinecartContentSlowdown + "\n"
            + "\n"
            + "[entity]\n"
            + " # Fast entity-section scan: iterate the backing entity list with\n"
            + " # indexed access and an inlined strict-bound AABB/box overlap test\n"
            + " # instead of the vanilla per-call wrapping iterator + method-call\n"
            + " # test. Semantically identical; benefits every box-based entity\n"
            + " # query (projectiles, collisions, ...).\n"
            + " # 实体区块快速扫描:直访底层实体列表做下标遍历,内联严格边界的\n"
            + " # AABB/盒相交测试,替代原版每次调用分配包装迭代器 + 方法调用测试。\n"
            + " # 语义完全一致,惠及所有盒状实体查询(弹射物、碰撞等)。\n"
            + " fastEntitySectionScan = " + config.entity.fastEntitySectionScan + "\n"
            + "\n"
            + " # SubSection-granularity entity collision range (applies to EVERY\n"
            + " # entity query, not just projectiles). When on, box queries\n"
            + " # against the three-level hittable index start from the 4^3\n"
            + " # sub-region (SubSection) buckets and descend to single-block\n"
            + " # buckets - they no longer walk the section's 16^3 span. With\n"
            + " # [entity.projectile] fastIndexedProjectileScan on, the\n"
            + " # projectile scan changes from \"16^3 section -> 4^3 sub-region\n"
            + " # -> single block\" to \"4^3 sub-region -> single block\".\n"
            + " # 4^3 SubSection 粒度的实体碰撞范围(对所有实体查询生效,不限于\n"
            + " # 弹射物)。开启时,对三级可命中索引的盒查询直接从 4^3 子区桶\n"
            + " # 起算、下探到单方块桶,不再遍历 section 的 16^3 范围。配合\n"
            + " # [entity.projectile] fastIndexedProjectileScan 时,弹射物扫描\n"
            + " # 由 \"16^3 section -> 4^3 子区 -> 单方块\" 变为 \"4^3 子区 ->\n"
            + " # 单方块\"。\n"
            + " fastSubSectionEntityScan = " + config.entity.fastSubSectionEntityScan + "\n"
            + "\n"
            + " # Stacking display (rendering-layer only): when at least\n"
            + " # stackDisplayThreshold entities of the SAME type occupy the SAME\n"
            + " # block, the server sends only ONE of them (the representative)\n"
            + " # to every client and shows a \"xN\" count nametag above it. ALL\n"
            + " # entities keep their full vanilla behavior server-side (AI,\n"
            + " # collision, damage, AOE) - the client just never learns about\n"
            + " # the hidden ones. The count nametag is a vanilla data packet\n"
            + " # (no entity data is modified), so vanilla clients display it\n"
            + " # natively; hidden entities' own nametags are never sent.\n"
            + " # Blacklisted (never stacked): players, item entities, experience\n"
            + " # orbs, entities with passengers / riding, bosses and primed TNT.\n"
            + " # 堆叠显示(仅渲染层):当同一方块内同类实体数量 >=\n"
            + " # stackDisplayThreshold 时,服务器只向客户端发送其中一只(代表\n"
            + " # 实体),并在其上方以 nametag 显示 \"xN\" 计数。所有实体在服务端\n"
            + " # 保持完整原版行为(AI、碰撞、伤害、范围效果)——客户端只是不\n"
            + " # 知道被隐藏实体的存在。计数 nametag 是原版数据包(不修改实体\n"
            + " # 数据),原版客户端原生显示;被隐藏实体的原 nametag 不再发送。\n"
            + " # 黑名单(永不堆叠):玩家、物品实体、经验球、带乘客/骑乘实体、\n"
            + " # Boss 与已点燃 TNT。\n"
            + " stackDisplay = " + config.entity.stackDisplay + "\n"
            + " stackDisplayThreshold = " + config.entity.stackDisplayThreshold + "\n"
            + "\n"
            + " [entity.projectile]\n"
            + "  # Fast arrow / many-hit projectile test: stream candidates from\n"
            + "  # the entity sections directly (no candidate List) and test the\n"
            + "  # ray with an inlined zero-allocation slab identical to vanilla\n"
            + "  # AABB.clip; only actual hits allocate an EntityHitResult. Affects\n"
            + "  # arrows and the getHitEntitiesAlong melee/attack sweep.\n"
            + "  # 快速箭/多目标弹射物命中检测:直接从实体区块流式取候选(不建\n"
            + "  # 候选 List),射线测试用零分配内联 slab(与原版 AABB.clip 逐位\n"
            + "  # 一致),仅命中实体分配 EntityHitResult。作用于箭与近战横扫。\n"
            + "  fastArrowHitTest = " + config.entity.projectile.fastArrowHitTest + "\n"
            + "\n"
            + "  # Skip collision computation for mutually-unhittable projectiles when\n"
            + "  # the hit-test caller is itself a projectile. Projectile ids whose\n"
            + "  # collision is skipped (isPickable() == false, never hit by another\n"
            + "  # projectile): minecraft:arrow, minecraft:spectral_arrow,\n"
            + "  # minecraft:snowball, minecraft:egg, minecraft:ender_pearl,\n"
            + "  # minecraft:experience_bottle, minecraft:trident,\n"
            + "  # minecraft:llama_spit, minecraft:small_fireball,\n"
            + "  # minecraft:dragon_fireball, minecraft:wither_skull. Cuts the\n"
            + "  # dense-arrow-cloud O(N^2) candidate scan to O(N x hittable).\n"
            + "  # Disable for mod projectiles that intentionally hit others.\n"
            + "  # 命中测试调用方是弹射物时,跳过互相不可命中弹射物的碰撞计算。\n"
            + "  # 碰撞计算被跳过的弹射物 id(isPickable() 恒 false,不会被其它\n"
            + "  # 弹射物命中):minecraft:arrow(箭) / minecraft:spectral_arrow\n"
            + "  # (光灵箭) / minecraft:snowball(雪球) / minecraft:egg(鸡蛋) /\n"
            + "  # minecraft:ender_pearl(末影珍珠) /\n"
            + "  # minecraft:experience_bottle(经验瓶) / minecraft:trident(三叉戟)\n"
            + "  # / minecraft:llama_spit(羊驼口水) /\n"
            + "  # minecraft:small_fireball(小火球) /\n"
            + "  # minecraft:dragon_fireball(末影龙火球) /\n"
            + "  # minecraft:wither_skull(凋灵骷髅头)。把密集箭雨的 O(N^2) 候选\n"
            + "  # 扫描降为 O(N x 可命中)。故意命中其它弹射物的 mod 弹射物应关闭。\n"
            + "  SkipUnhittableProjectiles = " + config.entity.projectile.skipUnhittableProjectiles + "\n"
            + "\n"
            + "  # Three-level spatial index of hittable entities per section\n"
            + "  # (16^3 section -> 4^3 sub-region -> single block). Only\n"
            + "  # isPickable() entities are indexed (LivingEntity, players,\n"
            + "  # REDIRECTABLE projectiles); arrows/snowballs/tridents stay out,\n"
            + "  # so dense arrow clouds no longer scan each other (breaks the\n"
            + "  # O(N^2) mutual hit-test scan to O(N x local hittable density).\n"
            + "  # Entity-vs-block collision is a separate path, unaffected.\n"
            + "  # 每 section 的三级可命中实体空间索引(16^3 -> 4^3 子区 -> 单方块)。\n"
            + "  # 只索引 isPickable() 实体(生物/玩家/REDIRECTABLE 弹射物);箭、\n"
            + "  # 雪球、三叉戟等不入索引,密集箭云不再互相扫描(把平方命中测试\n"
            + "  # 降为 O(N x 局部可命中密度))。实体-方块碰撞为独立路径,不受影响。\n"
            + "  fastIndexedProjectileScan = " + config.entity.projectile.fastIndexedProjectileScan + "\n"
            + "\n"
            + "  # Skip the block-collision sweep for projectiles whose swept\n"
            + "  # volume sits entirely inside AIR sections (or all-empty 4^3\n"
            + "  # sub-regions when the section is not fully air). A per-tick\n"
            + "  # region-state cache remembers each queried 4^3/section once;\n"
            + "  # dense clouds sharing one volume collapse thousands of lookups\n"
            + "  # into one. SubSection granularity is preferred; when the\n"
            + "  # horizontal or vertical speed exceeds blockScanSubSectionSpeed\n"
            + "  # (m/s), whole 16^3 sections are judged instead.\n"
            + "  # 弹射物扫掠体积完全位于空气 Section(或 section 非全空时的空 4^3\n"
            + "  # 子区)时跳过方块碰撞扫掠。per-tick 区域状态缓存对每个被查询的\n"
            + "  # 4^3/Section 只判定一次,密集云共享同一体积时上千次查询塌缩为\n"
            + "  # 一次。优先判定 SubSection;水平或竖直速度超过\n"
            + "  # blockScanSubSectionSpeed(m/s) 时改判整个 16^3 Section。\n"
            + "  fastProjectileBlockScan = " + config.entity.projectile.fastProjectileBlockScan + "\n"
            + "  blockScanSubSectionSpeed = " + config.entity.projectile.blockScanSubSectionSpeed + "\n"
            + "\n"
            + "[explosion]\n"
            + " # Entity damage-density block cache, applied ON TOP of the\n"
            + " # vanilla grid-ray algorithm (destruction unchanged). Vanilla\n"
            + " # getSeenPercent casts one clip ray per entity per sampling\n"
            + " # point; this caches the result per block of the entity (per\n"
            + " # explosion), so dense crowds on the same blocks share one ray.\n"
            + " # Slight approximation: all entities on one block share the\n"
            + " # density of the first one sampled (boundary of shielded areas).\n"
            + " # 实体伤害密度按方块缓存(叠加在原版网格射线之上,破坏行为不变)。\n"
            + " # 原版 getSeenPercent 对每个实体的每个采样点发一条 clip 射线;\n"
            + " # 本项按实体所在方块缓存结果(每次爆炸),同方块密集实体共享一条\n"
            + " # 射线。轻微近似:同方块实体共享首个采样实体的密度。\n"
            + " densityBlockCache = " + config.explosion.densityBlockCache + "\n"
            + " # Per-explosion block-resistance cache for the destruction rays:\n"
            + " # overlapping grid rays re-evaluate getBlockExplosionResistance\n"
            + " # for the same block many times; this caches it per block (per\n"
            + " # explosion). Identical semantics.\n"
            + " # 每次爆炸的方块阻力缓存:重叠网格射线对同一方块多次重复计算\n"
            + " # 阻力;本项按方块缓存(每次爆炸)。语义完全一致。\n"
            + " resistanceCache = " + config.explosion.resistanceCache + "\n"
            + " # Air-volume fast path (P1): when every block the explosion rays\n"
            + " # can touch is air, the per-entity seen-percent is exactly 1.0 and\n"
            + " # the destruction position set is rebuilt analytically (identical\n"
            + " # float arithmetic and RNG consumption; the destroy decision still\n"
            + " # comes from the damage calculator). Conservative: any non-air\n"
            + " # block, unloaded chunk, missing section or custom calculator keeps\n"
            + " # the vanilla/Lithium path.\n"
            + " # 空气体积快速路径(P1):爆炸射线可触及范围全是空气时,逐实体可见度\n"
            + " # 恒为 1.0,破坏位置集合按解析法重建(浮点算术与 RNG 消耗完全一致;\n"
            + " # 是否破坏仍由伤害计算器判定)。保守回退:任何非空气方块、未加载\n"
            + " # 区块、缺失 section 或自定义计算器都会走原版/Lithium 路径。\n"
            + " airVolumeFastPath = " + config.explosion.airVolumeFastPath + "\n"
            + "\n"
            + "[particle]\n"
            + " # Particle optimizations live in sub-groups. Currently only the\n"
            + " # per-block particle limit exists ([particle.limit.block]); future\n"
            + " # particle optimizations get their own sub-group.\n"
            + " # 粒子优化放在子组中。目前只有单方块粒子上限\n"
            + " # ([particle.limit.block]);后续粒子优化使用各自子组。\n"
            + " # Particles are only created where a real (non-bot) player is within\n"
            + " # render distance; nothing a client could see is suppressed.\n"
            + " # 仅在有真人玩家可见(渲染距离内)时创建粒子。\n"
            + " onlyNearbyPlayers = " + config.particle.onlyNearbyPlayers + "\n"
            + "\n"
            + " [particle.limit.block]\n"
            + "  # Per-block particle caps, ordered rules. Each line is\n"
            + "  # \"pattern = cap\"; the EARLIER a rule appears the higher its\n"
            + "  # priority. Patterns: \"*\" (all particles), \"namespace:*\" (all\n"
            + "  # of one namespace, e.g. minecraft:*), \"namespace:path\" (one\n"
            + "  # particle type). A particle's effective per-block cap is the\n"
            + "  # MINIMUM of every matching rule. When a block is over its cap,\n"
            + "  # remaining allowance is distributed to the sub-categories\n"
            + "  # weighted by min(actual count, own cap), so a minority particle\n"
            + "  # type is never starved by another's flood. Enforcement is on the\n"
            + "  # SERVER: once a block already has its cap of particles, further\n"
            + "  # spawns are not sent/processed.\n"
            + "  # 单方块粒子上限,规则有序。每行 \"pattern = 上限\";越靠上的规则\n"
            + "  # 优先级越高。pattern 支持 \"*\"(全部粒子)、\"namespace:*\"(某命名\n"
            + "  # 空间全部,如 minecraft:*)、\"namespace:path\"(单个粒子类型)。\n"
            + "  # 粒子的生效上限 = 所有匹配规则的最小值。方块超上限时,剩余额度\n"
            + "  # 按 min(实际数, 自身上限) 加权分配给子类,避免少数派粒子被其它\n"
            + "  # 粒子的洪流饿死。在服务端执行:方块内粒子达到上限后,新的发射\n"
            + "  # 不再发送/处理。\n"
            + "  # NOTE: '*' and 'namespace:*' are NOT valid bare TOML keys (they\n"
            + "  # contain '*'), so quote them: \"*\" = 1000. Unquoted keys are\n"
            + "  # still accepted by this mod (the section is parsed textually).\n"
            + "  # 注意:'*' 与 'namespace:*' 含 '*' 不是合法裸 TOML 键,需加引号:\n"
            + "  # \"*\" = 1000。本模组也接受不带引号的裸键(该节按文本解析)。\n"
            + "  \"*\" = 1000\n"
            + "\n"
            + "[safety]\n"
            + " # Software-level safe file write (copy-on-write semantics).\n"
            + " # Prevents torn writes and partial-file corruption.\n"
            + " # 软件层面安全写入(写时复制语义),防止写入撕裂。\n"
            + " softSaveFileWrite = " + config.safety.softSaveFileWrite + "\n"
            + "\n"
            + "[chunk]\n"
            + " # Reuse per-chunk thread-safety objects (ReentrantLock / Semaphore /\n"
            + " # ThreadingDetector) instead of creating them per chunk generation.\n"
            + " # Allocation saving ~5-8% during chunk loading storms.\n"
            + " # 复用每区块的线程安全对象(ReentrantLock / Semaphore /\n"
            + " # ThreadingDetector),不再每次区块生成时新建。\n"
            + " # 区块加载风暴时分配可省 ~5-8%。\n"
            + " reuseSyncObjects = " + config.chunk.reuseSyncObjects + "\n"
            + "\n"
            + " # Build chunk packets off the server thread. Vanilla serializes\n"
            + " # all sections on the server thread inside PlayerChunkSender;\n"
            + " # under a chunk-loading storm that is the dominant server-thread\n"
            + " # cost and the main cause of tick drops. Off-thread build moves\n"
            + " # packet construction to a small daemon pool; the server thread\n"
            + " # only submits and the connection send is thread-safe.\n"
            + " # 区块包构建移出服务器线程。原版在服务器线程串行序列化全部 section,\n"
            + " # 区块加载风暴时这是服务器线程最大开销,也是掉刻主因。改为小规模\n"
            + " # 守护线程池异步构建,服务器线程只提交,连接发送本身线程安全。\n"
            + " asyncPacketBuild = " + config.chunk.asyncPacketBuild + "\n"
            + "\n"
            + " # Reuse per-thread region-file read buffers (compressed chunk\n"
            + " # header ByteBuffer). Small allocation saving on IO workers.\n"
            + " # 复用每线程的区块文件读取缓冲(压缩区块头 ByteBuffer)。\n"
            + " # IO 线程少量分配节省。\n"
            + " ioBufferReuse = " + config.chunk.ioBufferReuse + "\n"
            + "\n"
            + " # Async chunk-packet builder thread count. 0 = auto (2..4 based\n"
            + " # on CPU count). Only used when asyncPacketBuild is on.\n"
            + " # 异步区块包构建线程数。0 = 自动(按 CPU 数取 2~4)。\n"
            + " # 仅在 asyncPacketBuild 开启时生效。\n"
            + " packetBuilderThreads = " + config.chunk.packetBuilderThreads + "\n"
            + "\n"
            + " # Serialize unloaded chunk saves off the server thread. While\n"
            + " # flying, the chunks left behind are unloaded and deep-copied\n"
            + " # (all sections) on the server thread - the largest single cost\n"
            + " # in the loading JFR. Only chunks no longer tracked by the\n"
            + " # ChunkMap (immutable) are moved to a small daemon pool; live\n"
            + " # periodic saves stay synchronous.\n"
            + " # 卸载区块的保存序列化移出服务器线程。飞行时身后区块被卸载并在\n"
            + " # 服务器线程全量深拷贝(全部 section)——加载 JFR 中最大单栈开销。\n"
            + " # lightCorrect(false), no section re-registration. Chunks without\n"
            + " # complete stored light keep the vanilla path.\n"
            + " # 可信光照跳过:预生成世界的光照完整区块完全跳过光照状态任务。\n"
            + " # 光照不完整的区块保持原版路径。\n"
            + " skipLoadedLight = " + config.chunk.skipLoadedLight + "\n"
            + " # Fast block-states parse path: air-section short-circuit +\n"
            + " # direct palette read + vanilla unpack (load-side CPU cut).\n"
            + " # 区块解析快路径:空气段短路 + palette 直读 + 原版 unpack。\n"
            + " fastChunkParsing = " + config.chunk.fastChunkParsing + "\n"
            + " # Clean loaded chunks stay clean: chunks loaded from disk with\n"
            + " # complete light are not marked dirty on load, so unloaded\n"
            + " # chunks behind a flying player are not written back. Real\n"
            + " # modifications still mark them normally.\n"
            + " # 加载块保持干净:光照完整的加载块不再无条件标脏,飞行身后\n"
            + " # 卸载块不写盘。真实修改仍正常标脏。\n"
            + " skipCleanChunkSaves = " + config.chunk.skipCleanChunkSaves + "\n"
            + " # DataFixer upgrade skip: chunks already at the current\n"
            + " # DataVersion skip the vanilla upgrade pass. Older chunks keep\n"
            + " # the vanilla upgrade path.\n"
            + " # 跳过 DataFixer 升级:当前 DataVersion 的块跳过升级遍历。\n"
            + " skipDataFixerUpgrade = " + config.chunk.skipDataFixerUpgrade + "\n"
            + " # Completed-chunk skip: chunks already persisted as FULL (with\n"
            + " # complete stored light) skip the FULL status task, which would\n"
            + " # otherwise submit a fresh async task (ForkJoinTask + lambda)\n"
            + " # and spin up a WorldGenRegion per chunk - a no-op for fully\n"
            + " # generated disk chunks.\n"
            + " # 已完成区块跳过:持久状态已达 FULL(光照完整)的区块跳过 FULL\n"
            + " # 状态任务。该任务原本每块提交一个新异步任务(ForkJoinTask +\n"
            + " # lambda)并创建 WorldGenRegion——对已完整生成的磁盘区块是完全\n"
            + " # 的 no-op。\n"
            + " skipCompletedChunks = " + config.chunk.skipCompletedChunks + "\n"
            + "\n"
            + " # Reuse the outer buffered stream around every NBT compressed\n"
            + " # chunk write/read (8 KiB allocated per block in vanilla).\n"
            + " # Per-thread reuse; the inner GZIP compressor is still fresh.\n"
            + " # 复用 NBT 压缩写/读的外层缓冲流(原版每块分配 8 KiB)。\n"
            + " # 每线程复用;内部 GZIP 压缩流仍每次新建。\n"
            + " writeStreamReuse = " + config.chunk.writeStreamReuse + "\n"
            + " # Cache whole region file on first chunk read; invalidated on write.\n"
            + " # 整 region 文件缓存:首次读入内存,后续同一 region 内 chunk 不走磁盘。\n"
            + " regionFileCaching = " + config.chunk.regionFileCaching + "\n"
            + " # Batch chunk status chain: inline thenComposeAsync → thenCompose\n"
            + " # 批量区块状态链:thenComposeAsync 替换为 thenCompose\n"
            + " batchChain = " + config.chunk.batchChain + "\n"
            + " # Batch region loading: read all 1024 chunks in a region at once\n"
            + " # 批量区域加载:一次性读取区域文件内全部 1024 个区块\n"
            + " regionBatchLoader = " + config.chunk.regionBatchLoader + "\n"
            + " # Offload region reads to the parallel reader pool (lock-free, batch draining).\n"
            + " # 区域读取并行化(无锁读取池,批量排空)。\n"
            + " asyncChunkSaving = " + config.chunk.asyncChunkSaving + "\n"
            + "\n"
            + " # Pre-size the root tag map when writing chunk NBT (fewer rehashes on save).\n"
            + " # 写入区块 NBT 时预分配根 tag map(减少保存端扩容)。\n"
            + " saveWritePreallocatedTags = " + config.chunk.saveWritePreallocatedTags + "\n"
            + " # Flat chunk data: skip NBT codec for SerializableChunkData root tag\n"
            + " # 扁平区块数据:跳过 SerializableChunkData 根 tag 的 NBT codec\n"
            + " flatChunkData = " + config.chunk.flatChunkData + "\n"
            + " # Preallocation capacity for CompoundTag backing map (chunk NBT tags).\n"
            + " # 0 = vanilla default (16). 64 avoids rehash on ~30-key chunk tags.\n"
            + " # CompoundTag 底层 map 预分配容量。0 = 原版默认(16)。64 可避免\n"
            + " # 约 30 键的区块 tag 扩容重hash。\n"
            + " tagMapCapacity = " + config.chunk.tagMapCapacity + "\n"
            + " # Also right-size the map handed to the CompoundTag(Map) constructor\n"
            + " # (codec / serializer path), so it does not pay HashMap.resize.\n"
            + " # 同时对 CompoundTag(Map) 构造路径做等尺寸化,避免 HashMap 扩容拷贝。\n"
            + " rightSizeTagMapConstructor = " + config.chunk.rightSizeTagMapConstructor + "\n"
            + "\n"
            + " # Keep one instance per distinct NBT key name while reading chunk NBT\n"
            + " # (bounded cache; value semantics unchanged).\n"
            + " # 读取区块 NBT 时,对每个不同的键名只保留一个实例(有上限;语义不变)。\n"
            + " internTagNames = " + config.chunk.internTagNames + "\n"
            + " internTagNamesLimit = " + config.chunk.internTagNamesLimit + "\n"
            + " # Parallel chunk pipeline: move registration+packet off server thread\n"
            + " # 并行区块管线:将注册+包构建移出服务器线程\n"
            + " parallelChunkPipeline = " + config.chunk.parallelChunkPipeline + "\n"
            + " # Batch packet send: group chunk packets by region and send as batch\n"
            + " # 批量包发送:按区域分组区块包并作为批量包发送\n"
            + " batchPacketSend = " + config.chunk.batchPacketSend + "\n"
            + " # Throttle light-storage refresh (LightEngine COW copy). Vanilla\n"
            + " # clones the whole light data map on every propagation refresh; the\n"
            + " # clone was ~17% of server-side allocation during fast chunk loading.\n"
            + " # Refreshes are merged: the copy is a full snapshot, so delaying it\n"
            + " # only lags the published light by a few ticks (no flicker).\n"
            + " # 节流光照存储刷新(光照引擎写时复制)。原版每次传播刷新都克隆整个\n"
            + " # 光照数据 map(快速区块加载时占服务端分配约 17%)。合并刷新:copy\n"
            + " # 是全量快照,延迟只让光照发布滞后几个 tick(无闪烁)。\n"
            + " noLightClone = " + config.chunk.noLightClone + "\n"
            + " # Light refresh decision signal: the pending light queue size.\n"
            + " # Vanilla LightEngine.runLightUpdates() drains the whole queue and\n"
            + " # then clones the whole light data map (the COW publish). Skipping\n"
            + " # the batch defers propagation (queues keep their entries) so light\n"
            + " # converges a few ticks later. Skip only when the queue is large\n"
            + " # (chunk-loading floods from a fast player); small queues (block\n"
            + " # changes near a slow player) always process, so one flying player\n"
            + " # no longer delays light updates for everyone.\n"
            + " # 光照刷新决策信号:待处理光照队列大小。原版 runLightUpdates 每次\n"
            + " # 处理整个队列并克隆整个光照数据 map(写时复制发布)。跳过整批只是\n"
            + " # 延迟传播(队列保留条目),光照晚几个 tick 收敛。仅在队列较大时\n"
            + " # (快速玩家造成的区块加载洪峰)跳过;小队列(慢速玩家附近的方块\n"
            + " # 变化)总是处理——单个飞行玩家不再拖慢所有人的光照更新。\n"
            + " lightSkipQueueThreshold = " + config.chunk.lightSkipQueueThreshold + "\n"
            + " # Chunk packet transmission optimization (server-preference rule: the\n"
            + " # client always decodes optimized chunk packets if the server sends\n"
            + " # them). Implementation: section culling; compression is global\n"
            + " # ([net] useZstd).\n"
            + " # 区块包传输优化(服务端优先原则:服务端发送优化包时客户端总是能解析)。\n"
            + " # 实现:Section 剔除;压缩由 [net] useZstd 全局处理。\n"
            + " # Section culling was removed; the setting is gone and the feature is" + "\\n"
            + " # permanently disabled (vanilla chunk packets are sent unchanged)." + "\\n"
            + " # Reuse per-thread build buffers for culled chunk payloads.\n"
            + " # 复用每线程的剔除区块构建缓冲。\n"
            + " # High-speed section culling extension: when the closest player has\n"
            + " # kept a horizontal speed >= sectionCullingHighSpeedThreshold (m/s)\n"
            + " # for at least sectionCullingHighSpeedTicks consecutive ticks,\n"
            + " # non-empty sections whose top face is fully covered by the\n"
            + " # MOTION_BLOCKING heightmap (surface >= section top on every column:\n"
            + " # hidden behind other sections) are dropped from the chunk packet\n"
            + " # too. Air / below-surface sections are dropped at any speed; this\n"
            + " # only broadens culling to fully-cap-hidden sections for players\n"
            + " # flying past fast. 0 disables the speed window (always cull).\n"
            + " # 高速 Section 剔除扩展:最近玩家水平速度连续 >=\n"
            + " # sectionCullingHighSpeedThreshold(m/s) 达\n"
            + " # sectionCullingHighSpeedTicks 个 tick 时,顶面被 MOTION_BLOCKING\n"
            + " # 高度图完全覆盖(每列 surface >= section 顶,即被其它 Section\n"
            + " # 挡住)的非空 Section 也从区块包剔除。空气/地表以下 Section 任何\n"
            + " # 速度下本已剔除;此项仅对快速掠过的玩家把剔除扩展到完全封顶的\n"
            + " # Section。0 = 禁用速度窗口(始终剔除)。\n"
            + "[chunk.io]\n"
            + " # Skip natural spawning in chunks whose 4 neighbours are not\n"
            + " # fully loaded (non-blocking spawn). NaturalSpawner force-loads\n"
            + " # candidate chunks with getChunkBlocking, blocking the server\n"
            + " # thread while the player flies into ungenerated areas - a\n"
            + " # tick-drop amplifier. Fully loaded areas (spawn farms) are\n"
            + " # unaffected.\n"
            + " # 区块或其4邻居未完全加载时跳过该区块自然刷怪(非阻塞刷怪)。\n"
            + " # 原版刷怪检查会用 getChunkBlocking 强制加载候选区块,飞行进入\n"
            + " # 未生成区域时阻塞服务器线程,放大掉刻。已加载区域(刷怪塔)不受影响。\n"
            + " skipSpawningOnUnreadyChunks = " + config.chunk.skipSpawningOnUnreadyChunks + "\n"
            + "\n"
            + " # Aggregate player chunk-scheduling updates by movement speed.\n"
            + " # Vanilla re-registers the player's chunk tickets (and recomputes\n"
            + " # the whole view-distance graph) on every chunk-section change;\n"
            + " # while flying at 200+ m/s that happens every 1-2 ticks and is a\n"
            + " # major server-thread cost. Fast players submit their position\n"
            + " # every N ticks instead (edge chunks load a few ticks later,\n"
            + " # imperceptible at speed); slow players keep the vanilla per-tick\n"
            + " # behavior. Teleports / dimension changes always flush immediately.\n"
            + " # 按玩家移动速度聚合区块调度更新。原版在玩家每次跨区块时重新注册\n"
            + " # 区块票并重算整个视距图;200+ m/s 飞行时每 1-2 tick 一次,是服务器\n"
            + " # 线程的主要开销之一。快速玩家改为每 N tick 提交一次位置(边缘区块\n"
            + " # 晚几个 tick 加载,高速时无感);低速玩家保持原版每 tick 行为。\n"
            + " # 传送/维度切换总是立即提交。\n"
            + " aggregateMovementScheduling = " + config.chunk.aggregateMovementScheduling + "\n"
            + " # Speed (m/s) below which the player keeps vanilla per-tick updates.\n"
            + " # 低于该速度(m/s)的玩家保持原版每 tick 更新。\n"
            + " movementSchedulingSpeedThreshold = " + config.chunk.movementSchedulingSpeedThreshold + "\n"
            + " # Upper bound for the per-player submission interval (ticks).\n"
            + " # interval = clamp(speed / threshold, 2, max).\n"
            + " # 玩家提交间隔上限(tick)。interval = clamp(速度/阈值, 2, 上限)。\n"
            + " movementSchedulingMaxInterval = " + config.chunk.movementSchedulingMaxInterval + "\n"
            + "\n"
            + " # Merge chunk writes to the same region file into batches.\n"
            + " # Reduces executor overhead and makes disk writes sequential.\n"
            + " # 合并同一 region 文件的区块写入为批次,减少执行器开销。\n"
            + " mergedWrite = " + config.chunk.mergedWrite + "\n"
            + "\n"
            + "[chunk.generate]\n"
            + " # Extra loading-pump rounds per tick when a generation/loading backlog\n"
            + " # exists (pre-generation, flying into new areas). The client's tick is\n"
            + " # render-paced, so the worldgen workers starve between ticks otherwise\n"
            + " # (Chunky pre-generation shows ~30% CPU). 1 = vanilla. Default 4.\n"
            + " # 每 tick 有生成/加载积压时,区块加载泵额外多跑的轮数。客户端 tick 受\n"
            + " # 渲染节奏约束,否则世界生成 Worker 会在 tick 间挨饿(Chunky 预生成时\n"
            + " # CPU 仅 ~30%)。1 = 原版行为。默认 4。\n"
            + " genUpdatesPerTick = " + config.chunk.genUpdatesPerTick + "\n"
            + " # Cache the surface-build biome per column (the vanilla biome sources\n"
            + " # are 2D, so the per-y re-samples and their memoize/lambda allocations\n"
            + " # are redundant). Default on.\n"
            + " # 地表构建按列缓存生态群系(原版生态群系源为 2D,每 y 的重复采样及其\n"
            + " # memoize/lambda 分配是冗余的)。默认开启。\n"
            + " genSurfaceBiomeColumnCache = " + config.chunk.genSurfaceBiomeColumnCache + "\n"
            + "\n"
            + "[chunk.ticking]\n"
            + "\n"
            + " # O(1) memo for the per-entity entity-ticking query.\n"
            + " # 实体 ticking 判定的 O(1) 备忘。\n"
            + " entityTickingMemo = " + config.chunk.entityTickingMemo + "\n"
            + "\n"
            + (com.server_optimize.util.ServerOptimizeEnv.isClient()
                ? " # Client-side command-history settings (command_history.txt).\n"
                  + " # 客户端命令历史设置(command_history.txt)。仅客户端生成此组。\n"
                  + "\n"
                  + "[client]\n"
                  + " # When executing a command already in command_history.txt, remove all\n"
                  + " # its other records and write the current one as the last line.\n"
                  + " # Off by default.\n"
                  + " # 执行命令时若 command_history.txt 已含该命令,移除其中所有该命令的\n"
                  + " # 其他记录,再把本次命令写入最后一行。默认关。\n"
                  + " CommandHistoryDeduplicate = " + config.client.commandHistoryDeduplicate + "\n"
                  + "\n"
                  + " # Commands that are incorrect (parse errors, selector type mismatch)\n"
                  + " # are removed from command_history.txt when exiting the server or\n"
                  + " # singleplayer world. Entity-not-found is not incorrect and stays.\n"
                  + " # Off by default.\n"
                  + " # 命令不正确(解析错误、选择器类型不匹配)时,退出服务器或单人世界\n"
                  + " # 从 command_history.txt 移除。未找到实体不算不正确,保留。默认关。\n"
                  + " CommandHistoryDropError = " + config.client.commandHistoryDropError + "\n"
                  + "\n"
                  + " # /tell and /say are treated as chat and never saved to command_history.txt.\n"
                  + " # Off by default.\n"
                  + " # 把 /tell 与 /say 视为聊天内容,不保存到 command_history.txt。默认关。\n"
                  + " CommandHistoryDropTalk = " + config.client.commandHistoryDropTalk + "\n"
                  + "\n"
                : "")
            + "[net]\n"
            + " # Use ZSTD instead of the vanilla zlib for network compression.\n"
            + " # The vanilla network-compression-threshold (server.properties) still\n"
            + " # controls when compression is enabled and the packet size threshold.\n"
            + " # Requires the mod on both sides. Default ON: negotiation happens during\n"
            + " # login phase capability exchange — fall back to vanilla zlib if server says no.\n"
            + " # 网络压缩改用 ZSTD(替代原版zlib)。原版\n"
            + " # network-compression-threshold(server.properties)仍控制是否启用压缩\n"
            + " # 及包大小阈值。需要双端安装本模组。默认开启:登录阶段能力交换协商,\n"
            + " # 服务端不支持或无响应时回退到原版zlib。仅mod-to-mod环境使用ZSTD。\n"
            + " useZstd = " + config.net.useZstd + "\n"
            + " # Server-side ZSTD compression level (1-22, default 4).\n"
            + " # 服务端 ZSTD 压缩等级(1-22,默认 4)。\n"
            + " zstdLevel = " + config.net.zstdLevel + "\n"
            + " # Chunk send rate boost: chunks/tick processed by PlayerChunkSender.\n"
            + " # 0 = vanilla. 区块发送速率提升:PlayerChunkSender 每 tick 处理数。0 = 原版。\n"
            + " chunkSendMaxPerTick = " + config.net.chunkSendMaxPerTick + "\n"
            + " # Batch entity position packets into one ClientboundBundlePacket per\n"
            + " # player per tick (cuts the entity-move packet storm ~100x).\n"
            + " # 实体位置包聚合:每玩家每 tick 合并为一个原版 bundle 包(削减\n"
            + " # 实体移动包风暴 ~100 倍)。\n"
            + " entityPacketBatching = " + config.net.entityPacketBatching + "\n"
            + " # UDP transport (mod-to-mod, falls back to TCP).\n"
            + " # UDP 传输(仅 mod-to-mod,不可用回退 TCP)。\n"
            + " useUDP = " + config.net.useUDP + "\n"
            + " # UDP port the server listens on. 0 = use the vanilla server-port (server.properties).\n"
            + " # 服务器 UDP 监听端口。0 = 使用原版 server-port 端口(server.properties)。\n"
            + " udpPort = " + config.net.udpPort + "\n"
            + (com.server_optimize.util.ServerOptimizeEnv.isClient()
                ? ""
                : " # Force UDP/ZSTD even when the client disabled them (server-only, default off).\n"
                + " # 强制 UDP/ZSTD:即使客户端关闭也强制使用(仅服务端,默认关)。\n"
                + " forceUseUDP = " + config.net.forceUseUDP + "\n"
                + " forceUseZSTD = " + config.net.forceUseZSTD + "\n")
            + "\n"
            + "[command]\n"
            + " # Enable /serveroptimize gc command for manual GC invocation.\n"
            + " # 开启 /serveroptimize gc 命令用于手动执行垃圾回收。\n"
            + " commandGC = " + config.command.commandGC + "\n"
            + " # Show network status via /serveroptimize status net.\n"
            + " # 通过 /serveroptimize status net 显示网络状态。\n"
            + " commandNetworkStatus = " + config.command.commandNetworkStatus + "\n"
            + " # Show thread / worker usage via /serveroptimize status thread.\n"
            + " # 通过 /serveroptimize status thread 显示线程/Worker 使用情况。\n"
            + " commandThreadStatus = " + config.command.commandThreadStatus + "\n"
            + " # Shell-style chaining: ';' '&&' '||' between commands. Only\n"
            + " # split when the whole line fails to parse as one vanilla\n"
            + " # command (greedy args keep vanilla behavior); signed chat\n"
            + " # commands are never split. See [command] in code for details.\n"
            + " # 类 shell 命令串联:命令之间支持 ';' '&&' '||'。仅当整行无法\n"
            + " # 作为单条原版命令解析时才拆分(greedy 参数保持原版行为);\n"
            + " # 签名聊天命令从不拆分。\n"
            + " bashSyntax = " + config.command.bashSyntax + "\n"
            + "\n"
            + "[log]\n"
            + " # Log every chunk disk load and save (dimension + pos + size).\n"
            + " # Off by default; per-chunk logging is expensive.\n"
            + " # 记录每个区块的磁盘加载与保存(维度+坐标+大小)。\n"
            + " # 默认关闭;逐块记录开销大。\n"
            + " chunkIO = " + config.log.chunkIO + "\n"
            + "\n"
            + " # Log chunk load/save counts in the 30 s periodic summary line.\n"
            + " # Cheaper than chunkIO and enough to see load/save balance.\n"
            + " # 在 30 s 周期摘要行里记录加载/保存计数。\n"
            + " # 比 chunkIO 便宜,足以看出加载/保存平衡。\n"
            + " chunkSummary = " + config.log.chunkSummary + "\n"
            + "\n"
            + " # Toggle the diag line (heapUsed, culledCache, player count,\n"
            + " # per-dimension map sizes). Off by default.\n"
            + " # 控制诊断行(heapUsed、culledCache、玩家数、维度 map 大小)。\n"
            + " # 默认关闭。\n"
            + " diagInfo = " + config.log.diagInfo + "\n"
            + " # Diag line output interval in ticks (20 = 1 second).\n"
            + " # Default 600 = 30 s. Only used when diagInfo = true.\n"
            + " # 诊断行输出间隔(tick)。20 = 1 秒。默认 600 = 30 秒。\n"
            + " # 仅在 diagInfo = true 时生效。\n"
            + " diagInfoInterval = " + config.log.diagInfoInterval + "\n"
            + "\n"
            + " # Toggle light-refresh throttle log line every 10 s. Off by default.\n"
            + " # 控制每 10s 的光照节流日志行。默认关闭。\n"
            + " lightThrottle = " + config.log.lightThrottle + "\n"
            + "\n"
            + " # Show chunk load rate (chunks/s) in the diag line.\n"
            + " # Requires diagInfo = true. Off by default.\n"
            + " # 在诊断行中显示区块加载速率(区块/s)。\n"
            + " # 需要 diagInfo = true。默认关闭。\n"
            + " chunkLoadRate = " + config.log.chunkLoadRate + "\n"
            + "\n"
            + " # Log UDP transport connection events (bind, probe, migration,\n"
            + " # tunnel install, active). Off by default - routine INFO lines;\n"
            + " # warnings/errors are always logged.\n"
            + " # 记录 UDP 传输连接事件(监听绑定、探测、迁移、隧道安装、激活)。\n"
            + " # 默认关闭——常规 INFO 日志;warn/error 始终记录。\n"
            + " udpConnectionLog = " + config.log.udpConnectionLog + "\n"
            + "\n"
            + " # Log ZSTD compression negotiation events (request, ack, codec swap).\n"
            + " # Off by default - routine INFO lines; warnings/errors are always logged.\n"
            + " # 记录 ZSTD 压缩协商事件(请求、ack、编解码器切换)。\n"
            + " # 默认关闭——常规 INFO 日志;warn/error 始终记录。\n"
            + " zstdConnectionLog = " + config.log.zstdConnectionLog + "\n"
            + "\n"
            + "[compatibility]\n"
            + " # Lithium overlap: verified against Lithium 0.21.4 itself - mixin.collections." + "\\n"
            + " # entity_ticking targets EntityTickList and mixin.alloc.explosion_behavior only" + "\\n"
            + " # removes the calculator's Optional boxing, so nothing here duplicates Lithium;" + "\\n"
            + " # mixin.world.explosions (block/entity ray cast) stays the faster path anyway." + "\\n"
            + " # 与 Lithium 无功能重复(经反编译核实),不做任何禁用。" + "\\n"
            + "\n"
            + "[thread]\n"
            + " # Scheduling-related settings live in the sub-group [thread.schedule].\n"
            + " # 线程调度相关设置都在子组 [thread.schedule] 中。\n"
            + " [thread.schedule]\n"
            + " # Thread affinity management. DEFAULT OFF: pinning overrides the\n"
            + " # OS scheduler - enable only on a machine dedicated to Minecraft.\n"
            + " # On a memory-bandwidth-bottlenecked machine pinning cannot create\n"
            + " # bandwidth; it only improves cache locality / reduces migration.\n"
            + " # Windows: SetThreadAffinityMask via JNA (single processor group);\n"
            + " # Linux: sched_setaffinity; unsupported platforms disable themselves.\n"
            + " # 线程亲和性管理。默认关闭:绑定会覆盖操作系统调度器,仅当机器\n"
            + " # 专用于 Minecraft 时开启。内存带宽瓶颈的机器上,绑定不能创造\n"
            + " # 带宽,只改善缓存局部性/减少迁移。Windows 经 JNA 调用\n"
            + " # SetThreadAffinityMask(单处理器组);Linux 用 sched_setaffinity;\n"
            + " # 不支持的平台自动禁用。\n"
            + " EnableThreadScheduler = " + config.thread.enabled + "\n"
            + " # Pin the server thread (\"Server thread\") to one dedicated core.\n"
            + " # 将服务端主线程锁定到独立核心。\n"
            + " pinServerThread = " + config.thread.pinServerThread + "\n"
            + " # Pin the client render thread (\"Render thread\") to one dedicated\n"
            + " # core (client only). The server and render threads are never put\n"
            + " # on the two hardware threads of the same physical core.\n"
            + " # 将客户端渲染线程锁定到独立核心(仅客户端)。服务端与渲染线程\n"
            + " # 绝不会占用同一物理核心的两个超线程。\n"
            + " pinRenderThread = " + config.thread.pinRenderThread + "\n"
            + " # After pinning, thread priorities are set as\n"
            + " # Render Thread > Server Thread > Worker Thread\n"
            + " # (10 / 8 / 4 on the Java 1..10 scale), so the client frame\n"
            + " # pacing always beats the integrated server tick loop, and\n"
            + " # chunk gen/save/load workers never steal either.\n"
            + " # 绑定核心后设置线程优先级:\n"
            + " # 渲染线程 > 服务端线程 > Worker 线程(Java 1..10 刻度上的\n"
            + " # 10 / 8 / 4),保证客户端帧节奏始终优先于集成服 tick 循环,\n"
            + " # 区块生成/保存/加载的 worker 也不会抢占两者。\n"
            + " # Rebuild the vanilla worker pool with per-worker core pinning.\n"
            + " # Worker COUNT already adapts to the process CPU affinity\n"
            + " # (availableProcessors); this additionally pins the worker\n"
            + " # threads. Uses reflection on Util.BACKGROUND_EXECUTOR at server\n"
            + " # start - keep off unless actually needed.\n"
            + " # 重建原版 worker 池并对每个 worker 线程绑定核心。worker 数量\n"
            + " # 本就随进程 CPU 亲和性自适应(availableProcessors);开启后额外\n"
            + " # 绑定 worker 线程本身。通过反射在服务端启动时替换\n"
            + " # Util.BACKGROUND_EXECUTOR——非必要请保持关闭。\n"
            + " pinWorkers = " + config.thread.pinWorkers + "\n"
            + " # Logical CPU for the server thread. -1 = auto (first free core,\n"
            + " # coordinated across instances). 服务端线程逻辑核。-1 = 自动。\n"
            + " serverThreadCore = " + config.thread.serverThreadCore + "\n"
            + " # Logical CPU for the client render thread. -1 = auto.\n"
            + " # 客户端渲染线程逻辑核。-1 = 自动。\n"
            + " renderThreadCore = " + config.thread.renderThreadCore + "\n"
            + " # Coordinate core allocation across Minecraft instances on this\n"
            + " # machine via a locked ledger file (instances must share the same\n"
            + " # config/ directory). 在同一台机器的多个实例间通过加锁台账文件\n"
            + " # 协调核心分配(各实例需共享同一 config/ 目录)。\n"
            + " coordinateMultiInstance = " + config.thread.coordinateMultiInstance + "\n"
            + " # Client only: when connected to a REMOTE server, the mod does not\n"
            + " # start its own background workers / pool rebuild, leaving the CPU\n"
            + " # to the server. Rendering-needed vanilla client threads are never\n"
            + " # touched. 仅客户端:连接远程服务器时,本模组不启动自己的后台\n"
            + " # worker/不重建线程池,把 CPU 留给服务器。渲染所需的原版客户端\n"
            + " # 线程绝不触碰。\n"
            + " disableWorkersWhenConnected = " + config.thread.disableWorkersWhenConnected + "\n"
            + "\n"
            + " [thread.multithread]\n"
            + " # Parallel thread count for the worker pool this mod uses (the Minecraft\n"
            + " # worker pool we rebuild - no workers of our own are created).\n"
            + " #   0 = follow EnableThreadScheduler: allowed CPU set (process affinity\n"
            + " #       intersected with CPU sets) minus the cores dedicated to the\n"
            + " #       server/render threads.\n"
            + " #  > 0 = cap it further; can never exceed the allowed set, and the threads\n"
            + " #       stay pinned by pinWorkers.\n"
            + " # 并行线程数:0 = 跟随 EnableThreadScheduler(可用 CPU 集合 - 专用核);\n"
            + " # >0 只在其上再收窄,永不超过可用集合;线程仍由 pinWorkers 绑核。\n"
            + " parallelThreads = " + config.thread.parallelThreads + "\n"
            + "\n"
            + " # Section-parallel random ticking. Only the read-only block-state sampling" + "\n"
            + " # moves to workers; sample positions are drawn on the server thread in" + "\n"
            + " # vanilla order and randomTick stays serial, so the RNG sequence and the" + "\n"
            + " # world mutations are unchanged. Workers = the common pool sized from the" + "\n"
            + " # allowed CPU set. /serveroptimize status thread count reports the width." + "\n"
            + " # 随机刻 Section 级并行:仅只读取态并行,抽样与 randomTick 仍串行,语义不变。" + "\n"
            + " randomTickParallel = " + config.thread.randomTickParallel + "\n"
            + "\n"
            + " # A random-tick pass shorter than this, in milliseconds, runs serial - it is\n"
            + " # not worth waking the pool for. 0 keeps it always parallel.\n"
            + " # 随机刻 pass 小于该毫秒数时串行执行;0 = 始终并行。\n"
            + " randomTickParallelThresholdMs = " + config.thread.randomTickParallelThresholdMs + "\n"
            + "\n"
            + " # Experimental: entity-tick read-side parallelism (pathfinding, target\n"
            + " # scanning, collision pre-checks on the worker pool). Off until the first\n"
            + " # slice lands; see TestResult/实体多线程-设计方案.md.\n"
            + " # 实验性:实体 tick 读侧并行。默认关。\n"
            + " entityTickParallel = " + config.thread.entityTickParallel + "\n"
            + "\n"
            + "   [thread.multithread.regionbased]\n"
            + " # 区域多线程总开关:单维度内按区域并行 tick。默认关,引擎按切片逐步\n"
            + " # 实现(见 TestResult/实体多线程-设计方案.md)。\n"
            + " # Master gate for region-based multithreaded ticking within a dimension.\n"
            + " # Off by default; the engine is built slice by slice.\n"
            + " EnableRegionBasedMultithreadTicking = " + config.thread.regionbased.enableRegionBasedMultithreadTicking + "\n"
            + "\n"
            + " # 是否使用字节码补丁(字段虚拟化 agent)来重定向 vanilla 与模组对 Level\n"
            + " # 内部结构的字段访问。实验性:agent 尚未实现,默认关。\n"
            + " # Use bytecode patching (a load-time field-virtualization agent) to redirect\n"
            + " # the field access of vanilla and mod classes to per-region storage.\n"
            + " # Experimental, the agent is not implemented yet, off by default.\n"
            + " useBytecodePatch = " + config.thread.regionbased.useBytecodePatch + "\n"
            + "\n"
            + " # Worker load balancing: the pool is a ForkJoinPool, so idle workers steal\n"
            + " # queued work by construction; this reports queued tasks and the active /\n"
            + " # parked worker counts so an imbalance is visible instead of silent.\n"
            + " # Worker 负载均衡:工作窃取由池自带;此项输出队列深度与活跃/停靠线程数。\n"
            + " autoBalance = " + config.thread.autoBalance + "\n"
            + "\n"
            + " # Time and count worker-side phases, so a stall stays traceable after the\n"
            + " # work has left the server thread (chunk read / chunk save / packet build).\n"
            + " # Worker 阶段计时:离开主线程后的卡顿源仍可追踪。\n" + "\n";
    }
}
