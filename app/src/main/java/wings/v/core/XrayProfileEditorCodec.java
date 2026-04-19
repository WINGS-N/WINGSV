package wings.v.core;

import android.text.TextUtils;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import wings.v.xray.XrayBridge;

@SuppressWarnings({ "PMD.CyclomaticComplexity", "PMD.CognitiveComplexity" })
public final class XrayProfileEditorCodec {

    private XrayProfileEditorCodec() {}

    public static String toEditableJson(XrayProfile profile) throws Exception {
        String rawPayload = profile == null || profile.rawLink == null ? "" : profile.rawLink.trim();
        if (TextUtils.isEmpty(rawPayload)) {
            throw new IllegalArgumentException("Xray профиль пуст");
        }
        JSONObject outbound = extractPrimaryOutboundObject(rawPayload);
        return outbound.toString(2);
    }

    public static String toEditableVless(XrayProfile profile) throws Exception {
        String rawPayload = profile == null || profile.rawLink == null ? "" : profile.rawLink.trim();
        if (TextUtils.isEmpty(rawPayload)) {
            throw new IllegalArgumentException("Xray профиль пуст");
        }
        if (rawPayload.startsWith("vless://")) {
            return rawPayload;
        }
        JSONObject outbound = extractPrimaryOutboundObject(rawPayload);
        JSONObject xrayContainer = new JSONObject();
        xrayContainer.put("outbounds", new JSONArray().put(outbound));
        String links = XrayBridge.convertXrayJsonToShareLinks(xrayContainer.toString());
        String firstLink = firstNonEmptyLine(links);
        if (!firstLink.startsWith("vless://")) {
            throw new IllegalStateException("Не удалось получить VLESS ссылку из JSON профиля");
        }
        return firstLink;
    }

    public static XrayProfile parseJsonProfile(XrayProfile existingProfile, String rawJson) throws Exception {
        if (existingProfile == null) {
            throw new IllegalArgumentException("Профиль не найден");
        }
        JSONObject outbound = extractPrimaryOutboundObject(rawJson);
        Endpoint endpoint = extractEndpoint(outbound);
        String title = extractJsonTitle(outbound, endpoint, existingProfile.title);
        return new XrayProfile(
            existingProfile.id,
            title,
            outbound.toString(2),
            existingProfile.subscriptionId,
            existingProfile.subscriptionTitle,
            endpoint.address,
            endpoint.port
        );
    }

    public static XrayProfile parseVlessProfile(XrayProfile existingProfile, String rawLink) throws Exception {
        if (existingProfile == null) {
            throw new IllegalArgumentException("Профиль не найден");
        }
        String normalized = rawLink == null ? "" : rawLink.trim();
        if (!normalized.startsWith("vless://")) {
            throw new IllegalArgumentException("Ожидается vless:// ссылка");
        }
        XrayProfile parsed = VlessLinkParser.parseProfile(
            normalized,
            existingProfile.subscriptionId,
            existingProfile.subscriptionTitle
        );
        if (parsed == null) {
            throw new IllegalStateException("Не удалось разобрать VLESS ссылку");
        }
        return new XrayProfile(
            existingProfile.id,
            parsed.title,
            normalized,
            existingProfile.subscriptionId,
            existingProfile.subscriptionTitle,
            parsed.address,
            parsed.port
        );
    }

    public static boolean looksLikeJsonPayload(String rawPayload) {
        String normalized = rawPayload == null ? "" : rawPayload.trim();
        return normalized.startsWith("{") || normalized.startsWith("[");
    }

    private static JSONObject extractPrimaryOutboundObject(String rawPayload) throws Exception {
        String normalized = rawPayload == null ? "" : rawPayload.trim();
        if (TextUtils.isEmpty(normalized)) {
            throw new IllegalArgumentException("Пустой JSON");
        }
        JSONObject container;
        if (looksLikeJsonPayload(normalized)) {
            container = normalized.startsWith("[")
                ? new JSONObject().put("outbounds", new JSONArray(normalized))
                : new JSONObject(normalized);
        } else {
            container = new JSONObject(XrayBridge.convertShareLinkToOutboundJson(normalized));
        }
        JSONObject outbound = extractPrimaryOutbound(container);
        if (outbound == null) {
            throw new IllegalStateException("Не удалось получить outbound из профиля");
        }
        return new JSONObject(outbound.toString());
    }

    private static JSONObject extractPrimaryOutbound(JSONObject configObject) {
        if (configObject == null) {
            return null;
        }
        if (configObject.has("protocol")) {
            return configObject;
        }
        JSONArray outbounds = configObject.optJSONArray("outbounds");
        if (outbounds == null || outbounds.length() == 0) {
            return null;
        }
        for (int index = 0; index < outbounds.length(); index++) {
            JSONObject outbound = outbounds.optJSONObject(index);
            if (outbound != null && TextUtils.equals("proxy", outbound.optString("tag"))) {
                return outbound;
            }
        }
        for (int index = 0; index < outbounds.length(); index++) {
            JSONObject outbound = outbounds.optJSONObject(index);
            if (outbound == null || isInternalProtocol(outbound.optString("protocol"))) {
                continue;
            }
            return outbound;
        }
        return outbounds.optJSONObject(0);
    }

    private static boolean isInternalProtocol(String protocol) {
        String normalized = protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
        return "dns".equals(normalized) || "freedom".equals(normalized) || "blackhole".equals(normalized);
    }

    private static Endpoint extractEndpoint(JSONObject outbound) {
        if (outbound == null) {
            return Endpoint.EMPTY;
        }
        JSONObject settings = outbound.optJSONObject("settings");
        if (settings == null) {
            return Endpoint.EMPTY;
        }
        Endpoint vnextEndpoint = extractArrayEndpoint(settings.optJSONArray("vnext"));
        if (!vnextEndpoint.isEmpty()) {
            return vnextEndpoint;
        }
        Endpoint serversEndpoint = extractArrayEndpoint(settings.optJSONArray("servers"));
        if (!serversEndpoint.isEmpty()) {
            return serversEndpoint;
        }
        String address = trim(settings.optString("address"));
        int port = settings.optInt("port");
        return new Endpoint(address, port);
    }

    private static Endpoint extractArrayEndpoint(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Endpoint.EMPTY;
        }
        JSONObject object = array.optJSONObject(0);
        if (object == null) {
            return Endpoint.EMPTY;
        }
        return new Endpoint(trim(object.optString("address")), object.optInt("port"));
    }

    private static String extractJsonTitle(JSONObject outbound, Endpoint endpoint, String fallbackTitle) {
        if (!endpoint.isEmpty()) {
            return endpoint.displayValue();
        }
        String tag = outbound == null ? "" : trim(outbound.optString("tag"));
        if (!TextUtils.isEmpty(tag)) {
            return tag;
        }
        String protocol = outbound == null ? "" : trim(outbound.optString("protocol"));
        if (!TextUtils.isEmpty(protocol)) {
            return protocol.toUpperCase(Locale.ROOT);
        }
        return TextUtils.isEmpty(fallbackTitle) ? "Xray" : fallbackTitle;
    }

    private static String firstNonEmptyLine(String rawValue) {
        if (TextUtils.isEmpty(rawValue)) {
            return "";
        }
        String[] lines = rawValue.split("\\r?\\n");
        for (String line : lines) {
            String normalized = trim(line);
            if (!TextUtils.isEmpty(normalized)) {
                return normalized;
            }
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Endpoint {

        static final Endpoint EMPTY = new Endpoint("", 0);

        final String address;
        final int port;

        Endpoint(String address, int port) {
            this.address = trim(address);
            this.port = Math.max(0, port);
        }

        boolean isEmpty() {
            return TextUtils.isEmpty(address) || port <= 0;
        }

        String displayValue() {
            return isEmpty() ? "" : address + ":" + port;
        }
    }
}
