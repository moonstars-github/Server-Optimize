package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.config.ModConfig;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/**
 * O(1) memo for "is this chunk entity-ticking" (chunk.ticking.entityTickingMemo).
 * <p>
 * The 4.3/5 profiles put the answer path at the top of the CPU list
 * ({@code DistanceManager.inEntityTickingRange -> SimulationChunkTracker.getLevel ->
 * Long2ByteOpenHashMap.get}, 8.84% in the 5 run): the query runs once per TRACKED
 * ENTITY per tick, and entities cluster in chunks, so the same chunk position is
 * asked many times within a tick.
 * <p>
 * This differs fundamentally from the entity-ticking cache that was removed
 * earlier: that one rebuilt a set of every entity-ticking chunk once per tick
 * (iterating the whole simulation-level map plus allocating a LongOpenHashSet,
 * ~12% of all allocation for no benefit). Here nothing is precomputed, nothing is
 * allocated, and the vanilla answer is simply remembered for the chunk positions
 * that were actually asked about, in a small direct-mapped table (32 slots, key +
 * packed result). The table is dropped whenever the distance manager recomputes
 * ticket levels, so a stored answer can never be staler than vanilla's own map.
 * <p>
 * Called only from the server thread (ChunkMap.tick and the ticket system).
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerEntityTickingMemoMixin {

    @Unique
    private static final int SERVER_OPTIMIZE$SLOTS = 32;

    @Unique
    private static final long[] SERVER_OPTIMIZE$KEYS = new long[SERVER_OPTIMIZE$SLOTS];

    /** 0 = empty, 1 = false, 2 = true (keeps "key 0 is a real key" unambiguous). */
    @Unique
    private static final byte[] SERVER_OPTIMIZE$VALUES = new byte[SERVER_OPTIMIZE$SLOTS];

    @WrapMethod(method = "inEntityTickingRange(J)Z")
    private boolean serverOptimize$memoEntityTicking(long chunkPos, Operation<Boolean> original) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.entityTickingMemo) {
            return original.call(chunkPos);
        }
        int slot = (int) ((chunkPos ^ (chunkPos >>> 32)) & (SERVER_OPTIMIZE$SLOTS - 1));
        byte cached = SERVER_OPTIMIZE$VALUES[slot];
        if (cached != 0 && SERVER_OPTIMIZE$KEYS[slot] == chunkPos) {
            return cached == 2;
        }
        boolean result = original.call(chunkPos);
        SERVER_OPTIMIZE$KEYS[slot] = chunkPos;
        SERVER_OPTIMIZE$VALUES[slot] = (byte) (result ? 2 : 1);
        return result;
    }

    @Inject(method = "runAllUpdates", at = @At("RETURN"))
    private void serverOptimize$dropMemo(ChunkMap chunkMap, CallbackInfoReturnable<Boolean> cir) {
        Arrays.fill(SERVER_OPTIMIZE$VALUES, (byte) 0);
    }
}
