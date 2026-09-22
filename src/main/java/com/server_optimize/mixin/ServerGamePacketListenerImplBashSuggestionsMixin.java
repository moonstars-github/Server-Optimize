package com.server_optimize.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import com.server_optimize.config.ModConfig;
import com.server_optimize.util.OperatorSuggestions;
import com.server_optimize.util.ShellSplit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;


/**
 * Tab-completion support for the shell-style command chaining
 * ([command] bashSyntax). When the client asks for suggestions on a line that
 * is NOT one valid vanilla command but contains ";" / "&amp;&amp;" / "||",
 * the completion for the LAST (currently typed) segment is computed, so
 * "/say a; se&lt;Tab&gt;" completes "seed"/"serveroptimize..." inside the
 * chain. It also offers the operators themselves when the caret sits behind a
 * space. Fully-valid vanilla lines and lines without operators keep the
 * vanilla suggestion path untouched.
 * <p>
 * CRITICAL - coordinate space: the client sends the WHOLE command string
 * (including the leading '/') and applies the suggestion ranges it gets back
 * directly to that string (CommandSuggestions$SuggestionsList.useSuggestion:
 * {@code input.setValue(suggestion.apply(originalContents))}, cursor =
 * range.getStart() + text.length()). Vanilla therefore parses the packet
 * string itself and merely skips the '/' with the reader, keeping every range
 * an absolute index into the client's text. An earlier version of this hook
 * stripped the '/' by rebuilding the string ("substring(1)"), which shifted
 * EVERY range - and with it the insertion point and the popup anchor - one
 * character to the left. The same reader-based approach is used here (also for
 * the chained segment), so no range shifting is needed anywhere.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplBashSuggestionsMixin {

    private static final int MAX_SUGGESTIONS = 1000;

    @Shadow
    @Final
    private ServerPlayer player;

    @WrapMethod(method = "handleCustomCommandSuggestions")
    private void serverOptimize$bashSuggestions(ServerboundCommandSuggestionPacket packet,
                                                Operation<Void> original) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || !cfg.command.bashSyntax) {
            original.call(packet);
            return;
        }
        // The client's text, exactly as the client will edit it (leading '/'
        // included) - every range we return must live in this coordinate space.
        String command = packet.getCommand();
        MinecraftServer server = this.player.level().getServer();
        if (server == null) {
            original.call(packet);
            return;
        }
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack source = this.player.createCommandSourceStack();
        // The client always completes at its caret, which the packet does not
        // carry; vanilla assumes the end of the line and so do we.
        int cursor = command.length();
        boolean offerOperators = OperatorSuggestions.atTokenStart(command, cursor);

        ParseResults<CommandSourceStack> whole;
        try {
            whole = dispatcher.parse(commandReader(command), source);
        } catch (Exception e) {
            original.call(packet);
            return;
        }
        boolean wholeValid = whole.getExceptions().isEmpty() && !whole.getReader().canRead();
        if (wholeValid && !offerOperators) {
            // One valid vanilla command, nothing to add: vanilla completion.
            original.call(packet);
            return;
        }
        // Complete the whole line when it is valid; otherwise complete the
        // segment the caret is in (parsed from the SAME string, so its ranges
        // are already absolute). Parsing a segment that starts BEHIND the
        // caret would make brigadier's findSuggestionContext throw.
        ParseResults<CommandSourceStack> targetParse = whole;
        if (!wholeValid) {
            int segmentStart = ShellSplit.segmentStartFor(command, cursor);
            if (segmentStart > 0) {
                try {
                    targetParse = dispatcher.parse(segmentReader(command, segmentStart), source);
                } catch (Exception e) {
                    original.call(packet);
                    return;
                }
            } else if (!offerOperators) {
                original.call(packet);
                return;
            }
        }
        final boolean withOperators = offerOperators;
        try {
            dispatcher.getCompletionSuggestions(targetParse, cursor).thenAccept(suggestions -> {
                Suggestions out = withOperators
                    ? OperatorSuggestions.append(suggestions, cursor)
                    : suggestions;
                if (out.getList().size() > MAX_SUGGESTIONS) {
                    out = new Suggestions(out.getRange(), out.getList().subList(0, MAX_SUGGESTIONS));
                }
                Suggestions finalOut = out;
                server.execute(() ->
                    this.player.connection.send(new ClientboundCommandSuggestionsPacket(packet.getId(), finalOut)));
            });
        } catch (Exception e) {
            // Fall back to vanilla on any completion hiccup.
            original.call(packet);
        }
    }

    /** A reader over the full command, positioned after a leading '/' - the
     *  same way vanilla parses the packet string. */
    private static StringReader commandReader(String command) {
        StringReader reader = new StringReader(command);
        if (reader.canRead() && reader.peek() == '/') {
            reader.skip();
        }
        return reader;
    }

    /** A reader over the full command, positioned at a chained segment. */
    private static StringReader segmentReader(String command, int offset) {
        StringReader reader = new StringReader(command);
        reader.setCursor(Math.max(0, Math.min(offset, command.length())));
        reader.skipWhitespace();
        if (reader.canRead() && reader.peek() == '/') {
            reader.skip();
        }
        return reader;
    }
}
