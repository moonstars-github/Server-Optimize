package com.server_optimize.util;

import com.server_optimize.ServerOptimize;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * Registers this mod's command permission nodes with LuckPerms so they show
 * up in {@code /lp ... permission set <node>} suggestions and can be granted
 * to players/groups. The nodes are ALSO used by the command predicates, so a
 * grant of e.g. {@code serveroptimize.status} actually unlocks the status
 * command for a player.
 * <p>
 * fabric-permissions-api (used by LuckPerms / vanilla-permissions) has no
 * node-registration API, and LuckPerms' {@code PermissionRegistry} is
 * internal, so the registration walks the runtime objects reflectively:
 * {@code LuckPermsProvider.get()} -> LuckPermsApiProvider.plugin (private
 * field) -> getPermissionRegistry() -> offer(node). All of it is skipped
 * silently when LuckPerms is absent.
 */
public final class LuckPermsNodes {

    public static final String ROOT = "serveroptimize";
    public static final String RELOAD = "serveroptimize.reload";
    public static final String GC = "serveroptimize.gc";
    public static final String STATUS = "serveroptimize.status";
    /** /serveroptimize status thread - thread/worker usage report. */
    public static final String THREAD_STATUS = "serveroptimize.status.thread";

    private static volatile boolean attempted;
    private static volatile boolean registered;

    private LuckPermsNodes() {
    }

    /** All nodes this mod exposes, in command-tree order. */
    public static String[] allNodes() {
        return new String[]{
            ROOT,
            RELOAD,
            GC,
            STATUS,
            THREAD_STATUS,
        };
    }

    /** True once the nodes were successfully offered to LuckPerms. */
    public static boolean isRegistered() {
        return registered;
    }

    /**
     * Registers all nodes with LuckPerms. Safe to call any time after
     * LuckPerms finished loading; no-ops when LuckPerms is not present.
     * Returns false when LuckPerms is absent or not yet ready.
     */
    public static boolean registerAll() {
        if (attempted) {
            return registered;
        }
        attempted = true;
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object api = providerClass.getMethod("get").invoke(null);
            // api = me.lucko.luckperms.common.api.LuckPermsApiProvider
            Field pluginField = api.getClass().getDeclaredField("plugin");
            pluginField.setAccessible(true);
            Object plugin = pluginField.get(api);
            Method getRegistry = plugin.getClass().getMethod("getPermissionRegistry");
            Object registry = getRegistry.invoke(plugin);
            Method offer = registry.getClass().getMethod("offer", String.class);
            String[] nodes = allNodes();
            for (String node : nodes) {
                offer.invoke(registry, node);
            }
            registered = true;
            ServerOptimize.LOGGER.info("LuckPerms: registered {} permission node(s): {}",
                nodes.length, String.join(", ", nodes));
        } catch (Throwable t) {
            // LuckPerms absent, or not loaded yet (retried by the caller).
            attempted = false;
            ServerOptimize.LOGGER.debug("LuckPerms node registration skipped: {}", String.valueOf(t));
        }
        return registered;
    }

    /**
     * Permission predicate that honors the given LuckPerms node when
     * fabric-permissions-api is present; without it, console/RCON pass and
     * players need vanilla op level 3+ (ADMINS). Every command node of this
     * mod uses it, so a LuckPerms grant of e.g.
     * {@code serveroptimize.status} gates it, while RCON/console keep
     * working everywhere.
     */
    public static Predicate<CommandSourceStack> require(String node) {
        try {
            Class<?> permissions = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            Method require = permissions.getMethod("require", String.class, int.class);
            @SuppressWarnings("unchecked")
            Predicate<CommandSourceStack> typed =
                (Predicate<CommandSourceStack>) require.invoke(null, node, 4);
            return typed;
        } catch (ReflectiveOperationException | LinkageError e) {
            return source -> {
                if (!source.isPlayer()) {
                    return true; // console / RCON
                }
                PermissionSet perms = source.permissions();
                if (perms instanceof LevelBasedPermissionSet lbs) {
                    return lbs.level().id() >= PermissionLevel.ADMINS.id();
                }
                return false;
            };
        }
    }
}