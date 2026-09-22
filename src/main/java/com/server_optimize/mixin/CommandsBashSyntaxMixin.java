package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.server_optimize.config.ModConfig;
import com.server_optimize.util.BashSyntax;
import com.server_optimize.util.ShellSplit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Shell-style command chaining for console / RCON / prefixed command input
 * ([command] bashSyntax): ";" / "&amp;&amp;" / "||" between commands.
 * <p>
 * A line is ONLY split when it fails to parse as ONE vanilla command, so
 * greedy arguments (/say a;b, /tellraw ... "x;y", /execute run say a;b)
 * keep their exact vanilla behavior. Console input (DedicatedServer console)
 * and RCON both route through Commands.performPrefixedCommand, so this mixin
 * covers both entry points.
 */
@Mixin(Commands.class)
public abstract class CommandsBashSyntaxMixin {

    @WrapMethod(method = "performPrefixedCommand")
    private void serverOptimize$bashChain(CommandSourceStack source, String command,
                                          Operation<Void> original) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.command.bashSyntax) {
            original.call(source, command);
            return;
        }
        String cmd = Commands.trimOptionalPrefix(command);
        CommandDispatcher<CommandSourceStack> dispatcher =
            ((Commands) (Object) this).getDispatcher();
        // Parse failure is reported inside ParseResults (brigadier.parse does
        // not throw); an empty exception map + fully-consumed reader means the
        // whole line is ONE valid vanilla command -> run it exactly as vanilla
        // would (greedy arguments and all).
        ParseResults<CommandSourceStack> parse = dispatcher.parse(cmd, source);
        if (parse.getExceptions().isEmpty() && !parse.getReader().canRead()) {
            original.call(source, command);
            return;
        }
        List<ShellSplit.Segment> segments = BashSyntax.split(cmd);
        if (segments.size() <= 1) {
            // No chaining operator: keep the vanilla behavior (vanilla will
            // report the same parse error it always would).
            original.call(source, command);
            return;
        }
        BashSyntax.execute(source, segments);
    }
}