package com.server_optimize.networking;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Section culling (chunk.sectionCulling).
 *
 * The vanilla chunk-with-light packet carries every section (128 sections at
 * 2032 height). Sections the player cannot see are dropped on the wire:
 *   - air sections (client fills air anyway)
 *   - sections fully below the occlusion heightmap (hidden underground /
 *     inside mountains); the client renders them as air, exactly like
 *     not-yet-loaded chunks, until a resend covers them.
 * Sections containing block entities are always kept.
 *
 * The payload carries a section mask, the kept sections' serialized bytes,
 * the vanilla heightmaps, filtered block entities and the light data. The
 * client rebuilds the full chunk buffer (missing sections = air bytes) and
 * dispatches through the vanilla handler, so no rendering code is touched.
 */
public final class SectionCulling {
    private static final Logger LOGGER = LoggerFactory.getLogger("server-optimize");

    /** Current server instance (set by ServerLifecycleEvents in ServerOptimize). */
    public static volatile net.minecraft.server.MinecraftServer SERVER;

    public static final Identifier CHUNK_PAYLOAD_ID = Identifier.fromNamespaceAndPath("server-optimize", "culled_chunk");
    public static final CustomPacketPayload.Type<CulledChunkPayload> PAYLOAD_TYPE =
        new CustomPacketPayload.Type<>(CHUNK_PAYLOAD_ID);

    private static final Set<ServerPlayer> SUPPORTED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Long, CulledChunkPayload> CACHE = new ConcurrentHashMap<>();

    /** High-speed per-player velocity window tracker. */
    private record HighSpeedTracker(double speed, int consecutiveTicks) {}

    /** Player -> current velocity window state. */
    private static final Map<ServerPlayer, HighSpeedTracker> HIGH_SPEED_TRACKERS = new ConcurrentHashMap<>();

    /** High speed threshold (m/s). 默认 64 m/s。 */
    public static double getHighSpeedThreshold() {
        var cfg = com.server_optimize.config.ModConfig.INSTANCE;
        return cfg != null ? cfg.chunk.sectionCullingHighSpeedThreshold : 64.0;
    }

    /** Consecutive ticks above the high speed threshold before the window opens. 默认 40 tick。 */
    public static int getHighSpeedTicks() {
        var cfg = com.server_optimize.config.ModConfig.INSTANCE;
        return cfg != null ? cfg.chunk.sectionCullingHighSpeedTicks : 40;
    }

    /**
     * Per-tick update of one player's high-speed window. Horizontal speed
     * (sqrt(vx^2+vz^2)) must stay >= threshold for getHighSpeedTicks()
     * consecutive ticks to open the window; any tick below closes it.
     * 每 tick 更新单个玩家的高速窗口。水平速度 sqrt(vx^2+vz^2) 连续
     * getHighSpeedTicks() 个 tick >= 阈值才进入窗口;任一 tick 低于阈值即关闭。
     */
    public static void updateHighSpeedTracker(ServerPlayer player) {
        if (!SUPPORTED.contains(player)) {
            HIGH_SPEED_TRACKERS.remove(player);
            return;
        }
        double threshold = getHighSpeedThreshold();
        int ticks = getHighSpeedTicks();
        if (threshold <= 0) {
            HIGH_SPEED_TRACKERS.remove(player);
            return;
        }
        var dm = player.getDeltaMovement();
        double velocity = Math.sqrt(dm.x() * dm.x() + dm.z() * dm.z());
        HighSpeedTracker tracker = HIGH_SPEED_TRACKERS.get(player);
        if (tracker != null) {
            int consecutive = velocity >= threshold ? tracker.consecutiveTicks() + 1 : 0;
            if (consecutive >= ticks) {
                HIGH_SPEED_TRACKERS.put(player, new HighSpeedTracker(velocity, ticks));
            } else if (consecutive > 0) {
                HIGH_SPEED_TRACKERS.put(player, new HighSpeedTracker(velocity, consecutive));
            } else {
                HIGH_SPEED_TRACKERS.remove(player);
            }
        } else if (velocity >= threshold) {
            HIGH_SPEED_TRACKERS.put(player, new HighSpeedTracker(velocity, 1));
        }
    }

    /** Update every supported player once per tick. Called from MinecraftServer.tickChildren. */
    public static void updateAllHighSpeedTrackers(net.minecraft.server.MinecraftServer server) {
        try {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            for (int i = 0; i < players.size(); i++) {
                updateHighSpeedTracker(players.get(i));
            }
        } catch (Throwable t) {
            // Best-effort — player list may change during iteration.
        }
    }

    /**
     * Whether any supported player inside the vanilla 2-chunk entity tracking
     * range of (chunkX, chunkZ) is inside its high-speed window. Empty window
     * (nobody fast) short-circuits to false.
     * 该区块 2-chunk 实体追踪范围内是否有支持的玩家处于高速窗口。窗口为空
     * (无人高速)时直接短路为 false。
     */
    private static boolean isNearbyPlayerHighSpeed(net.minecraft.server.level.ServerLevel level, int chunkX, int chunkZ) {
        double threshold = getHighSpeedThreshold();
        int ticks = getHighSpeedTicks();
        if (threshold <= 0 || ticks <= 0 || HIGH_SPEED_TRACKERS.isEmpty()) {
            return false;
        }
        try {
            for (ServerPlayer sp : level.getPlayers(p -> true)) {
                if (!SUPPORTED.contains(sp)) {
                    continue;
                }
                HighSpeedTracker t = HIGH_SPEED_TRACKERS.get(sp);
                if (t == null || t.consecutiveTicks() < ticks) {
                    continue;
                }
                int pcx = Math.floorDiv(net.minecraft.util.Mth.floor(sp.getX()), 16);
                int pcz = Math.floorDiv(net.minecraft.util.Mth.floor(sp.getZ()), 16);
                if (Math.abs(pcx - chunkX) <= 2 && Math.abs(pcz - chunkZ) <= 2) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /**
     * True when every MOTION_BLOCKING column surface >= sectionTopY (the top
     * face is fully covered by blocks above, i.e. hidden behind other
     * sections). Used as the high-speed culling extension: occluded() only
     * drops sections fully below the surface (surface > top), while this
     * also drops sections whose top is flush-cap-hidden.
     * 所有 MOTION_BLOCKING 列 surface >= sectionTopY 时为 true(顶面被上方
     * 方块完全覆盖,即被其它 Section 挡住)。高速剔除扩展用:occluded() 只
     * 剔除完全低于地表(surface > top)的 section,此判定额外剔除顶面齐平
     * 封住的 section。
     */
    private static boolean sectionTopCovered(Heightmap occlusion, int sectionTopY) {
        if (occlusion == null) {
            return false;
        }
        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int surface = (int) HEIGHTMAP_GET.invoke(occlusion, x, z);
                    if (surface < sectionTopY) {
                        return false;
                    }
                }
            }
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Field FIELD_SECTIONS;
    private static Method SECTION_WRITE;
    private static Method CHUNK_HEIGHTMAPS;
    private static Method HEIGHTMAP_GET;
    private static Object BE_LIST_CODEC;          // BlockEntityInfo list stream codec
    private SectionCulling() {
    }

    public record CulledChunkPayload(
        int x, int z,
        long[] mask,
        int[] ends,
        byte[] culled,
        byte[] airSection,
        byte[] heightmaps,
        byte[] blockEntities,
        byte[] light
    ) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PAYLOAD_TYPE;
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, CulledChunkPayload> CODEC = new StreamCodec<>() {
        @Override
        public CulledChunkPayload decode(RegistryFriendlyByteBuf buf) {
            int x = buf.readInt();
            int z = buf.readInt();
            int maskWords = buf.readVarInt();
            long[] mask = new long[maskWords];
            for (int i = 0; i < maskWords; i++) {
                mask[i] = buf.readLong();
            }
            int segCount = buf.readVarInt();
            int[] ends = new int[segCount];
            for (int i = 0; i < segCount; i++) {
                ends[i] = buf.readVarInt();
            }
            byte[] culled = readBytes(buf);
            byte[] air = readBytes(buf);
            byte[] heightmaps = readBytes(buf);
            byte[] blockEntities = readBytes(buf);
            byte[] light = readBytes(buf);
            return new CulledChunkPayload(x, z, mask, ends, culled, air, heightmaps, blockEntities, light);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, CulledChunkPayload value) {
            buf.writeInt(value.x());
            buf.writeInt(value.z());
            buf.writeVarInt(value.mask().length);
            for (long m : value.mask()) {
                buf.writeLong(m);
            }
            buf.writeVarInt(value.ends().length);
            for (int e : value.ends()) {
                buf.writeVarInt(e);
            }
            buf.writeVarInt(value.culled().length);
            buf.writeBytes(value.culled());
            writeBytes(buf, value.airSection());
            writeBytes(buf, value.heightmaps());
            writeBytes(buf, value.blockEntities());
            writeBytes(buf, value.light());
        }

        private static byte[] readBytes(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            byte[] d = new byte[n];
            buf.readBytes(d);
            return d;
        }

        private static void writeBytes(FriendlyByteBuf buf, byte[] d) {
            buf.writeVarInt(d.length);
            buf.writeBytes(d);
        }
    };

    public static void register() {
        PayloadTypeRegistry.playS2C().register(PAYLOAD_TYPE, CODEC);
        try {
            // 1.21 Mojang mapping: getHeightmaps() lives on ChunkAccess (parent), not LevelChunk.
            CHUNK_HEIGHTMAPS = findMethodNoArgs(LevelChunk.class, "method_12011", "getHeightmaps");
        } catch (NoSuchMethodException ignored) {
            try {
                // Fallback to parent class where the method actually resides.
                CHUNK_HEIGHTMAPS = LevelChunk.class.getSuperclass().getMethod("getHeightmaps");
                CHUNK_HEIGHTMAPS.setAccessible(true);
            } catch (ReflectiveOperationException e2) {
                LOGGER.warn("Heightmap accessor unavailable; occlusion culling disabled");
            }
        }
        try {
            HEIGHTMAP_GET = findMethod(Heightmap.class, "getFirstAvailable", "getFirstAvailable", int.class, int.class);
        } catch (NoSuchMethodException e) {
            LOGGER.warn("Heightmap getter unavailable; occlusion culling disabled");
        }
        try {
            Class<?> bei = Class.forName("net.minecraft.class_6603$class_6604");
            Field codecField = findField(bei, "field_47932", "STREAM_CODEC");
            codecField.setAccessible(true);
            BE_LIST_CODEC = codecField.get(null);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to init block-entity codec; section culling disabled", e);
        }
    }

    /**
     * The section array field: vanilla 1.21.11 uses "field_34545" (or yarn
     * "sections"), but mods like ServerCore replace the LevelChunk layout,
     * so fall back to scanning for the field whose type is
     * LevelChunkSection[].
     */
    private static Field findSectionsField(Class<?> c) throws NoSuchFieldException {
        for (String n : new String[]{"field_34545", "sections"}) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                if (f.getType() == LevelChunkSection[].class) {
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == LevelChunkSection[].class) {
                f.setAccessible(true);
                return f;
            }
        }
        throw new NoSuchFieldException("no LevelChunkSection[] field in " + c.getName());
    }

    private static Field findField(Class<?> c, String... names) throws NoSuchFieldException {
        for (String n : names) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException("none of " + String.join(",", names) + " in " + c.getName());
    }

    private static Method findMethodNoArgs(Class<?> c, String... names) throws NoSuchMethodException {
        for (String n : names) {
            try {
                Method m = c.getDeclaredMethod(n);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("none of " + String.join(",", names) + " in " + c.getName());
    }

    private static Method findMethod(Class<?> c, String n1, String n2, Class<?>... params) throws NoSuchMethodException {
        for (String n : new String[]{n1, n2}) {
            try {
                Method m = c.getDeclaredMethod(n, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("none of " + n1 + "," + n2 + " in " + c.getName());
    }

    public static void markSupported(ServerPlayer player) {
        SUPPORTED.add(player);
    }

    public static void removePlayer(ServerPlayer player) {
        SUPPORTED.remove(player);
    }

    public static boolean isSupported(ServerPlayer player) {
        return SUPPORTED.contains(player);
    }

    // Transfer cache between the async packet builder (worker) and the send
    // path (server thread). A payload only needs to live for the build->send
    // window; capping the map keeps a flying player (thousands of chunks)
    // from pinning every payload forever. On eviction the send falls back to
    // the vanilla packet - harmless, just unoptimized.
    private static final int CACHE_MAX = 512;
    public static CulledChunkPayload getCached(int x, int z) {
        return CACHE.get(ChunkPos.asLong(x, z));
    }

    public static void invalidate(int x, int z) {
        CACHE.remove(ChunkPos.asLong(x, z));
    }

    public static int cacheSize() {
        return CACHE.size();
    }

    /** Server side: build the culled payload at packet construction time. */
    @SuppressWarnings("unchecked")
    public static void onChunkPacketBuilt(ClientboundLevelChunkWithLightPacket packet, LevelChunk chunk) {
        if (!com.server_optimize.config.ModConfig.enabled()) {
            return;
        }
        try {
            ClientboundLevelChunkPacketData data = packet.getChunkData();
            FriendlyByteBuf in = data.getReadBuffer();
            int count = chunk.getHeight() >> 4;

            // Parse the vanilla buffer: each section = short(nonEmpty) + 2 palette containers.
            int[] starts = new int[count];
            int[] nonEmpty = new int[count];
            @SuppressWarnings({"unchecked", "rawtypes"})
            net.minecraft.core.Registry biomeReg = chunk.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
            net.minecraft.core.IdMap<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomeIdMap = biomeReg.asHolderIdMap();
            net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomeTmp =
                new net.minecraft.world.level.chunk.PalettedContainer<>(
                    biomeIdMap.byId(0),
                    (net.minecraft.world.level.chunk.Strategy<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>) (net.minecraft.world.level.chunk.Strategy<?>) net.minecraft.world.level.chunk.Strategy.createForBiomes(biomeIdMap));
            for (int i = 0; i < count; i++) {
                starts[i] = in.readerIndex();
                nonEmpty[i] = in.readShort();
                try {
                    // Skip via the vanilla reader: zero format assumptions.
                    BLOCK_TMP.get().read(in);
                    biomeTmp.read(in);
                } catch (RuntimeException e) {
                    LOGGER.error("CULL-DIAG: section {} of chunk ({},{}) misparse at readerIndex {} of {}, nonEmpty={}, err={}",
                        i, packet.getX(), packet.getZ(), in.readerIndex(), starts[0] + in.readableBytes(), nonEmpty[i], e.toString());
                    throw e;
                }
            }
            int end = in.readerIndex();

            boolean[] hasBlockEntity = new boolean[count];
            int minY = chunk.getMinY();
            for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                int idx = (pos.getY() - minY) >> 4;
                if (idx >= 0 && idx < count) {
                    hasBlockEntity[idx] = true;
                }
            }

            Heightmap occlusion = null;
            if (CHUNK_HEIGHTMAPS != null) {
                for (Map.Entry<Heightmap.Types, Heightmap> e : (Iterable<Map.Entry<Heightmap.Types, Heightmap>>) CHUNK_HEIGHTMAPS.invoke(chunk)) {
                    if (e.getKey() == Heightmap.Types.MOTION_BLOCKING) {
                        occlusion = e.getValue();
                        break;
                    }
                }
            }

            long[] mask = new long[(count + 63) / 64];
            int[] ends = new int[count];
            int culledSize = 0;
            // High-speed window active for a player near this chunk? When yes,
            // additionally drop sections whose top face is fully covered by the
            // heightmap (hidden behind other sections).
            boolean highSpeedActive = chunk.getLevel() instanceof net.minecraft.server.level.ServerLevel sl
                && isNearbyPlayerHighSpeed(sl, packet.getX(), packet.getZ());
            for (int i = 0; i < count; i++) {
                boolean keep;
                if (nonEmpty[i] == 0) {
                    keep = false;                       // air section
                } else if (hasBlockEntity[i]) {
                    keep = true;
                } else if (occluded(occlusion, minY + i * 16 + 15)) {
                    keep = false;                       // hidden below surface
                } else if (highSpeedActive && sectionTopCovered(occlusion, minY + i * 16 + 15)) {
                    keep = false;                       // fast window: top face cap-hidden
                } else {
                    keep = true;
                }
                if (keep) {
                    mask[i >> 6] |= 1L << (i & 63);
                    ends[i] = starts[i] - starts[0];
                }
                culledSize += (i + 1 < count ? starts[i + 1] : end) - starts[i];
            }
            // Rebuild culled buffer: kept sections' bytes in order.
            FriendlyByteBuf in2 = data.getReadBuffer();
            boolean reuse = com.server_optimize.config.ModConfig.INSTANCE != null
                && com.server_optimize.config.ModConfig.INSTANCE.chunk.cullingBufferReuse;
            // The body copy is pure scratch: with reuse on it comes from a
            // grow-only per-thread buffer instead of a fresh array per packet
            // (the payload arrays handed to CulledChunkPayload are shipped to
            // the client and therefore cannot be pooled).
            byte[] raw;
            int rawLength = in2.readableBytes();
            if (reuse) {
                raw = TMP_RAW.get();
                if (raw.length < rawLength) {
                    raw = new byte[Math.max(rawLength, raw.length * 2)];
                    TMP_RAW.set(raw);
                }
            } else {
                raw = new byte[rawLength];
            }
            in2.readBytes(raw, 0, rawLength);
            ByteBuf bb = reuse ? TMP_CULLED.get() : Unpooled.buffer(culledSize);
            bb.clear();
            for (int i = 0; i < count; i++) {
                if ((mask[i >> 6] & (1L << (i & 63))) != 0) {
                    int s = starts[i] - starts[0];
                    int e2 = (i + 1 < count ? starts[i + 1] : end) - starts[0];
                    bb.writeBytes(raw, s, e2 - s);
                    ends[i] = bb.writerIndex();
                }
            }
            byte[] culled = toBytes(bb);

            // air section bytes: first air section slice (all identical)
            byte[] airBytes = null;
            for (int i = 0; i < count; i++) {
                if (nonEmpty[i] == 0) {
                    int s = starts[i] - starts[0];
                    int e2 = (i + 1 < count ? starts[i + 1] : end) - starts[0];
                    airBytes = sharedAirBytes(java.util.Arrays.copyOfRange(raw, s, e2));
                    break;
                }
            }

            // meta buffers share one reusable buffer (reuse=false falls back to fresh ones)
            ByteBuf meta = reuse ? TMP_META.get() : Unpooled.buffer(1024);
            FriendlyByteBuf hmBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                meta, chunk.getLevel().registryAccess());
            meta.clear();
            Object hmCodec = heightmapsCodec();
            ((net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Map<Heightmap.Types, long[]>>) hmCodec)
                .encode((net.minecraft.network.RegistryFriendlyByteBuf) hmBuf, data.getHeightmaps());
            byte[] hmBytes = toBytes(hmBuf);

            Field beField = findField(ClientboundLevelChunkPacketData.class, "blockEntitiesData", "field_34865");
            List<Object> beList = (List<Object>) beField.get(data);
            meta.clear();
            FriendlyByteBuf beBuf = new net.minecraft.network.RegistryFriendlyByteBuf(meta, chunk.getLevel().registryAccess());
            ((net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, List<Object>>) BE_LIST_CODEC)
                .encode((net.minecraft.network.RegistryFriendlyByteBuf) beBuf, beList);
            byte[] beBytes = toBytes(beBuf);

            byte[] lightBytes = null;
            Object light = packet.getLightData();
            if (light != null) {
                meta.clear();
                FriendlyByteBuf lb = new FriendlyByteBuf(meta);
                Method lightWrite = findMethod(light.getClass(), "method_38603", "write", FriendlyByteBuf.class);
                lightWrite.invoke(light, lb);
                lightBytes = toBytes(lb);
            }

            CulledChunkPayload payload = new CulledChunkPayload(
                packet.getX(), packet.getZ(), mask, ends, culled,
                airBytes != null ? airBytes : new byte[0],
                hmBytes, beBytes, lightBytes != null ? lightBytes : new byte[0]);
            if (CACHE.size() >= CACHE_MAX) {
                CACHE.clear();
            }
            CACHE.put(ChunkPos.asLong(packet.getX(), packet.getZ()), payload);
        } catch (Exception e) {
            LOGGER.error("Failed to build culled chunk at ({},{})", packet.getX(), packet.getZ(), e);
        }
    }

    /**
     * Skips one PalettedContainer in the vanilla chunk-buffer format.
     * Layout (verified from 1.21.11 bytecode):
     *   byte(bitCount) [= SimpleBitStorage bits; 0 = SINGLE palette]
     *   SINGLE (0):       1 varInt entry + long array (empty)
     *   LINEAR/HASHMAP (1-8): varInt(size) + size varInt entries + long array
     *   GLOBAL (9+):      no palette entries + long array
     *   long array:       varInt(long count) + count*8 bytes
     */

    /** Diagnostic: dump one section's recorded start bytes with its parsed bits. */

    /** Diagnostic: dump the whole chunk buffer to a file for offline format analysis. */


    private static boolean occluded(Heightmap occlusion, int sectionTopY) {
        if (occlusion == null) {
            return false;
        }
        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int surface = (int) HEIGHTMAP_GET.invoke(occlusion, x, z);
                    if (surface <= sectionTopY) {
                        return false;
                    }
                }
            }
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Object heightmapsCodec() throws ReflectiveOperationException {
        try {
            Field f = ClientboundLevelChunkPacketData.class.getDeclaredField("HEIGHTMAPS_STREAM_CODEC");
            f.setAccessible(true);
            return f.get(null);
        } catch (NoSuchFieldException e) {
            // runtime (intermediary) name
            Field f = ClientboundLevelChunkPacketData.class.getDeclaredField("field_56601");
            f.setAccessible(true);
            return f.get(null);
        }
    }

    /** Client-side registry access for RegistryFriendlyByteBuf codecs. */
    private static net.minecraft.core.RegistryAccess registryAccessOf(net.minecraft.client.multiplayer.ClientPacketListener listener) {
        try {
            java.lang.reflect.Method m = listener.getClass().getMethod("getLevel");
            m.setAccessible(true);
            Object level = m.invoke(listener);
            if (level instanceof net.minecraft.world.level.Level l) {
                return l.registryAccess();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level != null) {
                return mc.level.registryAccess();
            }
        } catch (Throwable ignored) {
        }
        return net.minecraft.core.RegistryAccess.EMPTY;
    }

    /** Reusable vanilla reader (per thread) for skipping block palette containers. */
    private static final ThreadLocal<net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.world.level.block.state.BlockState>> BLOCK_TMP =
        ThreadLocal.withInitial(() -> new net.minecraft.world.level.chunk.PalettedContainer<>(
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
            net.minecraft.world.level.chunk.Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY)));

    /** Reusable build buffers (per thread), controlled by client.cullingBufferReuse. */
    private static final ThreadLocal<ByteBuf> TMP_CULLED = ThreadLocal.withInitial(() -> Unpooled.buffer(4096));
    private static final ThreadLocal<ByteBuf> TMP_META = ThreadLocal.withInitial(() -> Unpooled.buffer(1024));

    /** Grow-only scratch for the chunk body copy (never escapes this method). */
    private static final ThreadLocal<byte[]> TMP_RAW = ThreadLocal.withInitial(() -> new byte[8192]);

    /**
     * All air sections carry identical bytes, so every payload can share one
     * immutable instance per size instead of copying a fresh array per packet.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, byte[]> AIR_BYTES =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static byte[] sharedAirBytes(byte[] candidate) {
        if (candidate.length == 0) {
            return candidate;
        }
        byte[] existing = AIR_BYTES.get(candidate.length);
        if (existing != null) {
            return existing;
        }
        byte[] previous = AIR_BYTES.putIfAbsent(candidate.length, candidate);
        return previous != null ? previous : candidate;
    }

    private static byte[] toBytes(ByteBuf bb) {
        byte[] out = new byte[bb.writerIndex()];
        bb.getBytes(0, out);
        return out;
    }

    private static byte[] toBytes(FriendlyByteBuf buf) {
        ByteBuf bb = buf;
        return toBytes(bb);
    }

    /** Client side: rebuild full chunk buffer and dispatch through vanilla handler. */
    @SuppressWarnings("unchecked")
    public static void dispatchCulled(CulledChunkPayload payload, net.minecraft.client.multiplayer.ClientPacketListener listener) {
        try {
            int count = payload.mask().length * 64;
            int ptr = 0;
            byte[] culled = payload.culled();
            ByteBuf full = Unpooled.buffer(8192);
            FriendlyByteBuf out = new FriendlyByteBuf(full);
            for (int i = 0; i < count; i++) {
                boolean keep = (payload.mask()[i >> 6] & (1L << (i & 63))) != 0;
                if (keep) {
                    out.writeBytes(culled, ptr, payload.ends()[i] - ptr);
                    ptr = payload.ends()[i];
                } else {
                    out.writeBytes(payload.airSection());
                }
            }

            Map<Heightmap.Types, long[]> heightmaps = ((net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Map<Heightmap.Types, long[]>>) heightmapsCodec())
                .decode(new net.minecraft.network.RegistryFriendlyByteBuf(
                    Unpooled.wrappedBuffer(payload.heightmaps()), registryAccessOf(listener)));
            FriendlyByteBuf beBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(payload.blockEntities()), registryAccessOf(listener));
            List<Object> blockEntities = ((net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, List<Object>>) BE_LIST_CODEC).decode((net.minecraft.network.RegistryFriendlyByteBuf) beBuf);

            ClientboundLevelChunkPacketData data = ChunkPacketDataFactory.create(heightmaps, toBytes(full), blockEntities);
            Method update = findMethod(listener.getClass(), "method_38539", "updateLevelChunk", int.class, int.class, ClientboundLevelChunkPacketData.class);
            update.invoke(listener, payload.x(), payload.z(), data);

            if (payload.light().length > 0) {
                FriendlyByteBuf lb = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload.light()));
                Class<?> lpd = Class.forName("net.minecraft.class_6606");
                Object lightData = lpd.getConstructor(FriendlyByteBuf.class, int.class, int.class)
                    .newInstance(lb, payload.x(), payload.z());
                Method apply = findMethod(listener.getClass(), "method_38543", "applyLightData", int.class, int.class, lpd, boolean.class);
                apply.invoke(listener, payload.x(), payload.z(), lightData, false);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to dispatch culled chunk at ({},{})", payload.x(), payload.z(), e);
        }
    }
}