package wings.v.core;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings({ "PMD.CyclomaticComplexity", "PMD.CognitiveComplexity" })
public final class XrayProfileEditorText {

    private static final int COLOR_JSON_KEY = 0xFF4B7BEC;
    private static final int COLOR_JSON_STRING = 0xFF2B8A3E;
    private static final int COLOR_JSON_NUMBER = 0xFFD97706;
    private static final int COLOR_JSON_LITERAL = 0xFF7B2CBF;
    private static final int COLOR_VLESS_SCHEME = 0xFF7B2CBF;
    private static final int COLOR_VLESS_AUTHORITY = 0xFF2B8A3E;
    private static final int COLOR_VLESS_QUERY_KEY = 0xFF4B7BEC;
    private static final int COLOR_VLESS_QUERY_VALUE = 0xFFD97706;
    private static final int COLOR_VLESS_FRAGMENT = 0xFFB02A37;

    private XrayProfileEditorText() {}

    public static String buildLineNumbers(CharSequence text) {
        int resolvedLineCount = countTextLines(text);
        StringBuilder builder = new StringBuilder();
        for (int line = 1; line <= resolvedLineCount; line++) {
            if (line > 1) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    public static int countTextLines(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 1;
        }
        int lineCount = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lineCount++;
            }
        }
        return Math.max(1, lineCount);
    }

    public static String buildVisualLineNumbers(CharSequence text, Layout layout) {
        if (layout == null) {
            return buildLineNumbers(text);
        }
        int visualLineCount = Math.max(1, layout.getLineCount());
        StringBuilder builder = new StringBuilder();
        int previousSourceLineNumber = -1;
        for (int visualLine = 0; visualLine < visualLineCount; visualLine++) {
            if (visualLine > 0) {
                builder.append('\n');
            }
            int lineStart = layout.getLineStart(visualLine);
            int sourceLineNumber = 1 + countNewlines(text, 0, lineStart);
            if (sourceLineNumber != previousSourceLineNumber) {
                builder.append(sourceLineNumber);
            }
            previousSourceLineNumber = sourceLineNumber;
        }
        return builder.toString();
    }

    public static boolean isValidJson(String value) {
        String normalized = value == null ? "" : value.trim();
        if (TextUtils.isEmpty(normalized)) {
            return false;
        }
        try {
            if (normalized.startsWith("[")) {
                new JSONArray(normalized);
            } else {
                new JSONObject(normalized);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void applyJsonHighlighting(Editable editable) {
        if (editable == null) {
            return;
        }
        clearHighlighting(editable);
        int length = editable.length();
        int index = 0;
        while (index < length) {
            char current = editable.charAt(index);
            if (current == '"') {
                int end = scanJsonString(editable, index);
                boolean key = isJsonKey(editable, end + 1);
                applySpan(editable, index, end + 1, key ? COLOR_JSON_KEY : COLOR_JSON_STRING, key);
                index = end + 1;
                continue;
            }
            if (current == '-' || Character.isDigit(current)) {
                int end = scanJsonNumber(editable, index);
                applySpan(editable, index, end, COLOR_JSON_NUMBER, false);
                index = end;
                continue;
            }
            if (matchKeyword(editable, index, "true")) {
                applySpan(editable, index, index + 4, COLOR_JSON_LITERAL, true);
                index += 4;
                continue;
            }
            if (matchKeyword(editable, index, "false")) {
                applySpan(editable, index, index + 5, COLOR_JSON_LITERAL, true);
                index += 5;
                continue;
            }
            if (matchKeyword(editable, index, "null")) {
                applySpan(editable, index, index + 4, COLOR_JSON_LITERAL, true);
                index += 4;
                continue;
            }
            index++;
        }
    }

    public static void applyVlessHighlighting(Editable editable) {
        if (editable == null) {
            return;
        }
        clearHighlighting(editable);
        String text = editable.toString();
        if (TextUtils.isEmpty(text)) {
            return;
        }

        int schemeEnd = text.indexOf("://");
        if (schemeEnd > 0) {
            applySpan(editable, 0, schemeEnd + 3, COLOR_VLESS_SCHEME, true);
        }

        int authorityStart = schemeEnd >= 0 ? schemeEnd + 3 : 0;
        int queryStart = text.indexOf('?', authorityStart);
        int fragmentStart = text.indexOf('#', authorityStart);
        int authorityEnd = text.length();
        if (queryStart >= 0) {
            authorityEnd = Math.min(authorityEnd, queryStart);
        }
        if (fragmentStart >= 0) {
            authorityEnd = Math.min(authorityEnd, fragmentStart);
        }
        if (authorityEnd > authorityStart) {
            int atIndex = text.indexOf('@', authorityStart);
            if (atIndex >= authorityStart && atIndex < authorityEnd) {
                applySpan(editable, authorityStart, atIndex + 1, COLOR_VLESS_AUTHORITY, true);
                applySpan(editable, atIndex + 1, authorityEnd, COLOR_JSON_STRING, false);
            } else {
                applySpan(editable, authorityStart, authorityEnd, COLOR_JSON_STRING, false);
            }
        }

        if (queryStart >= 0) {
            int queryEnd = fragmentStart >= 0 ? fragmentStart : text.length();
            int pairStart = queryStart + 1;
            while (pairStart < queryEnd) {
                int pairEnd = indexOfOrEnd(text, '&', pairStart, queryEnd);
                int equalsIndex = text.indexOf('=', pairStart);
                if (equalsIndex >= pairStart && equalsIndex < pairEnd) {
                    applySpan(editable, pairStart, equalsIndex, COLOR_VLESS_QUERY_KEY, true);
                    if (equalsIndex + 1 < pairEnd) {
                        applySpan(editable, equalsIndex + 1, pairEnd, COLOR_VLESS_QUERY_VALUE, false);
                    }
                } else if (pairStart < pairEnd) {
                    applySpan(editable, pairStart, pairEnd, COLOR_VLESS_QUERY_KEY, true);
                }
                pairStart = pairEnd + 1;
            }
        }

        if (fragmentStart >= 0 && fragmentStart + 1 < text.length()) {
            applySpan(editable, fragmentStart, text.length(), COLOR_VLESS_FRAGMENT, false);
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

    private static void applySpan(Editable editable, int start, int end, int color, boolean bold) {
        editable.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            editable.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static int scanJsonString(CharSequence text, int start) {
        int index = start + 1;
        boolean escaped = false;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return index;
            }
            index++;
        }
        return text.length() - 1;
    }

    private static int scanJsonNumber(CharSequence text, int start) {
        int index = start;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (
                !(Character.isDigit(current) ||
                    current == '-' ||
                    current == '+' ||
                    current == '.' ||
                    current == 'e' ||
                    current == 'E')
            ) {
                break;
            }
            index++;
        }
        return index;
    }

    private static boolean isJsonKey(CharSequence text, int start) {
        int index = start;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index < text.length() && text.charAt(index) == ':';
    }

    private static boolean matchKeyword(CharSequence text, int start, String keyword) {
        int end = start + keyword.length();
        if (end > text.length()) {
            return false;
        }
        if (!keyword.contentEquals(text.subSequence(start, end))) {
            return false;
        }
        return (
            (start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1))) &&
            (end == text.length() || !Character.isLetterOrDigit(text.charAt(end)))
        );
    }

    private static int countNewlines(CharSequence text, int start, int end) {
        if (text == null || text.length() == 0 || start < 0 || end <= start) {
            return 0;
        }
        int count = 0;
        int safeEnd = Math.min(end, text.length());
        for (int index = Math.max(0, start); index < safeEnd; index++) {
            if (text.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static int indexOfOrEnd(String text, char target, int start, int end) {
        int index = text.indexOf(target, start);
        if (index < 0 || index > end) {
            return end;
        }
        return index;
    }
}
