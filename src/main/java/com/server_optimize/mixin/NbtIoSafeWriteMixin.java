package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Software-level safe file write for standalone NBT dat files
 * (tweak.softSaveFileWrite, copy-on-write semantics).
 *
 * Vanilla NbtIo.writeCompressed(CompoundTag, Path) / write(CompoundTag, Path)
 * open the target path directly - a crash (or taskkill) mid-write leaves a
 * truncated/corrupt file. This wraps the write in the classic CoW sequence:
 * write to a sibling "<name>.tmp", then ATOMIC_MOVE over the target. A crash
 * before the move leaves only a stray .tmp and the previous file intact.
 */
@Mixin(NbtIo.class)
public abstract class NbtIoSafeWriteMixin {

    @Inject(
        method = "writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void serverOptimize$safeWriteCompressed(CompoundTag tag,
        Path path, CallbackInfo ci) throws IOException {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.safety.softSaveFileWrite) {
            return;
        }
        ci.cancel();
        Path tmp = siblingTmp(path);
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                NbtIo.writeCompressed(tag, out);
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    @Inject(
        method = "write(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void serverOptimize$safeWrite(CompoundTag tag, Path path,
        CallbackInfo ci) throws IOException {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.safety.softSaveFileWrite) {
            return;
        }
        ci.cancel();
        Path tmp = siblingTmp(path);
        try {
            DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmp)));
            try {
                NbtIo.write(tag, out);
            } finally {
                out.close();
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static Path siblingTmp(Path path) {
        Path name = path.getFileName();
        return path.resolveSibling(name + ".tmp");
    }
}