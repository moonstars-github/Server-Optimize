package com.server_optimize.util;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell-style command chaining ([command] bashSyntax): ";" (always run the
 * next command), "&amp;&amp;" (run next only if the previous succeeded) and
 * "||" (run next only if the previous failed).
 * <p>
 * The splitting itself lives in {@link ShellSplit}, which has no Minecraft
 * class references so the CLIENT-side command-completion hook can use the
 * exact same syntax while parsing the chat input. This class adds the
 * server-side execution.
 * <p>
 * Safety rule honored by the callers: a command line is ONLY split when the
 * WHOLE line fails to parse as a single vanilla command. Greedy arguments
 * (/say a;b, /tellraw ... "x;y", /execute run say a;b) therefore keep their
 * exact vanilla behavior, and only inputs that vanilla would reject gain the
 * new chaining semantics. The splitter is quote-aware (double quotes and \"
 * escapes are respected, mirroring brigadier), so ";" / "&amp;&amp;" / "||"
 * inside a quoted argument never split. Signed chat commands (1.19+ security
 * chat) are never routed here - their signature covers the whole line.
 * <p>
 * Failure definition per segment: parse error OR execution throwing an
 * exception = failed. Each segment is parsed and executed independently with
 * the same source, so vanilla permission checks apply per command.
 * <p>
 * ASYNC SEGMENTS: Carpet's {@code /player <name> spawn} / {@code kill} accept
 * the command and return immediately while the actual spawn / death runs over
 * the following ticks. For those two actions the chain does not treat the
 * segment as succeeded until the operation has actually completed - the bot
 * is in the player list (spawn) or gone from it (kill) - and only then runs
 * the next segment. The check is driven by the server tick end (one poll per
 * tick) and gives up after {@value #PLAYER_ACTION_TIMEOUT_TICKS} ticks,
 * reporting the segment as failed.
 */
public final class BashSyntax {

    /** How many ticks a /player spawn|kill may take before the segment is failed. */
    private static final int PLAYER_ACTION_TIMEOUT_TICKS = 120;

    /** Carpet's /player action that starts a fake player's life or death. */
    private static final Pattern PLAYER_ACTION =
        Pattern.compile("^player\\s+(\\S+)\\s+(spawn|kill)(?:\\s+.*)?$");

    private static final Queue<Pending> PENDING = new ConcurrentLinkedQueue<>();

    private static volatile boolean tickHooked;

    private BashSyntax() {
    }

    /** True when the string contains a top-level (unquoted) ;, &amp;&amp; or ||. */
    public static boolean hasOperators(String s) {
        return ShellSplit.hasOperators(s);
    }

    /** Splits into segments (see {@link ShellSplit#split(String)}). */
    public static List<ShellSplit.Segment> split(String s) {
        return ShellSplit.split(s);
    }

    /**
     * Executes split segments sequentially with bash semantics:
     * ';' always runs; '&amp;&amp;' runs only when the previous succeeded;
     * '||' runs only when the previous failed. A segment that is a Carpet
     * /player spawn|kill pauses the chain until the operation actually
     * completes (or times out) before the next segment is considered.
     */
    public static void execute(CommandSourceStack source, List<ShellSplit.Segment> segments) {
        ensureTickHook();
        new Runner(source, segments).advance();
    }

    /** One chain in flight, resumed by {@link #tickPending}. */
    private static final class Runner {
        private final CommandSourceStack source;
        private final List<ShellSplit.Segment> segments;
        private int index;
        private boolean previousSucceeded = true;

        Runner(CommandSourceStack source, List<ShellSplit.Segment> segments) {
            this.source = source;
            this.segments = segments;
        }

        void advance() {
            while (index < segments.size()) {
                ShellSplit.Segment segment = segments.get(index++);
                if (segment.op() == '&' && !previousSucceeded) {
                    continue; // && after a failure
                }
                if (segment.op() == '|' && previousSucceeded) {
                    continue; // || after a success
                }
                String toRun = stripLeadingSlash(segment.text());
                Matcher playerAction = PLAYER_ACTION.matcher(toRun);
                if (playerAction.matches()) {
                    String name = playerAction.group(1);
                    String action = playerAction.group(2);
                    // Kick the action off now (the /player command accepts and returns
                    // immediately); its completion decides the chain.
                    if (!runOne(source, toRun)) {
                        previousSucceeded = false;
                        continue;
                    }
                    BooleanSupplier condition = "kill".equals(action)
                        ? () -> source.getServer().getPlayerList().getPlayerByName(name) == null
                        : () -> source.getServer().getPlayerList().getPlayerByName(name) != null;
                    final String detail = name + " " + action;
                    PENDING.add(new Pending(condition,
                        (ok, timedOut) -> {
                            if (!ok && timedOut) {
                                source.sendFailure(Component.literal(
                                    "player action timed out: " + detail));
                            }
                            previousSucceeded = ok;
                            advance();
                        }));
                    return; // the waiter resumes the chain
                }
                previousSucceeded = runOne(source, toRun);
            }
        }
    }

    /** A /player spawn|kill whose completion is being awaited, checked once per tick. */
    private static final class Pending {
        final BooleanSupplier condition;
        final BiConsumer<Boolean, Boolean> callback; // (succeeded, timedOut)
        int ticks;

        Pending(BooleanSupplier condition, BiConsumer<Boolean, Boolean> callback) {
            this.condition = condition;
            this.callback = callback;
        }
    }

    /** Installs a once-per-server-tick poller the /player waits are driven by. */
    private static void ensureTickHook() {
        if (tickHooked) {
            return;
        }
        synchronized (BashSyntax.class) {
            if (tickHooked) {
                return;
            }
            ServerTickEvents.END_SERVER_TICK.register(server -> tickPending());
            tickHooked = true;
        }
    }

    /** One tick's pass over the outstanding /player waits. */
    private static void tickPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            boolean ok;
            try {
                ok = pending.condition.getAsBoolean();
            } catch (Throwable t) {
                ok = false; // server shutting down or the level is gone
            }
            if (ok) {
                it.remove();
                pending.callback.accept(true, false);
                continue;
            }
            if (pending.ticks++ >= PLAYER_ACTION_TIMEOUT_TICKS) {
                it.remove();
                pending.callback.accept(false, true);
            }
        }
    }

    /** Runs one command; the leading '/' the splitter may have passed through is removed. */
    private static boolean runOne(CommandSourceStack source, String command) {
        CommandDispatcher<CommandSourceStack> dispatcher;
        try {
            dispatcher = source.getServer().getCommands().getDispatcher();
        } catch (Exception e) {
            source.sendFailure(Component.literal("chained command unavailable: " + e.getMessage()));
            return false;
        }
        try {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
            dispatcher.execute(parse);
            return true;
        } catch (CommandSyntaxException e) {
            // Same style as the vanilla error feedback for a bad command.
            source.sendFailure(Component.literal(e.getRawMessage().getString()));
            return false;
        } catch (Exception e) {
            source.sendFailure(Component.literal(
                "command failed: " + (e.getMessage() != null ? e.getMessage() : e.toString())));
            return false;
        }
    }

    private static String stripLeadingSlash(String command) {
        String trimmed = command.stripLeading();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }
}