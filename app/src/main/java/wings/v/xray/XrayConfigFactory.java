package wings.v.xray;

import android.content.Context;
import android.text.TextUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import wings.v.core.ByeDpiSettings;
import wings.v.core.ProxySettings;
import wings.v.core.XrayProfile;
import wings.v.core.XrayRoutingRule;
import wings.v.core.XrayRoutingStore;
import wings.v.core.XraySettings;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.SignatureDeclareThrowsException",
        "PMD.AvoidUsingHardCodedIP",
        "PMD.AvoidFileStream",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.CommentDefaultAccessModifier",
        "PMD.AvoidDuplicateLiterals",
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.GodClass",
        "PMD.TooManyMethods",
        "PMD.LooseCoupling",
    }
)
public final class XrayConfigFactory {

    private static final String TUN_TAG = "tun-in";
    private static final String SOCKS_TAG = "socks-in";
    private static final String PROXY_TAG = "proxy";
    private static final String BYEDPI_FRONT_TAG = "byedpi-front";
    private static final String DNS_TAG = "dns-internal";
    private static final String DNS_OUT_TAG = "dns-out";
    private static final String DIRECT_TAG = "direct";
    private static final String BLOCK_TAG = "block";
    private static final int DEFAULT_MTU = 1500;

    private XrayConfigFactory() {}

    public static String buildConfigJson(Context context, ProxySettings settings) throws Exception {
        if (
            settings == null ||
            settings.activeXrayProfile == null ||
            TextUtils.isEmpty(settings.activeXrayProfile.rawLink)
        ) {
            throw new IllegalArgumentException("Xray профиль не выбран");
        }

        XraySettings xraySettings = settings.xraySettings != null ? settings.xraySettings : new XraySettings();
        JSONObject proxyOutbound = resolveProxyOutbound(settings.activeXrayProfile);
        proxyOutbound.put("tag", PROXY_TAG);
        proxyOutbound.remove("sendThrough");
        sanitizeOutbound(proxyOutbound, settings.activeXrayProfile);
        applySecurityOverrides(proxyOutbound, xraySettings);

        JSONObject root = new JSONObject();
        root.put("log", buildLog(context));
        root.put("dns", buildDns(xraySettings));
        root.put("inbounds", buildInbounds(context, xraySettings));
        root.put("outbounds", buildOutbounds(proxyOutbound, xraySettings, settings.byeDpiSettings));
        root.put("routing", buildRouting(context, xraySettings));
        String configJson = root.toString();
        writeDebugArtifacts(context, configJson, proxyOutbound);
        return configJson;
    }

    private static JSONObject resolveProxyOutbound(XrayProfile profile) throws Exception {
        String rawPayload = profile == null ? "" : profile.rawLink == null ? "" : profile.rawLink.trim();
        if (TextUtils.isEmpty(rawPayload)) {
            throw new IllegalStateException("Xray профиль пуст");
        }
        if (looksLikeJsonProfilePayload(rawPayload)) {
            JSONObject container = rawPayload.startsWith("[")
                ? new JSONObject().put("outbounds", new JSONArray(rawPayload))
                : new JSONObject(rawPayload);
            JSONObject outbound = extractPrimaryOutbound(container);
            if (outbound == null) {
                throw new IllegalStateException("Не удалось получить outbound из JSON профиля");
            }
            return new JSONObject(outbound.toString());
        }

        JSONObject converted = new JSONObject(XrayBridge.convertShareLinkToOutboundJson(rawPayload));
        JSONObject outbound = extractPrimaryOutbound(converted);
        if (outbound == null) {
            throw new IllegalStateException("Не удалось получить outbound из share-link профиля");
        }
        return new JSONObject(outbound.toString());
    }

    private static boolean looksLikeJsonProfilePayload(String rawPayload) {
        if (TextUtils.isEmpty(rawPayload)) {
            return false;
        }
        String normalized = rawPayload.trim();
        return normalized.startsWith("{") || normalized.startsWith("[");
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
            if (outbound != null && TextUtils.equals(PROXY_TAG, outbound.optString("tag"))) {
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

    private static JSONObject buildLog(Context context) throws Exception {
        JSONObject log = new JSONObject();
        File logDir = new File(context.getFilesDir(), "xray/log");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        File accessLog = new File(logDir, "access.log");
        File errorLog = new File(logDir, "error.log");
        resetLogFile(accessLog);
        resetLogFile(errorLog);
        log.put("access", accessLog.getAbsolutePath());
        log.put("error", errorLog.getAbsolutePath());
        log.put("loglevel", "info");
        log.put("dnsLog", true);
        return log;
    }

    private static void writeDebugArtifacts(Context context, String configJson, JSONObject proxyOutbound) {
        try {
            File xrayDir = new File(context.getFilesDir(), "xray");
            if (!xrayDir.exists()) {
                xrayDir.mkdirs();
            }
            writeFile(new File(xrayDir, "config.json"), configJson);
            writeFile(new File(xrayDir, "proxy-outbound.json"), proxyOutbound.toString());
        } catch (Exception ignored) {}
    }

    private static void writeFile(File file, String content) throws Exception {
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void resetLogFile(File file) {
        try {
            if (file == null) {
                return;
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (file.exists()) {
                file.delete();
            }
            try (FileOutputStream ignored = new FileOutputStream(file, false)) {
                // recreate on each startup to keep xray logs session-scoped
            }
        } catch (Exception ignored) {}
    }

    private static JSONObject buildDns(XraySettings settings) throws Exception {
        JSONObject dns = new JSONObject();
        dns.put("tag", DNS_TAG);
        JSONArray servers = new JSONArray();
        addDnsServer(servers, settings.remoteDns);
        addDnsServer(servers, settings.directDns);
        if (servers.length() > 0) {
            dns.put("servers", servers);
        }
        dns.put("queryStrategy", settings.ipv6 ? "UseIP" : "UseIPv4");
        return dns;
    }

    private static void addDnsServer(JSONArray servers, String value) {
        for (String entry : splitDnsEntries(value)) {
            String normalized = entry == null ? "" : entry.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (int index = 0; index < servers.length(); index++) {
                if (TextUtils.equals(normalized, servers.optString(index))) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                servers.put(normalized);
            }
        }
    }

    private static List<String> splitDnsEntries(String rawValue) {
        ArrayList<String> values = new ArrayList<>();
        if (TextUtils.isEmpty(rawValue)) {
            return values;
        }
        String[] parts = rawValue.split(",");
        for (String part : parts) {
            String normalized = part == null ? "" : part.trim();
            if (!TextUtils.isEmpty(normalized)) {
                values.add(normalized);
            }
        }
        return values;
    }

    private static JSONArray buildInbounds(Context context, XraySettings settings) throws Exception {
        JSONArray inbounds = new JSONArray();
        JSONObject tunInbound = new JSONObject();
        tunInbound.put("tag", TUN_TAG);
        tunInbound.put("protocol", "tun");
        tunInbound.put("port", 0);
        JSONObject tunSettings = new JSONObject();
        tunSettings.put("MTU", DEFAULT_MTU);
        tunSettings.put("user_level", 0);
        tunInbound.put("settings", tunSettings);
        tunInbound.put("sniffing", buildSniffing(settings));
        inbounds.put(tunInbound);

        if (isLocalProxyEnabled(settings)) {
            JSONObject socksInbound = new JSONObject();
            socksInbound.put("tag", SOCKS_TAG);
            socksInbound.put("protocol", "socks");
            socksInbound.put("listen", settings.allowLan ? "0.0.0.0" : "127.0.0.1");
            socksInbound.put("port", settings.localProxyPort);
            socksInbound.put("settings", buildSocksInboundSettings(settings));
            socksInbound.put("sniffing", buildSniffing(settings));
            inbounds.put(socksInbound);
        }
        return inbounds;
    }

    private static JSONArray buildOutbounds(
        JSONObject proxyOutbound,
        XraySettings xraySettings,
        ByeDpiSettings byeDpiSettings
    ) throws Exception {
        JSONArray outbounds = new JSONArray();
        boolean useByeDpiFrontProxy = byeDpiSettings != null && byeDpiSettings.launchOnXrayStart;
        if (useByeDpiFrontProxy) {
            enableByeDpiFrontProxy(proxyOutbound, xraySettings);
        }
        outbounds.put(proxyOutbound);

        JSONObject dnsOutbound = new JSONObject();
        dnsOutbound.put("tag", DNS_OUT_TAG);
        dnsOutbound.put("protocol", "dns");
        JSONObject dnsOutboundSettings = new JSONObject();
        dnsOutboundSettings.put("network", "tcp");
        dnsOutbound.put("settings", dnsOutboundSettings);
        outbounds.put(dnsOutbound);

        JSONObject direct = new JSONObject();
        direct.put("tag", DIRECT_TAG);
        direct.put("protocol", "freedom");
        outbounds.put(direct);

        JSONObject block = new JSONObject();
        block.put("tag", BLOCK_TAG);
        block.put("protocol", "blackhole");
        outbounds.put(block);

        if (useByeDpiFrontProxy) {
            JSONObject byeDpiFrontOutbound = new JSONObject();
            byeDpiFrontOutbound.put("tag", BYEDPI_FRONT_TAG);
            byeDpiFrontOutbound.put("protocol", "socks");
            JSONObject settings = new JSONObject();
            JSONArray servers = new JSONArray();
            JSONObject server = new JSONObject();
            server.put("address", byeDpiSettings.resolveRuntimeDialHost());
            server.put("port", byeDpiSettings.resolveRuntimeListenPort());
            addByeDpiSocksAuth(server, byeDpiSettings);
            servers.put(server);
            settings.put("servers", servers);
            byeDpiFrontOutbound.put("settings", settings);
            outbounds.put(byeDpiFrontOutbound);
        }
        return outbounds;
    }

    static void enableByeDpiFrontProxy(JSONObject proxyOutbound, XraySettings xraySettings) throws Exception {
        proxyOutbound.put("proxySettings", new JSONObject().put("tag", BYEDPI_FRONT_TAG).put("transportLayer", true));

        JSONObject streamSettings = proxyOutbound.optJSONObject("streamSettings");
        if (streamSettings == null) {
            streamSettings = new JSONObject();
            proxyOutbound.put("streamSettings", streamSettings);
        }
        JSONObject sockopt = streamSettings.optJSONObject("sockopt");
        if (sockopt == null) {
            sockopt = new JSONObject();
            streamSettings.put("sockopt", sockopt);
        }
        if (TextUtils.isEmpty(sockopt.optString("domainStrategy", ""))) {
            boolean ipv6 = xraySettings != null && xraySettings.ipv6;
            sockopt.put("domainStrategy", ipv6 ? "ForceIP" : "ForceIPv4");
        }
    }

    private static JSONObject buildRouting(Context context, XraySettings settings) throws Exception {
        JSONObject routing = new JSONObject();
        routing.put("domainStrategy", settings.ipv6 ? "AsIs" : "IPIfNonMatch");
        JSONArray rules = new JSONArray();

        JSONObject dnsRule = new JSONObject();
        dnsRule.put("type", "field");
        JSONArray dnsInboundTags = new JSONArray();
        dnsInboundTags.put(TUN_TAG);
        if (isLocalProxyEnabled(settings)) {
            dnsInboundTags.put(SOCKS_TAG);
        }
        dnsRule.put("inboundTag", dnsInboundTags);
        dnsRule.put("network", "udp,tcp");
        dnsRule.put("port", "53");
        dnsRule.put("outboundTag", DNS_OUT_TAG);
        rules.put(dnsRule);

        JSONObject internalDnsRule = new JSONObject();
        internalDnsRule.put("type", "field");
        internalDnsRule.put("inboundTag", new JSONArray().put(DNS_TAG));
        internalDnsRule.put("outboundTag", DIRECT_TAG);
        rules.put(internalDnsRule);

        if (!settings.proxyQuicEnabled) {
            JSONObject blockQuicRule = new JSONObject();
            blockQuicRule.put("type", "field");
            JSONArray quicInboundTags = new JSONArray();
            quicInboundTags.put(TUN_TAG);
            if (isLocalProxyEnabled(settings)) {
                quicInboundTags.put(SOCKS_TAG);
            }
            blockQuicRule.put("inboundTag", quicInboundTags);
            blockQuicRule.put("network", "udp");
            blockQuicRule.put("port", "443");
            blockQuicRule.put("outboundTag", BLOCK_TAG);
            rules.put(blockQuicRule);
        }

        addCustomRoutingRules(rules, settings, context);

        JSONObject trafficRule = new JSONObject();
        trafficRule.put("type", "field");
        JSONArray inboundTags = new JSONArray();
        inboundTags.put(TUN_TAG);
        if (isLocalProxyEnabled(settings)) {
            inboundTags.put(SOCKS_TAG);
        }
        trafficRule.put("inboundTag", inboundTags);
        trafficRule.put("outboundTag", PROXY_TAG);
        rules.put(trafficRule);

        JSONObject blockBt = new JSONObject();
        blockBt.put("type", "field");
        blockBt.put("protocol", new JSONArray().put("bittorrent"));
        blockBt.put("outboundTag", BLOCK_TAG);
        rules.put(blockBt);

        routing.put("rules", rules);
        return routing;
    }

    private static void addCustomRoutingRules(JSONArray rules, XraySettings settings, Context context)
        throws Exception {
        for (XrayRoutingRule rule : XrayRoutingStore.getValidRules(context)) {
            if (rule == null || !rule.enabled || TextUtils.isEmpty(rule.code)) {
                continue;
            }
            JSONObject routingRule = new JSONObject();
            routingRule.put("type", "field");
            JSONArray inboundTags = new JSONArray();
            inboundTags.put(TUN_TAG);
            if (isLocalProxyEnabled(settings)) {
                inboundTags.put(SOCKS_TAG);
            }
            routingRule.put("inboundTag", inboundTags);
            if (!putCustomRoutingMatcher(routingRule, rule)) {
                continue;
            }
            routingRule.put("outboundTag", resolveOutboundTag(rule.action));
            rules.put(routingRule);
        }
    }

    private static boolean putCustomRoutingMatcher(JSONObject routingRule, XrayRoutingRule rule) throws Exception {
        if (rule.matchType == XrayRoutingRule.MatchType.GEOSITE) {
            routingRule.put("domain", new JSONArray().put("geosite:" + rule.code));
            return true;
        }
        if (rule.matchType == XrayRoutingRule.MatchType.GEOIP) {
            routingRule.put("ip", new JSONArray().put("geoip:" + rule.code));
            return true;
        }
        if (rule.matchType == XrayRoutingRule.MatchType.DOMAIN) {
            JSONArray domainRules = buildRoutingValueArray(rule.code, true);
            if (domainRules.length() == 0) {
                return false;
            }
            routingRule.put("domain", domainRules);
            return true;
        }
        if (rule.matchType == XrayRoutingRule.MatchType.IP) {
            JSONArray ipRules = buildRoutingValueArray(rule.code, false);
            if (ipRules.length() == 0) {
                return false;
            }
            routingRule.put("ip", ipRules);
            return true;
        }
        if (rule.matchType == XrayRoutingRule.MatchType.PORT) {
            String portRule = normalizePortRoutingValue(rule.code);
            if (TextUtils.isEmpty(portRule)) {
                return false;
            }
            routingRule.put("port", portRule);
            return true;
        }
        return false;
    }

    private static JSONArray buildRoutingValueArray(String value, boolean domainRule) {
        JSONArray values = new JSONArray();
        for (String token : value.split("[,\\s]+")) {
            String normalized = trim(token);
            if (TextUtils.isEmpty(normalized)) {
                continue;
            }
            values.put(domainRule ? normalizeDomainRoutingValue(normalized) : normalized);
        }
        return values;
    }

    private static String normalizeDomainRoutingValue(String value) {
        return hasRoutingValuePrefix(value) ? value : "domain:" + value;
    }

    private static boolean hasRoutingValuePrefix(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return (
            normalized.startsWith("domain:") ||
            normalized.startsWith("full:") ||
            normalized.startsWith("regexp:") ||
            normalized.startsWith("regex:") ||
            normalized.startsWith("geosite:") ||
            normalized.startsWith("keyword:") ||
            normalized.startsWith("plain:")
        );
    }

    private static String normalizePortRoutingValue(String value) {
        ArrayList<String> ports = new ArrayList<>();
        for (String token : value.split("[,\\s]+")) {
            String normalized = trim(token);
            if (!TextUtils.isEmpty(normalized)) {
                ports.add(normalized);
            }
        }
        return TextUtils.join(",", ports);
    }

    private static String resolveOutboundTag(XrayRoutingRule.Action action) {
        if (action == XrayRoutingRule.Action.DIRECT) {
            return DIRECT_TAG;
        }
        if (action == XrayRoutingRule.Action.BLOCK) {
            return BLOCK_TAG;
        }
        return PROXY_TAG;
    }

    private static JSONObject buildSniffing(XraySettings settings) throws Exception {
        JSONObject sniffing = new JSONObject();
        sniffing.put("enabled", settings.sniffingEnabled);
        sniffing.put("destOverride", new JSONArray().put("http").put("tls").put("quic"));
        return sniffing;
    }

    static boolean isLocalProxyEnabled(XraySettings settings) {
        return settings != null && settings.localProxyEnabled && settings.localProxyPort > 0;
    }

    static JSONObject buildSocksInboundSettings(XraySettings settings) throws Exception {
        JSONObject socksSettings = new JSONObject();
        if (
            settings != null &&
            settings.localProxyAuthEnabled &&
            !TextUtils.isEmpty(trim(settings.localProxyUsername)) &&
            !TextUtils.isEmpty(trim(settings.localProxyPassword))
        ) {
            socksSettings.put("auth", "password");
            socksSettings.put(
                "accounts",
                new JSONArray().put(
                    new JSONObject()
                        .put("user", trim(settings.localProxyUsername))
                        .put("pass", trim(settings.localProxyPassword))
                )
            );
        } else {
            socksSettings.put("auth", "noauth");
        }
        socksSettings.put("udp", true);
        return socksSettings;
    }

    static void addByeDpiSocksAuth(JSONObject server, ByeDpiSettings byeDpiSettings) throws Exception {
        if (server == null || byeDpiSettings == null || !byeDpiSettings.proxyAuthEnabled) {
            return;
        }
        String username = trim(byeDpiSettings.resolveRuntimeProxyUsername());
        String password = trim(byeDpiSettings.resolveRuntimeProxyPassword());
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            return;
        }
        server.put("users", new JSONArray().put(new JSONObject().put("user", username).put("pass", password)));
    }

    static void applySecurityOverrides(JSONObject outbound, XraySettings settings) throws Exception {
        if (!settings.allowInsecure) {
            return;
        }
        JSONObject streamSettings = outbound.optJSONObject("streamSettings");
        if (streamSettings == null) {
            return;
        }
        JSONObject tlsSettings = streamSettings.optJSONObject("tlsSettings");
        if (tlsSettings != null) {
            tlsSettings.put("allowInsecure", true);
        }
    }

    static void sanitizeOutbound(JSONObject outbound, XrayProfile activeProfile) throws Exception {
        pruneJsonObject(outbound);
        JSONObject streamSettings = outbound.optJSONObject("streamSettings");
        if (streamSettings == null) {
            return;
        }
        String fallbackServerName = resolveFallbackServerName(outbound, activeProfile);
        sanitizeTlsSettings(streamSettings, fallbackServerName);
        sanitizeRealitySettings(streamSettings, fallbackServerName);
        pruneJsonObject(streamSettings);
    }

    private static void sanitizeTlsSettings(JSONObject streamSettings, String fallbackServerName) throws Exception {
        JSONObject tlsSettings = streamSettings.optJSONObject("tlsSettings");
        if (tlsSettings == null) {
            return;
        }
        if (TextUtils.isEmpty(trim(tlsSettings.optString("serverName"))) && !TextUtils.isEmpty(fallbackServerName)) {
            tlsSettings.put("serverName", fallbackServerName);
        }
        pruneJsonObject(tlsSettings);
        if (tlsSettings.length() == 0) {
            streamSettings.remove("tlsSettings");
        }
    }

    private static void sanitizeRealitySettings(JSONObject streamSettings, String fallbackServerName) throws Exception {
        JSONObject realitySettings = streamSettings.optJSONObject("realitySettings");
        if (realitySettings == null) {
            return;
        }

        // libXray serializes client REALITY configs with server-only fields as null.
        // Xray-core treats dest/target json.RawMessage=null as present and switches to
        // server-side parsing, which then fails on missing serverNames/privateKey.
        removeKeys(
            realitySettings,
            "show",
            "target",
            "dest",
            "type",
            "xver",
            "serverNames",
            "privateKey",
            "minClientVer",
            "maxClientVer",
            "maxTimeDiff",
            "shortIds",
            "mldsa65Seed",
            "limitFallbackUpload",
            "limitFallbackDownload",
            "masterKeyLog"
        );
        if (
            TextUtils.isEmpty(trim(realitySettings.optString("serverName"))) && !TextUtils.isEmpty(fallbackServerName)
        ) {
            realitySettings.put("serverName", fallbackServerName);
        }
        pruneJsonObject(realitySettings);
        if (realitySettings.length() == 0) {
            streamSettings.remove("realitySettings");
        }
    }

    static String resolveFallbackServerName(JSONObject outbound, XrayProfile activeProfile) {
        if (activeProfile != null && !TextUtils.isEmpty(trim(activeProfile.address))) {
            return trim(activeProfile.address);
        }
        JSONObject settings = outbound.optJSONObject("settings");
        if (settings == null) {
            return "";
        }
        JSONArray vnext = settings.optJSONArray("vnext");
        if (vnext != null && vnext.length() > 0) {
            JSONObject server = vnext.optJSONObject(0);
            if (server != null && !TextUtils.isEmpty(trim(server.optString("address")))) {
                return trim(server.optString("address"));
            }
        }
        JSONArray servers = settings.optJSONArray("servers");
        if (servers != null && servers.length() > 0) {
            JSONObject server = servers.optJSONObject(0);
            if (server != null && !TextUtils.isEmpty(trim(server.optString("address")))) {
                return trim(server.optString("address"));
            }
        }
        return "";
    }

    static void pruneJsonObject(JSONObject object) throws Exception {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        for (String key : keys) {
            Object value = object.opt(key);
            if (value == null || value == JSONObject.NULL) {
                object.remove(key);
                continue;
            }
            if (value instanceof JSONObject) {
                JSONObject childObject = (JSONObject) value;
                pruneJsonObject(childObject);
                if (childObject.length() == 0) {
                    object.remove(key);
                }
                continue;
            }
            if (value instanceof JSONArray) {
                JSONArray childArray = (JSONArray) value;
                pruneJsonArray(childArray);
                if (childArray.length() == 0) {
                    object.remove(key);
                }
                continue;
            }
            if (value instanceof String && TextUtils.isEmpty(trim((String) value))) {
                object.remove(key);
            }
        }
    }

    static void pruneJsonArray(JSONArray array) throws Exception {
        for (int index = array.length() - 1; index >= 0; index--) {
            Object value = array.opt(index);
            if (value == null || value == JSONObject.NULL) {
                array.remove(index);
                continue;
            }
            if (value instanceof JSONObject) {
                JSONObject childObject = (JSONObject) value;
                pruneJsonObject(childObject);
                if (childObject.length() == 0) {
                    array.remove(index);
                }
                continue;
            }
            if (value instanceof JSONArray) {
                JSONArray childArray = (JSONArray) value;
                pruneJsonArray(childArray);
                if (childArray.length() == 0) {
                    array.remove(index);
                }
                continue;
            }
            if (value instanceof String && TextUtils.isEmpty(trim((String) value))) {
                array.remove(index);
            }
        }
    }

    static void removeKeys(JSONObject object, String... keys) {
        for (String key : keys) {
            object.remove(key);
        }
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
