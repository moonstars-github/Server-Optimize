package com.server_optimize.mixin;

import com.server_optimize.networking.ZstdCompressionDecoder;
import com.server_optimize.networking.ZstdSupportPacket;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Compression codec selection.
 * <p>
 * Vanilla installs zlib codecs during the login phase, before any post-join
 * capability handshake, so the algorithm cannot be negotiated in advance.
 * Instead:
 * <ul>
 *   <li>Decoder: the server side (enforce=true) always uses the universal
 *       {@link ZstdCompressionDecoder} (zlib + ZSTD auto-detect), so a modded
 *       client may switch its outbound to ZSTD at any time without breaking
 *       the server. The client side (enforce=false) keeps vanilla zlib until
 *       the play-phase negotiation swaps it for the universal one.</li>
 *   <li>Encoder: both sides start with vanilla zlib; the play-phase
 *       negotiation ({@link com.server_optimize.networking.ZstdCodecSwitcher})
 *       swaps each side's encoder to ZSTD independently.</li>
 * </ul>
 */
@Mixin(net.minecraft.network.Connection.class)
public abstract class ConnectionCompressionMixin {

    /** Record the compression threshold for the later encoder swap. */
    @Inject(method = "setupCompression", at = @At("HEAD"))
    private void serverOptimize$noteThreshold(int threshold, boolean enforce, CallbackInfo ci) {
        ZstdSupportPacket.noteThreshold(threshold);
    }

    @Redirect(
        method = "setupCompression",
        at = @At(value = "NEW", target = "Lnet/minecraft/class_2532;", remap = false)
    )
    private static CompressionDecoder serverOptimize$zstdDecoder(int threshold, boolean enforce) {
        // Server side (enforce=true): universal decoder that handles both
        // zlib and ZSTD. Client side (enforce=false): vanilla zlib until the
        // play-phase swap installs the universal decoder.
        if (enforce) return new ZstdCompressionDecoder(threshold, true);
        return new CompressionDecoder(threshold, false);
    }

    @Redirect(
        method = "setupCompression",
        at = @At(value = "NEW", target = "Lnet/minecraft/class_2534;", remap = false)
    )
    private static CompressionEncoder serverOptimize$zstdEncoder(int threshold) {
        // Both sides start with vanilla zlib; the play-phase negotiation swaps
        // the encoder to ZSTD per side (each side's decoder handles both).
        return new CompressionEncoder(threshold);
    }
}