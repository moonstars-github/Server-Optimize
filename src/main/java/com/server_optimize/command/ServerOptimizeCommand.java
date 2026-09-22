package com.server_optimize.command;

import com.server_optimize.ServerOptimize;
import com.server_optimize.config.ModConfig;
import com.server_optimize.mixin.accessor.CommandSourceStackAccessor;
import com.server_optimize.util.NetworkStatus;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;

import java.lang.reflect.Method;
import java.util.function.Predicate;

public final class ServerOptimizeCommand {
    private static final int OP_LEVEL = 4;
    private static final String ROOT_PERMISSION = com.server_optimize.util.LuckPermsNodes.ROOT;
    private static final String RELOAD_PERMISSION = com.server_optimize.util.LuckPermsNodes.RELOAD;
    private static final String GC_PERMISSION = com.server_optimize.util.LuckPermsNodes.GC;
    private static final String STATUS_PERMISSION = com.server_optimize.util.LuckPermsNodes.STATUS;
    private static final String THREAD_STATUS_PERMISSION = com.server_optimize.util.LuckPermsNodes.THREAD_STATUS;

    private ServerOptimizeCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = Commands.literal("serveroptimize")
                .requires(requirePermission(ROOT_PERMISSION));
            root = root.then(Commands.literal("reload")
                .requires(requirePermission(RELOAD_PERMISSION))
                .executes(ServerOptimizeCommand::executeReload));
            root = root.then(Commands.literal("gc")
                .requires(requirePermission(GC_PERMISSION))
                .executes(ServerOptimizeCommand::executeGC));
            // status net is server-only: it reports the network transport
            // negotiated with connected clients. In singleplayer the
            // integrated server has no real network, so the command is not
            // registered there (singleplayer disabled).
            // status net 仅独立服务端:它报告与已连接客户端的网络协商结果。
            // 单人世界集成服务端无真实网络,故不注册该命令(单人世界禁用)。
            // status thread works everywhere, including the single-player integrated
            // server: it only reports thread/worker usage. status net stays
            // dedicated-only, because it reports the network transport negotiated with
            // connected clients and single-player has no real network.
            var statusNode = Commands.literal("status")
                .requires(requirePermission(STATUS_PERMISSION))
                .then(Commands.literal("thread")
                    .requires(requirePermission(THREAD_STATUS_PERMISSION))
                        .then(Commands.literal("count")
                            .executes(ServerOptimizeCommand::executeStatusThread))
                        .then(Commands.literal("balance")
                            .executes(ServerOptimizeCommand::executeStatusThreadBalance)));
            if (environment == net.minecraft.commands.Commands.CommandSelection.DEDICATED) {
                statusNode = statusNode.then(Commands.literal("net")
                    .executes(ServerOptimizeCommand::executeStatusNet)
                    .then(Commands.literal("player")
                        .executes(ctx -> executeStatusNetPlayer(ctx, false))
                        .then(Commands.literal("count")
                            .executes(ctx -> executeStatusNetPlayer(ctx, true)))
                        .then(net.minecraft.commands.Commands.argument("targets",
                                net.minecraft.commands.arguments.EntityArgument.players())
                            .executes(ServerOptimizeCommand::executeStatusNetPlayerTarget))));
            }
            root = root.then(statusNode);
            root = root.executes(ServerOptimizeCommand::executeHelp);
            dispatcher.register(root);
        });
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String executor = executorName(source);
        try {
            ModConfig.load();
            // Thread-affinity changes apply at the next tick boundary
            // (server thread re-pins itself; render-thread changes need a
            // client restart).
            com.server_optimize.thread.AffinityManager.requestReapply();
            source.sendSuccess(() -> Component.literal("[" + executor + "] reload config successful"), false);
            return 1;
        } catch (Exception e) {
            ServerOptimize.LOGGER.error("Failed to reload server-optimize config", e);
            source.sendFailure(Component.literal("[" + executor + "] reload config failed, see server log"));
            return 0;
        }
    }

    private static int executeGC(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String executor = executorName(source);
        if (ModConfig.INSTANCE == null || !ModConfig.INSTANCE.command.commandGC) {
            source.sendFailure(Component.literal("[" + executor + "] GC command is disabled (command.commandGC=false)"));
            return 0;
        }
        // Note: on ZGC/G1, System.gc() triggers a stop-the-world Full GC unless
        // the JVM was started with -XX:+ExplicitGCInvokesConcurrent. This is the
        // explicit intent of the command - administrators invoke it manually.
        ServerOptimize.LOGGER.info("/serveroptimize gc: requesting JVM GC");
        source.sendSuccess(() -> Component.literal("[" + executor + "] requesting JVM garbage collection..."), false);
        System.gc();
        source.sendSuccess(() -> Component.literal("[" + executor + "] GC completed (request submitted via System.gc())"), false);
        return 1;
    }

    private static int executeStatusNet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.command.commandNetworkStatus) {
            source.sendFailure(Component.literal("status net is disabled (command.commandNetworkStatus=false)"));
            return 0;
        }
        boolean udp = NetworkStatus.isUdpActive();
        boolean zstd = NetworkStatus.isZstdActive();
        source.sendSuccess(() -> Component.literal("your network status:").withColor(0xFFFF55), false);
        if (udp && zstd) {
            source.sendSuccess(() -> Component.literal("UDP"), false);
            source.sendSuccess(() -> Component.literal("ZSTD(level=" + cfg.net.zstdLevel + ")"), false);
        } else if (udp) {
            source.sendSuccess(() -> Component.literal("UDP"), false);
        } else if (zstd) {
            source.sendSuccess(() -> Component.literal("ZSTD(level=" + cfg.net.zstdLevel + ")"), false);
        } else {
            source.sendSuccess(() -> Component.literal("vanilla"), false);
        }
        return 1;
    }

    /** /serveroptimize status net player {name|selector}: subset of players. */
    private static int executeStatusNetPlayerTarget(
            CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.command.commandNetworkStatus) {
            source.sendFailure(Component.literal("status net is disabled (command.commandNetworkStatus=false)"));
            return 0;
        }
        java.util.Collection<net.minecraft.server.level.ServerPlayer> players =
            net.minecraft.commands.arguments.EntityArgument.getPlayers(context, "targets");
        source.sendSuccess(() -> Component.literal("players network status:").withColor(0xFFFF55), false);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (net.minecraft.server.level.ServerPlayer p : players) {
            lines.add(formatPlayerLine(p));
        }
        if (lines.isEmpty()) {
            lines.add("(no players)");
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** One line per player: "{name}: {UDP ZSTD|UDP|ZSTD|vanilla}". Carpet
     *  bots (which skip the capability handshake) render as "vanilla(bot)". */
    private static String formatPlayerLine(net.minecraft.server.level.ServerPlayer p) {
        String name = p.getGameProfile().name();
        if (isCarpetBot(p)) {
            return name + ": vanilla(bot)";
        }
        return name + ": " + protocolTokens(p);
    }

    /** Both active -> "UDP ZSTD"; one -> that one; neither -> "vanilla". */
    private static String protocolTokens(net.minecraft.server.level.ServerPlayer p) {
        boolean u = com.server_optimize.networking.udp.UdpMigration.isUdpActive(p);
        boolean z = com.server_optimize.networking.ZstdSupportPacket.isZstdActive(p);
        if (u && z) return "UDP ZSTD";
        if (u) return "UDP";
        if (z) return "ZSTD";
        return "vanilla";
    }

    /** Carpet fake players use a subclass of ServerGamePacketListenerImpl and
     *  never run the mod's UDP/ZSTD handshake. */
    private static boolean isCarpetBot(net.minecraft.server.level.ServerPlayer player) {
        try {
            return player.connection != null
                && player.connection.getClass() != net.minecraft.server.network.ServerGamePacketListenerImpl.class;
        } catch (Exception e) {
            return false;
        }
    }

    /** /serveroptimize status net player [count]: per-player protocol. */
    private static int executeStatusNetPlayer(CommandContext<CommandSourceStack> context, boolean countOnly) {
        CommandSourceStack source = context.getSource();
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.command.commandNetworkStatus) {
            source.sendFailure(Component.literal("status net is disabled (command.commandNetworkStatus=false)"));
            return 0;
        }
        java.util.List<net.minecraft.server.level.ServerPlayer> players =
            source.getServer().getPlayerList().getPlayers();
        source.sendSuccess(() -> Component.literal("players network status:").withColor(0xFFFF55), false);
        if (countOnly) {
            int udp = 0;
            int zstd = 0;
            int vanilla = 0;
            for (net.minecraft.server.level.ServerPlayer p : players) {
                boolean u = com.server_optimize.networking.udp.UdpMigration.isUdpActive(p);
                boolean z = com.server_optimize.networking.ZstdSupportPacket.isZstdActive(p);
                if (u) udp++;
                if (z) zstd++;
                if (!u && !z) vanilla++;
            }
            final int udpOut = udp;
            final int zstdOut = zstd;
            final int vanillaOut = vanilla;
            source.sendSuccess(() -> Component.literal("Player use UDP: " + udpOut), false);
            source.sendSuccess(() -> Component.literal("Player use ZSTD: " + zstdOut), false);
            source.sendSuccess(() -> Component.literal("Player use vanilla: " + vanillaOut), false);
        } else {
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (net.minecraft.server.level.ServerPlayer p : players) {
                lines.add(formatPlayerLine(p));
            }
            if (lines.isEmpty()) {
                lines.add("(no players)");
            }
            for (String line : lines) {
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return 1;
    }

    private static int executeHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
            "Usage: /serveroptimize reload | gc | status net"), false);
        return 1;
    }

    /** Console = Server, Rcon = Rcon, player = their name. */
    private static String executorName(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player.getGameProfile().name();
        }
        try {
            CommandSource src = ((CommandSourceStackAccessor) source).serverOptimize$getSource();
            if (src instanceof RconConsoleSource) {
                return "Rcon";
            }
        } catch (Exception ignored) {
        }
        return "Server";
    }

    /** /serveroptimize status thread: thread and worker usage. */
    private static int executeStatusThread(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.command.commandThreadStatus) {
            source.sendFailure(Component.literal("status thread is disabled (command.commandThreadStatus=false)"));
            return 0;
        }
        // Our own source of truth: the pool this mod manages is sized from the allowed
        // CPU set (and capped by [thread.multithread] parallelThreads).
        final int usedThreads = com.server_optimize.thread.AffinityManager.allowedWorkerCount();
        final String randomTickText = com.server_optimize.thread.AffinityManager.randomTickThreadsText();
        source.sendSuccess(() -> Component.literal("-----多线程时长统计-----").withColor(0xFFFF55), false);
        source.sendSuccess(() -> Component.literal("并行线程池数: ").append(Component.literal(Integer.toString(usedThreads)).withColor(0xFFFF55)), false);
        source.sendSuccess(() -> Component.literal("线程池调度开销: ").append(Component.literal(schedulingOverheadText()).withColor(0xFFFF55)), false);
        String rawTick = com.server_optimize.util.WorkerBalance.tickMillisText(com.server_optimize.util.WorkerBalance.RANDOM_TICK);
        boolean tickHasValue = !"无数据".equals(rawTick);
        String tickText = tickHasValue ? rawTick + "ms" : noDataOr(rawTick);
        if (tickHasValue && !com.server_optimize.util.WorkerBalance.lastPassParallel(com.server_optimize.util.WorkerBalance.RANDOM_TICK)) {
            // The last pass ran without the pool - the feature is off, or the pass was below
            // the parallel threshold - so the number must not be read as a parallel one.
            tickText += " (单线程)";
        }
        final String tickLine = tickText;
        source.sendSuccess(() -> Component.literal("随机刻总用时: ").append(Component.literal(tickLine).withColor(0xFFFF55)), false);
        source.sendSuccess(() -> Component.literal("区域并行数: ").append(Component.literal(Integer.toString(com.server_optimize.thread.RegionTickDriver.totalRegionCount())).withColor(0xFFFF55)), false);
        source.sendSuccess(() -> Component.literal(" minecraft:overworld: ").append(Component.literal(Integer.toString(com.server_optimize.thread.RegionTickDriver.regionCountOf("minecraft:overworld"))).withColor(0xFFFF55)), false);
        source.sendSuccess(() -> Component.literal(" minecraft:the_nether: ").append(Component.literal(Integer.toString(com.server_optimize.thread.RegionTickDriver.regionCountOf("minecraft:the_nether"))).withColor(0xFFFF55)), false);
        source.sendSuccess(() -> Component.literal(" minecraft:the_end: ").append(Component.literal(Integer.toString(com.server_optimize.thread.RegionTickDriver.regionCountOf("minecraft:the_end"))).withColor(0xFFFF55)), false);
        return 1;
    }

    /** Scheduling-overhead total across every multithreaded module; 无数据 when none is on. */
    private static String schedulingOverheadText() {
        ModConfig cfg = ModConfig.INSTANCE;
        double total = 0.0D;
        boolean any = false;
        // The random-tick pass is the single multithreaded tick phase - whether it runs as
        // randomTickParallel itself or as the region engine (one worker per connected region) -
        // so its scheduling overhead counts whenever either gate is on: the part of the pass
        // wall that no single thread's own work covers (task hand-out, worker wake-up, join
        // latency beyond the busiest thread).
        if (cfg != null && (cfg.thread.randomTickParallel
            || cfg.thread.regionbased.enableRegionBasedMultithreadTicking)) {
            double randomTick = com.server_optimize.util.WorkerBalance.overheadMillis(
                com.server_optimize.util.WorkerBalance.RANDOM_TICK);
            if (randomTick >= 0.0D) {
                total += randomTick;
                any = true;
            }
        }
        if (cfg != null && cfg.thread.regionbased.enableRegionBasedMultithreadTicking) {
            // The region engine's entity flush is its own phase; its scheduling overhead
            // (wall span minus the busiest thread) counts here alongside the random-tick pass.
            double regionOverhead = com.server_optimize.util.WorkerBalance.overheadMillis(
                com.server_optimize.util.WorkerBalance.REGION);
            if (regionOverhead >= 0.0D) {
                total += regionOverhead;
                any = true;
            }
            total += com.server_optimize.thread.RegionTickDriver.regionOverheadMillis();
            any = true;
        }
        if (!any) {
            return "无数据";
        }
        return String.format(java.util.Locale.ROOT, "%.3fms", total);
    }
    /**
     * /serveroptimize status thread balance: worker load balance of the parallel phases.
     *
     * <p>Reports shortest-thread-time / longest-thread-time * 100% per phase, with the
     * server thread counted as a participant because it takes part in the phase (it draws
     * the random-tick sample positions and applies the ticks). Available in single-player
     * too: the status subtree is built outside the dedicated-server branch, and this
     * report only reads in-process counters.
     */
    private static int executeStatusThreadBalance(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.command.commandThreadStatus) {
            source.sendFailure(Component.literal("status thread is disabled (command.commandThreadStatus=false)"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("-----多线程负载均衡-----").withColor(0xFFFF55), false);
        ModConfig balanceCfg = ModConfig.INSTANCE;
        // 区域多线程 is the region engine's per-THREAD balance: shortest-thread tick time /
        // longest-thread tick time of the region phase. A worker thread may run several
        // connected regions sequentially (the dynamic scheduling yields a thread to the next
        // region when one finishes), so the per-REGION min/max would misreport the load
        // distribution; the per-thread ratio is what the scheduler actually produces.
        // 随机刻 is randomTickParallel's balance: with the region engine on, that option only
        // adds the WITHIN-region split, so its balance is 未开启功能 when the option is off.
        boolean regionOn = balanceCfg != null
            && balanceCfg.thread.regionbased.enableRegionBasedMultithreadTicking;
        String regionText;
        if (!regionOn) {
            regionText = "未开启功能";
        } else {
            double regionPct = com.server_optimize.util.WorkerBalance.percentDouble(
                com.server_optimize.util.WorkerBalance.REGION);
            regionText = regionPct < 0.0D
                ? "无数据"
                : String.format(java.util.Locale.ROOT, "%.2f%%", regionPct);
        }
        source.sendSuccess(() -> Component.literal("区域多线程: ").append(Component.literal(regionText).withColor(0xFFFF55)), false);
        boolean randomTickOn = balanceCfg != null && balanceCfg.thread.randomTickParallel;
        String randomTickText;
        if (!randomTickOn) {
            randomTickText = "未开启功能";
        } else {
            double rtPercent = com.server_optimize.util.WorkerBalance.percentDouble(
                com.server_optimize.util.WorkerBalance.RANDOM_TICK);
            randomTickText = rtPercent < 0.0D
                ? noDataOr("无数据")
                : String.format(java.util.Locale.ROOT, "%.2f%%", rtPercent);
        }
        source.sendSuccess(() -> Component.literal("随机刻: ").append(Component.literal(randomTickText).withColor(0xFFFF55)), false);
        return 1;
    }

    /**
     * Renders a random-tick counter, saying why it is empty instead of just 无数据.
     * The phase is off in the config, or the last pass was shorter than the parallel
     * threshold - either way the pool did not participate, which is why there is no
     * balance or time to report.
     */
    private static String noDataOr(String raw) {
        if (!"无数据".equals(raw)) {
            return raw + "ms";
        }
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.thread.randomTickParallel) {
            return "未在配置文件中启用";
        }
        double last = com.server_optimize.util.WorkerBalance.lastSpanMillis(com.server_optimize.util.WorkerBalance.RANDOM_TICK);
        long threshold = cfg.thread.randomTickParallelThresholdMs;
        String time = last < 0.0D ? "--" : String.format(java.util.Locale.ROOT, "%.1fms", last);
        return String.format(java.util.Locale.ROOT, "未达到启用阈值(%s/%dms)", time, threshold);
    }

    private static Predicate<CommandSourceStack> requirePermission(String permission) {
        // Use Fabric Permissions API when LuckPerms or vanilla-permissions
        // provides it. If it is absent, fall back to vanilla OP level 4.
        try {
            Class<?> permissions = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            Method require = permissions.getMethod("require", String.class, int.class);
            Object predicate = require.invoke(null, permission, OP_LEVEL);
            @SuppressWarnings("unchecked")
            Predicate<CommandSourceStack> typed = (Predicate<CommandSourceStack>) predicate;
            return typed;
        } catch (ReflectiveOperationException | LinkageError e) {
            return Commands.hasPermission(Commands.LEVEL_OWNERS);
        }
    }
}
