package com.server_optimize.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import com.server_optimize.config.ModConfig;
import com.server_optimize.util.OperatorSuggestions;
import com.server_optimize.util.ShellSplit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

/**
 * Client side of the shell-style command chaining ([command] bashSyntax):
 * makes the chat input's command parsing, syntax highlighting and TAB
 * completion understand ";", "&amp;&amp;" and "||".
 * <p>
 * Vanilla parses the whole input line as ONE command. With chaining the line
 * is e.g. "serveroptimize gc; say hi": the whole-line parse stops after the
 * first command, everything behind the operator stays "unparsed" - it is
 * rendered in RED (CommandSuggestions.UNPARSED_STYLE is the leftover text)
 * and tab completion offers nothing.
 * <p>
 * Fix: when (and only when) the whole line fails to parse as a single
 * command, the line contains a top-level operator, re-parse starting at the
 * LAST segment. The parse then runs on the SAME string (the reader is a
 * StringReader over the full input with its cursor moved to the segment), so
 * every range stays an absolute index into the input:
 * <ul>
 *   <li>the text before the segment (earlier commands + the operator) is
 *       rendered with the normal literal style instead of red;</li>
 *   <li>the segment's own arguments are highlighted normally, and only an
 *       INCOMPLETE last segment still shows red - which is correct;</li>
 *   <li>{@code getCompletionSuggestions(currentParse, cursor)} now completes
 *       the last segment at the right positions, locally, with no server
 *       round trip.</li>
 * </ul>
 * This mirrors the server-side execution rule exactly (a line is only split
 * when the whole line is not a single valid command), so what the client
 * shows as valid is what the server will actually run. The server-side
 * suggestion hook is still there for arguments that require server data.
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {

    /**
     * Input line currently recognised as a chained command (whole line invalid
     * as one command + a top-level operator). Set once per edit by the parse
     * hook, read every frame by the formatText hook.
     */
    private static volatile String chainedLine;

    /** The command field - needed to know where the caret is: only the segment
     *  the caret sits in may be parsed for completion (see the parse hook). */
    @Shadow
    @Final
    EditBox input;

    @WrapOperation(
        method = "updateCommandInfo",
        // require = 0: if the target ever moves, the client must still start
        // (expect = 1 makes Mixin log the miss instead).
        require = 0,
        expect = 1,
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/brigadier/CommandDispatcher;parse(Lcom/mojang/brigadier/StringReader;Ljava/lang/Object;)Lcom/mojang/brigadier/ParseResults;"))
    private ParseResults<ClientSuggestionProvider> serverOptimize$parseChainedCommand(
        CommandDispatcher<ClientSuggestionProvider> dispatcher,
        StringReader reader,
        Object source,
        Operation<ParseResults<ClientSuggestionProvider>> original) {
        ParseResults<ClientSuggestionProvider> vanilla = original.call(dispatcher, reader, source);
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg != null && !cfg.command.bashSyntax) {
            chainedLine = null;
            return vanilla;
        }
        String text = reader.getString();
        int caret = this.input.getCursorPosition();
        // The whole line must be invalid as a single command - that is exactly
        // the condition under which the server splits it too. The input box
        // highlighting (formatText) follows the LINE, so it is recorded here
        // independently of the caret.
        boolean wholeInvalid = !(vanilla.getExceptions().isEmpty() && !vanilla.getReader().canRead());
        chainedLine = wholeInvalid && ShellSplit.hasOperators(text) ? text : null;
        if (!wholeInvalid) {
            return vanilla;
        }
        // Only the segment the CARET is in may be parsed: brigadier's
        // completion walks the context up to the caret, and a parse whose
        // range starts behind the caret makes it throw
        // IllegalStateException("Can't find node before cursor") - which would
        // abort the whole key/char event and stop the command from ever being
        // executed. The first segment is left to vanilla (it starts at 0 and
        // the whole line already covers it).
        int offset = ShellSplit.segmentStartFor(text, caret);
        if (offset <= 0) {
            return vanilla;
        }
        try {
            // Parse the caret's segment, but keep the FULL string so all ranges
            // stay absolute (highlighting and suggestion ranges then need no
            // shifting at all).
            StringReader segmentReader = new StringReader(text);
            segmentReader.setCursor(offset);
            segmentReader.skipWhitespace();
            if (segmentReader.canRead() && segmentReader.peek() == '/') {
                segmentReader.skip();
            }
            @SuppressWarnings("unchecked")
            ClientSuggestionProvider typedSource = (ClientSuggestionProvider) source;
            ParseResults<ClientSuggestionProvider> segment =
                dispatcher.parse(segmentReader, typedSource);
            // Only take over when the segment fares better than the whole line
            // (e.g. "serveroptimize gc; say hi" - the segment "say hi" parses,
            // while the whole line does not).
            if (segment.getExceptions().isEmpty()
                || segment.getExceptions().size() < vanilla.getExceptions().size()) {
                return segment;
            }
        } catch (Exception ignored) {
            // Fall through to vanilla behaviour on any surprise.
        }
        return vanilla;
    }

    /**
     * Input-box highlighting: paints the chaining operators (";", "&amp;&amp;",
     * "||") yellow inside the command field, the same way vanilla paints
     * unparsed text red. Only applied while the line is actually treated as a
     * chained command (see the parse hook above), so a ";" that is part of a
     * normal argument - "/say a;b" parses as one greedy argument - keeps its
     * vanilla colour.
     * <p>
     * The wrapped formatText returns the formatted input line; this walks its
     * characters in order (the sequence always covers the whole text, split
     * into styled fragments) and recolours the operator positions.
     */
    @WrapMethod(method = "formatText", require = 0, expect = 1)
    private static FormattedCharSequence serverOptimize$yellowOperatorsInInput(
        ParseResults<ClientSuggestionProvider> parse,
        String text,
        int cursor,
        Operation<FormattedCharSequence> original) {
        FormattedCharSequence base = original.call(parse, text, cursor);
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg != null && !cfg.command.bashSyntax) {
            return base;
        }
        if (chainedLine == null || !chainedLine.equals(text)) {
            return base;
        }
        boolean[] mask = ShellSplit.operatorMask(text);
        boolean any = false;
        for (boolean marked : mask) {
            if (marked) {
                any = true;
                break;
            }
        }
        if (!any) {
            return base;
        }
        return sink -> {
            // formatText emits the full text in order; count the characters to
            // map them onto the mask (fragment indices are not global).
            int[] position = {0};
            return base.accept((index, style, codePoint) -> {
                int at = position[0]++;
                Style out = at < mask.length && mask[at]
                    ? style.withColor(ChatFormatting.YELLOW)
                    : style;
                return sink.accept(index, out, codePoint);
            });
        };
    }

    /**
     * Adds the chaining operators themselves to the suggestion list: when the
     * caret sits behind whitespace (a fresh token just started, e.g.
     * "/say hello " + TAB, or a chained "... ; "), ";" / "&amp;&amp;" / "||"
     * are offered next to the normal suggestions - locally, from the client's
     * own command tree.
     * <p>
     * The wrapped call also covers suggestions that need server data (the
     * client's ClientSuggestionProvider forwards those to the server), so both
     * paths get the operators.
     */
    @WrapOperation(
        method = "updateCommandInfo",
        require = 0,
        expect = 1,
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/brigadier/CommandDispatcher;getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;I)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Suggestions> serverOptimize$addOperatorSuggestions(
        CommandDispatcher<ClientSuggestionProvider> dispatcher,
        ParseResults<ClientSuggestionProvider> parse,
        int cursor,
        Operation<CompletableFuture<Suggestions>> original) {
        // Hard safety net: brigadier's completion walks the parse context up to
        // the caret and throws IllegalStateException("Can't find node before
        // cursor") when the caret lies before the parsed range. That exception
        // escapes into the key/char event handler and stops the input (and with
        // it the command) from working at all, so never let it happen.
        if (cursor < parse.getContext().getRange().getStart()) {
            return Suggestions.empty();
        }
        CompletableFuture<Suggestions> future;
        try {
            future = original.call(dispatcher, parse, cursor);
        } catch (RuntimeException e) {
            return Suggestions.empty();
        }
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg != null && !cfg.command.bashSyntax) {
            return future;
        }
        String text = parse.getReader().getString();
        if (!OperatorSuggestions.atTokenStart(text, cursor)) {
            return future;
        }
        return future.thenApply(suggestions -> OperatorSuggestions.append(suggestions, cursor));
    }
}