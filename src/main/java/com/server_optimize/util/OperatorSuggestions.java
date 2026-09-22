package com.server_optimize.util;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;

import java.util.ArrayList;
import java.util.List;

/**
 * Suggestion entries for the shell-style chaining operators. Offered when the
 * cursor sits right behind whitespace, i.e. a new token has just started -
 * so typing "/say hello " + TAB shows ";" / "&amp;&amp;" / "||" as well.
 * <p>
 * The text carries the trailing space because the vanilla suggestion list
 * inserts exactly {@code Suggestion.getText()} (CommandSuggestions
 * .SuggestionsList.useSuggestion), so the user can continue typing the next
 * command right away. Pure brigadier: usable from both the client and the
 * server completion paths.
 */
public final class OperatorSuggestions {

    /** Operator entries. Bare operators - like every vanilla suggestion the
     *  text is a plain token without a trailing space, so the popup rows have
     *  exactly the same width/sorting/insertion as native command
     *  suggestions. */
    private static final String[] OPERATORS = {";", "&&", "||"};

    private OperatorSuggestions() {
    }

    /**
     * Returns {@code suggestions} with the operator entries appended at
     * {@code cursor} (duplicates are skipped).
     * <p>
     * When the base result is empty its range is brigadier's
     * {@code Suggestions.EMPTY} - {@code StringRange.at(0)} - and the client
     * anchors the whole suggestion popup at {@code input.getScreenX(range
     * .getStart())}, which would park it at the very beginning of the command.
     * An empty base therefore gets a range at the caret, so the popup shows up
     * above the caret as usual.
     */
    public static Suggestions append(Suggestions suggestions, int cursor) {
        List<Suggestion> list = new ArrayList<>(suggestions.getList());
        StringRange at = StringRange.at(cursor);
        for (String operator : OPERATORS) {
            boolean present = false;
            for (Suggestion existing : list) {
                if (operator.equals(existing.getText())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                list.add(new Suggestion(at, operator));
            }
        }
        StringRange range = list.isEmpty() || suggestions.getList().isEmpty()
            ? at
            : suggestions.getRange();
        return new Suggestions(range, list);
    }

    /**
     * True when the caret is at the start of a fresh token: the character
     * before it is whitespace (the "前面存在空格" case), nothing but blanks
     * follows, and the caret is not inside a quoted argument.
     */
    public static boolean atTokenStart(String text, int cursor) {
        if (text == null || cursor <= 0 || cursor > text.length()) {
            return false;
        }
        if (!Character.isWhitespace(text.charAt(cursor - 1))) {
            return false;
        }
        for (int i = cursor; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return !ShellSplit.insideQuote(text, cursor);
    }
}