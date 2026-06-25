package wings.v.core;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

/**
 * Lightweight syntax highlighting for a wg-quick / awg-quick INI style config
 * text. Section headers like [Interface] and [Peer] are coloured, the key part
 * of a "Key = Value" line is coloured as a key and the value as a string,
 * comment lines starting with # or ; are dimmed. Shared by the WireGuard and
 * AmneziaWG profile text editors. Validation is delegated to the matching codec
 * (WireGuardProfileEditorCodec / AmneziaProfileEditorCodec) since the parsers
 * differ between the two backends.
 */
@SuppressWarnings({ "PMD.CommentRequired", "PMD.CyclomaticComplexity", "PMD.CognitiveComplexity" })
public final class WgQuickEditorText {

    private static final int COLOR_SECTION = 0xFF7B2CBF;
    private static final int COLOR_KEY = 0xFF4B7BEC;
    private static final int COLOR_VALUE = 0xFF2B8A3E;
    private static final int COLOR_COMMENT = 0xFF9AA0A6;

    private WgQuickEditorText() {}

    public static void applyHighlighting(Editable editable) {
        if (editable == null) {
            return;
        }
        clearHighlighting(editable);
        String text = editable.toString();
        int length = text.length();
        int lineStart = 0;
        while (lineStart <= length) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = length;
            }
            highlightLine(editable, text, lineStart, lineEnd);
            lineStart = lineEnd + 1;
        }
    }

    private static void highlightLine(Editable editable, String text, int lineStart, int lineEnd) {
        int contentStart = lineStart;
        while (contentStart < lineEnd && Character.isWhitespace(text.charAt(contentStart))) {
            contentStart++;
        }
        if (contentStart >= lineEnd) {
            return;
        }
        char first = text.charAt(contentStart);
        if (first == '#' || first == ';') {
            applySpan(editable, contentStart, lineEnd, COLOR_COMMENT, false);
            return;
        }
        if (first == '[') {
            applySpan(editable, contentStart, lineEnd, COLOR_SECTION, true);
            return;
        }
        int equalsIndex = text.indexOf('=', contentStart);
        if (equalsIndex >= contentStart && equalsIndex < lineEnd) {
            applySpan(editable, contentStart, equalsIndex, COLOR_KEY, true);
            int valueStart = equalsIndex + 1;
            while (valueStart < lineEnd && text.charAt(valueStart) == ' ') {
                valueStart++;
            }
            if (valueStart < lineEnd) {
                applySpan(editable, valueStart, lineEnd, COLOR_VALUE, false);
            }
        }
    }

    public static void clearHighlighting(Editable editable) {
        if (editable == null) {
            return;
        }
        for (ForegroundColorSpan span : editable.getSpans(0, editable.length(), ForegroundColorSpan.class)) {
            editable.removeSpan(span);
        }
        for (StyleSpan span : editable.getSpans(0, editable.length(), StyleSpan.class)) {
            editable.removeSpan(span);
        }
    }

    public static boolean isBlank(String value) {
        return TextUtils.isEmpty(value == null ? "" : value.trim());
    }

    private static void applySpan(Editable editable, int start, int end, int color, boolean bold) {
        editable.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            editable.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
