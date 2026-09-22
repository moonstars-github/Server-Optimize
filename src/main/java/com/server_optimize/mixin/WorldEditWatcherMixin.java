package com.server_optimize.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * WorldEdit's RecursiveDirectoryWatcher creates a non-daemon watch thread
 * (no setDaemon(true), no close hook). After the server shuts down the
 * thread keeps waiting on its queue forever, so the JVM never exits and the
 * server can only be killed with taskkill. Redirect the thread creation to
 * mark it daemon.
 */
@Pseudo
@Mixin(targets = "com.sk89q.worldedit.internal.util.RecursiveDirectoryWatcher")
public abstract class WorldEditWatcherMixin {

    @Redirect(method = "start", at = @At(value = "NEW", target = "Ljava/lang/Thread;"))
    private Thread serverOptimize$daemonWatcher(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    }
}
