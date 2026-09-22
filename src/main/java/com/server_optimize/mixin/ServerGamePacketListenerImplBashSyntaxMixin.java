package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.server_optimize.config.ModConfig;
import com.server_optimize.util.BashSyntax;
import com.server_optimize.util.ShellSplit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Shell-style command chaining for UNSIGNED player chat commands
 * ([command] bashSyntax): ";" / "&amp;&amp;" / "||" between commands.
 * <p>
 * Only the unsigned path is chained: signed chat commands (1.19+ security
 * chat, server.enforce-secure-profile) carry a signature over the WHOLE
 * line, and splitting them would produce unverifiable commands, so they keep
 * the vanilla behavior. The same "parse whole line first, split only on
 * parse failure" rule as CommandsBashSyntaxMixin guarantees greedy
 * arguments (/say a;b ...) keep their exact vanilla behavior.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplBashSyntaxMixin {

    @Shadow
    @Final
    private ServerPlayer player;

    @WrapMethod(method = "performUnsignedChatCommand")
    private void serverOptimize$bashChain(String command, Operation<Void> original) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.command.bashSyntax) {
            original.call(command);
            return;
        }
        MinecraftServer server = this.player.level().getServer();
        if (server == null) {
            original.call(command);
            return;
        }
        CommandSourceStack source = player.createCommandSourceStack();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
        if (parse.getExceptions().isEmpty() && !parse.getReader().canRead()) {
            original.call(command);
            return;
        }
        List<ShellSplit.Segment> segments = BashSyntax.split(command);
        if (segments.size() <= 1) {
            original.call(command);
            return;
        }
        BashSyntax.execute(source, segments);
    }
}