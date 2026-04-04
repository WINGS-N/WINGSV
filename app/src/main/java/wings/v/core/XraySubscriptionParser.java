package wings.v.core;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XraySubscriptionParser {
    private static final Pattern VLESS_PATTERN = Pattern.compile("vless://[^\\s\"']+");

    private XraySubscriptionParser() {
    }

    public static List<String> parseLinks(String rawText) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        collectLinks(rawText, links, true);
        return new ArrayList<>(links);
    }

    private static void collectLinks(String rawText, LinkedHashSet<String> links, boolean allowBase64Fallback) {
        String normalized = rawText == null ? "" : rawText.trim();
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        Matcher matcher = VLESS_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String match = matcher.group();
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
            byte[] decoded = Base64.decode(normalized, Base64.DEFAULT);
            String decodedText = new String(decoded, StandardCharsets.UTF_8);
            if (!TextUtils.equals(decodedText.trim(), normalized)) {
                collectLinks(decodedText, links, false);
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean looksLikeJson(String rawText) {
        if (TextUtils.isEmpty(rawText)) {
            return false;
        }
        char first = rawText.charAt(0);
        return first == '{' || first == '[';
    }

    private static void parseJsonLinks(String rawJson, LinkedHashSet<String> links) {
        try {
            if (rawJson.trim().startsWith("[")) {
                collectJsonValue(new JSONArray(rawJson), links);
            } else {
                collectJsonValue(new JSONObject(rawJson), links);
            }
        } catch (Exception ignored) {
        }
    }

    private static void collectJsonValue(Object value, LinkedHashSet<String> links) {
        if (value == null) {
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) {
                return;
            }
            for (int index = 0; index < names.length(); index++) {
                collectJsonValue(object.opt(names.optString(index)), links);
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
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
