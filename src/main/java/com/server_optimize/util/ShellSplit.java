package com.server_optimize.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Quote-aware splitter for the shell-style command chaining operators
 * ";" / "&amp;&amp;" / "||" (see {@link BashSyntax} for the semantics).
 * <p>
 * This class is deliberately free of any Minecraft class references: the
 * client-side command-completion hook uses it while parsing the chat input,
 * where loading server-side classes would be wrong. {@link BashSyntax} (the
 * server-side executor) delegates its splitting to this class, so the syntax
 * is defined in exactly one place.
 */
public final class ShellSplit {

    /** One split-out command plus the operator that precedes it
     *  (';' / '&amp;' / '|'; the first segment always has op ';') and the
     *  segment's start offset (index of its first non-blank character) in the
     *  original string. */
    public record Segment(char op, String text, int start) {}

    private ShellSplit() {
    }

    /** True when the string contains a top-level (unquoted) ;, &amp;&amp; or ||. */
    public static boolean hasOperators(String s) {
        return s != null && split(s).size() > 1;
    }

    /**
     * Splits on top-level (outside double quotes) ";" / "&amp;&amp;" / "||".
     * Empty segments ("a; ; b", "a;;b") are dropped. Quote characters are
     * kept in the segment text so the final command string is byte-identical
     * to what the caller passed (brigadier later unquotes arguments). Each
     * segment records the index of its first non-blank character in {@code s}.
     */
    public static List<Segment> split(String s) {
        List<Segment> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        char prevOp = ';';
        int curStart = -1;
        boolean inQuote = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                if (curStart < 0 && !Character.isWhitespace(c)) {
                    curStart = i;
                }
                cur.append(c);
                escaped = false;
                continue;
            }
            if (inQuote) {
                cur.append(c);
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"') {
                if (curStart < 0) {
                    curStart = i;
                }
                inQuote = true;
                cur.append(c);
                continue;
            }
            if (c == ';') {
                flush(out, cur, prevOp, curStart);
                prevOp = ';';
                curStart = -1;
                continue;
            }
            if (c == '&' && i + 1 < s.length() && s.charAt(i + 1) == '&') {
                flush(out, cur, prevOp, curStart);
                prevOp = '&';
                curStart = -1;
                i++;
                continue;
            }
            if (c == '|' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                flush(out, cur, prevOp, curStart);
                prevOp = '|';
                curStart = -1;
                i++;
                continue;
            }
            if (curStart < 0 && !Character.isWhitespace(c)) {
                curStart = i;
            }
            cur.append(c);
        }
        flush(out, cur, prevOp, curStart);
        return out;
    }

    /**
     * Index of the first non-blank character of the LAST segment, or -1 when
     * the string has no top-level operator (nothing to do) or the last
     * segment is empty.
     */
    public static int lastSegmentStart(String s) {
        List<Segment> segments = split(s);
        if (segments.size() < 2) {
            return -1;
        }
        return segments.get(segments.size() - 1).start();
    }

    /**
     * Marks every character that belongs to a top-level (unquoted) operator
     * (";" - one character, "&amp;&amp;" / "||" - two characters). Used by the
     * client to paint the operators yellow inside the command input box.
     */
    public static boolean[] operatorMask(String s) {
        boolean[] mask = new boolean[s == null ? 0 : s.length()];
        if (s == null || s.isEmpty()) {
            return mask;
        }
        boolean inQuote = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"') {
                inQuote = true;
                continue;
            }
            if (c == ';') {
                mask[i] = true;
                continue;
            }
            if (c == '&' && i + 1 < s.length() && s.charAt(i + 1) == '&') {
                mask[i] = true;
                mask[i + 1] = true;
                i++;
                continue;
            }
            if (c == '|' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                mask[i] = true;
                mask[i + 1] = true;
                i++;
            }
        }
        return mask;
    }

    /**
     * Start offset of the segment that CONTAINS {@code caret} (the last
     * segment starting at or before it), or -1 when there is no such segment
     * beyond the first one.
     * <p>
     * Brigadier's completion walks the context up to the caret and throws
     * {@code IllegalStateException("Can't find node before cursor")} when the
     * caret lies BEFORE the parsed range - so a completion hook must only
     * parse the segment the caret is actually in, never simply the last one.
     */
    public static int segmentStartFor(String s, int caret) {
        int best = -1;
        for (Segment segment : split(s)) {
            if (segment.start() > caret) {
                break;
            }
            best = segment.start();
        }
        return best;
    }

    /**
     * True when {@code index} sits inside an unclosed double-quoted section -
     * used to keep the operator suggestions out of quoted arguments.
     */
    public static boolean insideQuote(String s, int index) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        boolean inQuote = false;
        boolean escaped = false;
        int limit = Math.min(index, s.length());
        for (int i = 0; i < limit; i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                if (inQuote) {
                    escaped = true;
                }
                continue;
            }
            if (c == '"') {
                inQuote = !inQuote;
            }
        }
        return inQuote;
    }

    private static void flush(List<Segment> out, StringBuilder cur, char op, int start) {
        String text = cur.toString().trim();
        cur.setLength(0);
        if (!text.isEmpty()) {
            out.add(new Segment(op, text, Math.max(0, start)));
        }
    }
}