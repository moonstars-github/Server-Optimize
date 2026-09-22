package com.server_optimize.mixin;

import com.server_optimize.config.ModConfig;
import com.server_optimize.util.ByteArrayFileChannel;
import com.server_optimize.util.RegionWriteBackManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * RegionFile write-back caching (chunk.regionFileCaching).
 *
 * At construction, the vanilla RegionFile's disk FileChannel is swapped for an
 * in-memory {@link ByteArrayFileChannel} mirror. All chunk reads AND writes
 * then hit memory (vanilla sector-allocation still runs, just on the mirror).
 * The mirror is flushed to the real region file as a whole-file atomic
 * .tmp + ATOMIC_MOVE via {@link RegionWriteBackManager} on auto-save /
 * /save-all / region close / shutdown.
 *
 * The static registry and flush logic live in RegionWriteBackManager to keep
 * this mixin free of non-private static members.
 */
@Mixin(RegionFile.class)
public abstract class RegionFileWriteBackCacheMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("server-optimize");
    private static final long MAX_MIRROR_BYTES = 32L * 1024 * 1024;

    @Shadow
    private FileChannel file;

    @Shadow
    private Path path;

    /** Swap the disk channel for the in-memory mirror right after readHeader. */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void serverOptimize$initMirror(CallbackInfo ci) {
        if (ModConfig.INSTANCE == null || !ModConfig.INSTANCE.chunk.regionFileCaching) {
            return;
        }
        try {
            Path target = this.path;
            byte[] content;
            if (target != null && Files.exists(target) && Files.size(target) <= MAX_MIRROR_BYTES) {
                content = Files.readAllBytes(target);
            } else {
                content = new byte[0];
            }
            ByteArrayFileChannel mirror = new ByteArrayFileChannel(content, content.length);
            FileChannel oldChannel = this.file;
            if (serverOptimize$replaceFinalField(this, "file", "field_20436", mirror)) {
                RegionWriteBackManager.register((RegionFile) (Object) this, mirror);
                // The abandoned disk channel still holds a Windows file handle,
                // which prevents the atomic .tmp→.mca rename during flush
                // (AccessDeniedException). Close it now that all IO goes through
                // the mirror.
                try {
                    oldChannel.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LOGGER.warn("region mirror init failed: {}", e.toString());
        }
    }

    @Unique
    private static boolean serverOptimize$replaceFinalField(Object target, String yarnName, String obfName, Object value) {
        // The `file` field is final; assigning it from a mixin handler method
        // (which is not <init> itself) fails JVM verification with
        // IllegalAccessError. Reflection bypasses the final-field check.
        Field f = null;
        try {
            Class<?> c = target.getClass();
            if (c.getName().contains("class_2861") || c.getName().equals(net.minecraft.world.level.chunk.storage.RegionFile.class.getName())) {
                try {
                    f = net.minecraft.world.level.chunk.storage.RegionFile.class.getDeclaredField(obfName);
                } catch (NoSuchFieldException e) {
                    // development environment: yarn name
                    f = net.minecraft.world.level.chunk.storage.RegionFile.class.getDeclaredField(yarnName);
                }
            } else {
                f = c.getDeclaredField(yarnName);
            }
            f.setAccessible(true);
            f.set(target, value);
            return true;
        } catch (Exception e) {
            LOGGER.warn("region mirror init failed (cannot set file field {}): {}", f == null ? "?" : f.getName(), e.toString());
            return false;
        }
    }

    /** Any chunk write marks the mirror dirty (flush happens at save points). */
    @Inject(method = "write", at = @At("RETURN"))
    private void serverOptimize$markDirty(ChunkPos pos, ByteBuffer data, CallbackInfo ci) {
        RegionWriteBackManager.markDirty((RegionFile) (Object) this);
    }

    /** Flush + unregister before the region file is closed (unloaded). */
    @Inject(method = "close", at = @At("HEAD"))
    private void serverOptimize$flushOnClose(CallbackInfo ci) {
        RegionFile self = (RegionFile) (Object) this;
        RegionWriteBackManager.flush(self);
        RegionWriteBackManager.unregister(self);
    }
}