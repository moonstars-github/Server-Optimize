package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.server_optimize.config.ModConfig;
import com.server_optimize.util.NbtIntern;
import net.minecraft.nbt.NbtAccounter;
import org.spongepowered.asm.mixin.Mixin;

import java.io.DataInput;
import java.io.IOException;

/**
 * Canonicalises NBT key names while reading (chunk.nbt.internTagNames).
 * <p>
 * Targets the private static {@code readString} of the compound reader, which is
 * the site reported as {@code String.newStringNoRepl <- DataInputStream.readUTF}
 * in the 15.8.4 allocation profile (~3.5 GB per minute plus the strings). The
 * method itself is untouched - its result is only replaced by a previously seen
 * instance of the same value, so tag semantics (value equality) are unchanged
 * and duplicates become garbage immediately.
 */
@Mixin(targets = "net.minecraft.nbt.CompoundTag$1")
public abstract class CompoundTagStringInternMixin {

    @WrapMethod(method = "readString(Ljava/io/DataInput;Lnet/minecraft/nbt/NbtAccounter;)Ljava/lang/String;")
    private static String serverOptimize$internName(DataInput input, NbtAccounter accounter,
                                                    Operation<String> original) throws IOException {
        String value = original.call(input, accounter);
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.chunk.internTagNames) {
            return value;
        }
        return NbtIntern.intern(value, cfg.chunk.internTagNamesLimit);
    }
}
