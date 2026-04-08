package wings.v.core;

import android.text.TextUtils;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.CouplingBetweenObjects",
        "PMD.GodClass",
        "PMD.TooManyMethods",
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.NPathComplexity",
        "PMD.AvoidDeeplyNestedIfStmts",
        "PMD.AvoidLiteralsInIfCondition",
        "PMD.ShortVariable",
        "PMD.LooseCoupling",
        "PMD.AvoidBranchingStatementAsLastInLoop",
        "PMD.UseUnderscoresInNumericLiterals",
        "PMD.OnlyOneReturn",
    }
)
public final class XraySubscriptionParser {

    private static final Pattern VLESS_PATTERN = Pattern.compile("vless://[^\\s\"']+");

    private XraySubscriptionParser() {}

    public static List<String> parseLinks(String rawText) {
        final LinkedHashSet<String> links = new LinkedHashSet<>();
        collectLinks(rawText, links, true);
        return new ArrayList<>(links);
    }

    private static void collectLinks(
        final String rawText,
        final LinkedHashSet<String> links,
        final boolean allowBase64Fallback
    ) {
        final String normalized = rawText == null ? "" : rawText.trim();
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        final Matcher matcher = VLESS_PATTERN.matcher(normalized);
        while (matcher.find()) {
            final String match = matcher.group();
            if (!TextUtils.isEmpty(match)) {
                links.add(match.trim());
            }
        }
        if (!links.isEmpty()) {
            return;
        }
        if (looksLikeJson(normalized)) {
            parseJsonLinks(normalized, links);
            if (!links.isEmpty()) {
                return;
            }
        }
        if (!allowBase64Fallback) {
            return;
        }
        try {
            final byte[] decoded = Base64.decode(normalized, Base64.DEFAULT);
            final String decodedText = new String(decoded, StandardCharsets.UTF_8);
            if (!TextUtils.equals(decodedText.trim(), normalized)) {
                collectLinks(decodedText, links, false);
            }
        } catch (Exception ignored) {}
    }

    private static boolean looksLikeJson(final String rawText) {
        if (TextUtils.isEmpty(rawText)) {
            return false;
        }
        final char first = rawText.charAt(0);
        return first == '{' || first == '[';
    }

    private static void parseJsonLinks(final String rawJson, final LinkedHashSet<String> links) {
        try {
            if (rawJson.trim().startsWith("[")) {
                collectJsonValue(new JSONArray(rawJson), links);
            } else {
                collectJsonValue(new JSONObject(rawJson), links);
            }
        } catch (Exception ignored) {}
    }

    private static void collectJsonValue(final Object value, final LinkedHashSet<String> links) {
        if (value == null) {
            return;
        }
        if (value instanceof JSONObject) {
            final JSONObject object = (JSONObject) value;
            final JSONArray names = object.names();
            if (names == null) {
                return;
            }
            for (int index = 0; index < names.length(); index++) {
                collectJsonValue(object.opt(names.optString(index)), links);
            }
            return;
        }
        if (value instanceof JSONArray) {
            final JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                collectJsonValue(array.opt(index), links);
            }
            return;
        }
        if (value instanceof String) {
            collectLinks((String) value, links, false);
        }
    }
}
