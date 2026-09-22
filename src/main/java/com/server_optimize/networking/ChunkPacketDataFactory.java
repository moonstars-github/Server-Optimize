package com.server_optimize.networking;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Allocates a ClientboundLevelChunkPacketData without running any vanilla
 * constructor, so the client can feed the rebuilt chunk buffer into the
 * vanilla updateLevelChunk path. Fields are set via reflection (instance
 * fields, allowed on JDK 21 after setAccessible).
 */
public final class ChunkPacketDataFactory {

    private static final sun.misc.Unsafe UNSAFE = unsafe();

    private ChunkPacketDataFactory() {
    }

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String name, String altName, Object value) throws ReflectiveOperationException {
        Field f = null;
        try {
            f = ClientboundLevelChunkPacketData.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = ClientboundLevelChunkPacketData.class.getDeclaredField(altName);
        }
        f.setAccessible(true);
        f.set(target, value);
    }

    public static ClientboundLevelChunkPacketData create(Map<?, ?> heightmaps, byte[] buffer, List<?> blockEntities) {
        try {
            ClientboundLevelChunkPacketData d =
                (ClientboundLevelChunkPacketData) UNSAFE.allocateInstance(ClientboundLevelChunkPacketData.class);
            setField(d, "heightmaps", "field_34863", heightmaps);
            setField(d, "buffer", "field_34864", buffer);
            setField(d, "blockEntitiesData", "field_34865", blockEntities);
            return d;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create chunk packet data", e);
        }
    }
}
