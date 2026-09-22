package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.thread.ChunkSavePass;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Copy-on-save snapshot pass ([thread.multithread.regionbased] save path).
 *
 * <p>ChunkMap.saveAllChunks is the single funnel for auto-save (server tick period),
 * /save-all and server shutdown. Wrapping it (instead of injecting at RETURN, which would
 * miss an exception) opens the snapshot pass around the vanilla save loop, then closes it
 * in a finally: the snapshot copies made by {@code ChunkMap.save} while the pass is open
 * are waited for before the server thread moves on (the pause at the end of the tick, before
 * the next tick can mutate the chunks again), and the write-back of the copied data - the
 * region write-back cache flush - continues on background threads for auto-save, or is
 * awaited for flush saves and shutdown. See {@link ChunkSavePass}.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapSaveFlushMixin {

    @WrapMethod(method = "saveAllChunks(Z)V")
    private void serverOptimize$saveAllChunks(boolean flush, Operation<Void> original) {
        ChunkSavePass.enter();
        try {
            original.call(flush);
        } finally {
            ChunkSavePass.exit(flush);
        }
    }
}