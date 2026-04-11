package wings.v.core;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import wings.v.xray.XrayBridge;

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

    private static final Pattern SHARE_LINK_PATTERN = Pattern.compile(
        "(?:vless|vmess|socks|ss|trojan|hysteria2|hy2)://[^\\s\"']+",
        Pattern.CASE_INSENSITIVE
    );
    private static final String PRIMARY_PROXY_TAG = "proxy";

    private XraySubscriptionParser() {}

    public static List<String> parseLinks(String rawText) {
        final LinkedHashSet<String> links = new LinkedHashSet<>();
        collectShareLinks(rawText, links, true);
        return new ArrayList<>(links);
    }

    public static List<XrayProfile> parseProfiles(
        final String rawText,
        final String subscriptionId,
        final String subscriptionTitle
    ) {
        final ArrayList<XrayProfile> profiles = new ArrayList<>();
        final LinkedHashSet<String> dedupKeys = new LinkedHashSet<>();
        collectProfiles(rawText, profiles, dedupKeys, true, subscriptionId, subscriptionTitle);
        return profiles;
    }

    private static void collectProfiles(
        final String rawText,
        final List<XrayProfile> profiles,
        final LinkedHashSet<String> dedupKeys,
        final boolean allowBase64Fallback,
        final String subscriptionId,
        final String subscriptionTitle
    ) {
        final String normalized = rawText == null ? "" : rawText.trim();
        if (TextUtils.isEmpty(normalized)) {
            return;
        }

        final LinkedHashSet<String> links = new LinkedHashSet<>();
        collectShareLinks(normalized, links, false);
        if (!links.isEmpty()) {
            for (String link : links) {
                addProfile(parseShareLinkProfile(link, subscriptionId, subscriptionTitle), profiles, dedupKeys);
            }
            if (!profiles.isEmpty()) {
                return;
            }
        }

        if (looksLikeJson(normalized)) {
            parseJsonProfiles(normalized, profiles, dedupKeys, subscriptionId, subscriptionTitle);
            if (!profiles.isEmpty()) {
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
                collectProfiles(decodedText, profiles, dedupKeys, false, subscriptionId, subscriptionTitle);
            }
        } catch (Exception ignored) {}
    }

    private static void collectShareLinks(
        final String rawText,
        final LinkedHashSet<String> links,
        final boolean allowBase64Fallback
    ) {
        final String normalized = rawText == null ? "" : rawText.trim();
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        final Matcher matcher = SHARE_LINK_PATTERN.matcher(normalized);
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
                collectShareLinks(decodedText, links, false);
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
            collectShareLinks((String) value, links, false);
        }
    }

    private static void parseJsonProfiles(
        final String rawJson,
        final List<XrayProfile> profiles,
        final LinkedHashSet<String> dedupKeys,
        final String subscriptionId,
        final String subscriptionTitle
    ) {
        try {
            final Object root = rawJson.trim().startsWith("[") ? new JSONArray(rawJson) : new JSONObject(rawJson);
            collectJsonProfiles(root, profiles, dedupKeys, subscriptionId, subscriptionTitle);
        } catch (Exception ignored) {}
    }

    private static void collectJsonProfiles(
        final Object value,
        final List<XrayProfile> profiles,
        final LinkedHashSet<String> dedupKeys,
        final String subscriptionId,
        final String subscriptionTitle
    ) {
        if (value == null) {
            return;
        }
        if (value instanceof JSONObject) {
            final JSONObject object = (JSONObject) value;
            final XrayProfile profile = parseJsonConfigProfile(object, subscriptionId, subscriptionTitle);
            if (profile != null) {
                addProfile(profile, profiles, dedupKeys);
                return;
            }
            final JSONArray names = object.names();
            if (names == null) {
                return;
            }
            for (int index = 0; index < names.length(); index++) {
                collectJsonProfiles(
                    object.opt(names.optString(index)),
                    profiles,
                    dedupKeys,
                    subscriptionId,
                    subscriptionTitle
                );
            }
            return;
        }
        if (value instanceof JSONArray) {
            final JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                collectJsonProfiles(array.opt(index), profiles, dedupKeys, subscriptionId, subscriptionTitle);
            }
            return;
        }
        if (value instanceof String) {
            final XrayProfile profile = parseShareLinkProfile((String) value, subscriptionId, subscriptionTitle);
            addProfile(profile, profiles, dedupKeys);
        }
    }

    private static XrayProfile parseJsonConfigProfile(
        final JSONObject configObject,
        final String subscriptionId,
        final String subscriptionTitle
    ) {
        if (configObject == null) {
            return null;
        }
        final JSONObject outbound = extractPrimaryOutbound(configObject);
        if (outbound == null) {
            return null;
        }
        final Endpoint endpoint = extractEndpoint(outbound);
        final String title = extractJsonTitle(configObject, endpoint, outbound);
        return new XrayProfile(
            UUID.randomUUID().toString(),
            title,
            configObject.toString(),
            subscriptionId,
            subscriptionTitle,
            endpoint.address,
            endpoint.port
        );
    }

    private static XrayProfile parseShareLinkProfile(
        final String rawLink,
        final String subscriptionId,
        final String subscriptionTitle
    ) {
        final String normalized = rawLink == null ? "" : rawLink.trim();
        if (TextUtils.isEmpty(normalized)) {
            return null;
        }
        try {
            final JSONObject converted = new JSONObject(XrayBridge.convertShareLinkToOutboundJson(normalized));
            final JSONObject outbound = extractPrimaryOutbound(converted);
            if (outbound == null) {
                return null;
            }
            final Endpoint endpoint = extractEndpoint(outbound);
            final String title = extractShareLinkTitle(normalized, endpoint, outbound);
            return new XrayProfile(
                UUID.randomUUID().toString(),
                title,
                normalized,
                subscriptionId,
                subscriptionTitle,
                endpoint.address,
                endpoint.port
            );
        } catch (Exception ignored) {
            return VlessLinkParser.parseProfile(normalized, subscriptionId, subscriptionTitle);
        }
    }

    private static JSONObject extractPrimaryOutbound(final JSONObject configObject) {
        if (configObject == null) {
            return null;
        }
        if (configObject.has("protocol")) {
            return configObject;
        }
        final JSONArray outbounds = configObject.optJSONArray("outbounds");
        if (outbounds == null || outbounds.length() == 0) {
            return null;
        }
        for (int index = 0; index < outbounds.length(); index++) {
            final JSONObject outbound = outbounds.optJSONObject(index);
            if (outbound != null && TextUtils.equals(PRIMARY_PROXY_TAG, outbound.optString("tag"))) {
                return outbound;
            }
        }
        for (int index = 0; index < outbounds.length(); index++) {
            final JSONObject outbound = outbounds.optJSONObject(index);
            if (outbound == null || isInternalProtocol(outbound.optString("protocol"))) {
                continue;
            }
            return outbound;
        }
        return outbounds.optJSONObject(0);
    }

    private static boolean isInternalProtocol(final String protocol) {
        final String normalized = protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
        return "dns".equals(normalized) || "freedom".equals(normalized) || "blackhole".equals(normalized);
    }

    private static Endpoint extractEndpoint(final JSONObject outbound) {
        if (outbound == null) {
            return Endpoint.EMPTY;
        }
        final JSONObject settings = outbound.optJSONObject("settings");
        if (settings == null) {
            return Endpoint.EMPTY;
        }
        final Endpoint vnextEndpoint = extractArrayEndpoint(settings.optJSONArray("vnext"));
        if (!vnextEndpoint.isEmpty()) {
            return vnextEndpoint;
        }
        final Endpoint serversEndpoint = extractArrayEndpoint(settings.optJSONArray("servers"));
        if (!serversEndpoint.isEmpty()) {
            return serversEndpoint;
        }
        final String address = trim(settings.optString("address"));
        final int port = settings.optInt("port");
        return new Endpoint(address, port);
    }

    private static Endpoint extractArrayEndpoint(final JSONArray array) {
        if (array == null || array.length() == 0) {
            return Endpoint.EMPTY;
        }
        final JSONObject object = array.optJSONObject(0);
        if (object == null) {
            return Endpoint.EMPTY;
        }
        return new Endpoint(trim(object.optString("address")), object.optInt("port"));
    }

    private static String extractShareLinkTitle(
        final String rawLink,
        final Endpoint endpoint,
        final JSONObject outbound
    ) {
        try {
            final Uri uri = Uri.parse(rawLink);
            final String fragment = uri.getEncodedFragment();
            if (!TextUtils.isEmpty(fragment)) {
                return URLDecoder.decode(fragment, StandardCharsets.UTF_8.name());
            }
        } catch (Exception ignored) {}
        if (!endpoint.isEmpty()) {
            return endpoint.displayValue();
        }
        final String protocol = outbound == null ? "" : trim(outbound.optString("protocol"));
        return TextUtils.isEmpty(protocol) ? "Xray" : protocol.toUpperCase(Locale.ROOT);
    }

    private static String extractJsonTitle(
        final JSONObject configObject,
        final Endpoint endpoint,
        final JSONObject outbound
    ) {
        final String remarks = trim(configObject.optString("remarks"));
        if (!TextUtils.isEmpty(remarks)) {
            return remarks;
        }
        if (!endpoint.isEmpty()) {
            return endpoint.displayValue();
        }
        final String tag = outbound == null ? "" : trim(outbound.optString("tag"));
        if (!TextUtils.isEmpty(tag)) {
            return tag;
        }
        final String protocol = outbound == null ? "" : trim(outbound.optString("protocol"));
        return TextUtils.isEmpty(protocol) ? "Xray" : protocol.toUpperCase(Locale.ROOT);
    }

    private static void addProfile(
        final XrayProfile profile,
        final List<XrayProfile> profiles,
        final LinkedHashSet<String> dedupKeys
    ) {
        if (profile == null) {
            return;
        }
        final String dedupKey = profile.stableDedupKey();
        if (dedupKeys.contains(dedupKey)) {
            return;
        }
        dedupKeys.add(dedupKey);
        profiles.add(profile);
    }

    private static String trim(final String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Endpoint {

        static final Endpoint EMPTY = new Endpoint("", 0);

        final String address;
        final int port;

        Endpoint(final String address, final int port) {
            this.address = trim(address);
            this.port = Math.max(port, 0);
        }

        boolean isEmpty() {
            return TextUtils.isEmpty(address) || port <= 0;
        }

        String displayValue() {
            return isEmpty() ? "" : address + ":" + port;
        }
    }
}
