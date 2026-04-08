package wings.v.xray;

import android.content.Context;
import android.text.TextUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import wings.v.core.ByeDpiSettings;
import wings.v.core.XrayProfile;
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
        "PMD.LooseCoupling",
        "PMD.AvoidDuplicateLiterals",
        "PMD.TooManyMethods",
        "PMD.CyclomaticComplexity",
    }
)
public final class XrayAutoSearchConfigFactory {

    private static final String SOCKS_TAG = "socks-in";
    private static final String PROXY_TAG = "proxy";
    private static final String BYEDPI_FRONT_TAG = "byedpi-front";
    private static final String DNS_TAG = "dns-internal";
    private static final String DNS_OUT_TAG = "dns-out";
    private static final String DIRECT_TAG = "direct";
    private static final String BLOCK_TAG = "block";

    private XrayAutoSearchConfigFactory() {}

    public static String buildConfigJson(
        Context context,
        XrayProfile profile,
        XraySettings xraySettings,
        int localProxyPort,
        ByeDpiSettings byeDpiSettings,
        boolean useByeDpi
    ) throws Exception {
        if (profile == null || TextUtils.isEmpty(profile.rawLink)) {
            throw new IllegalArgumentException("Xray профиль не выбран");
        }

        JSONObject converted = new JSONObject(XrayBridge.convertShareLinkToOutboundJson(profile.rawLink));
        JSONArray convertedOutbounds = converted.optJSONArray("outbounds");
        if (convertedOutbounds == null || convertedOutbounds.length() == 0) {
            throw new IllegalStateException("Не удалось получить outbound из VLESS профиля");
        }

        XraySettings settings = xraySettings != null ? xraySettings : new XraySettings();
        JSONObject proxyOutbound = new JSONObject(convertedOutbounds.getJSONObject(0).toString());
        proxyOutbound.put("tag", PROXY_TAG);
        proxyOutbound.remove("sendThrough");
        XrayConfigFactory.sanitizeOutbound(proxyOutbound, profile);
        XrayConfigFactory.applySecurityOverrides(proxyOutbound, settings);
        if (useByeDpi && byeDpiSettings != null) {
            XrayConfigFactory.enableByeDpiFrontProxy(proxyOutbound, settings);
        }

        JSONObject root = new JSONObject();
        root.put("log", buildLog(context));
        root.put("dns", buildDns(settings));
        root.put("inbounds", buildInbounds(settings, localProxyPort));
        root.put("outbounds", buildOutbounds(proxyOutbound, byeDpiSettings, useByeDpi));
        root.put("routing", buildRouting(settings));
        String configJson = root.toString();
        writeDebugArtifacts(context, configJson, proxyOutbound);
        return configJson;
    }

    private static JSONObject buildLog(Context context) throws Exception {
        JSONObject log = new JSONObject();
        File logDir = new File(context.getFilesDir(), "xray/autosearch/log");
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
            File xrayDir = new File(context.getFilesDir(), "xray/autosearch");
            if (!xrayDir.exists()) {
                xrayDir.mkdirs();
            }
            writeFile(new File(xrayDir, "config.json"), configJson);
            writeFile(new File(xrayDir, "proxy-outbound.json"), proxyOutbound.toString());
        } catch (Exception ignored) {}
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
                // recreate on each autosearch startup to keep logs session-scoped
            }
        } catch (Exception ignored) {}
    }

    private static void writeFile(File file, String content) throws Exception {
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
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

    private static JSONArray buildInbounds(XraySettings settings, int localProxyPort) throws Exception {
        JSONArray inbounds = new JSONArray();
        JSONObject socksInbound = new JSONObject();
        socksInbound.put("tag", SOCKS_TAG);
        socksInbound.put("protocol", "socks");
        socksInbound.put("listen", settings.allowLan ? "0.0.0.0" : "127.0.0.1");
        socksInbound.put("port", localProxyPort);
        socksInbound.put("settings", XrayConfigFactory.buildSocksInboundSettings(settings));
        socksInbound.put("sniffing", buildSniffing(settings));
        inbounds.put(socksInbound);
        return inbounds;
    }

    private static JSONArray buildOutbounds(JSONObject proxyOutbound, ByeDpiSettings byeDpiSettings, boolean useByeDpi)
        throws Exception {
        JSONArray outbounds = new JSONArray();
        outbounds.put(proxyOutbound);

        JSONObject dnsOutbound = new JSONObject();
        dnsOutbound.put("tag", DNS_OUT_TAG);
        dnsOutbound.put("protocol", "dns");
        dnsOutbound.put("settings", new JSONObject().put("network", "tcp"));
        outbounds.put(dnsOutbound);

        JSONObject direct = new JSONObject();
        direct.put("tag", DIRECT_TAG);
        direct.put("protocol", "freedom");
        outbounds.put(direct);

        JSONObject block = new JSONObject();
        block.put("tag", BLOCK_TAG);
        block.put("protocol", "blackhole");
        outbounds.put(block);

        if (useByeDpi && byeDpiSettings != null) {
            JSONObject byeDpiFrontOutbound = new JSONObject();
            byeDpiFrontOutbound.put("tag", BYEDPI_FRONT_TAG);
            byeDpiFrontOutbound.put("protocol", "socks");
            JSONObject settings = new JSONObject();
            JSONArray servers = new JSONArray();
            JSONObject server = new JSONObject();
            server.put("address", byeDpiSettings.resolveRuntimeDialHost());
            server.put("port", byeDpiSettings.resolveRuntimeListenPort());
            XrayConfigFactory.addByeDpiSocksAuth(server, byeDpiSettings);
            servers.put(server);
            settings.put("servers", servers);
            byeDpiFrontOutbound.put("settings", settings);
            outbounds.put(byeDpiFrontOutbound);
        }
        return outbounds;
    }

    private static JSONObject buildRouting(XraySettings settings) throws Exception {
        JSONObject routing = new JSONObject();
        routing.put("domainStrategy", settings.ipv6 ? "AsIs" : "IPIfNonMatch");
        JSONArray rules = new JSONArray();

        JSONObject dnsRule = new JSONObject();
        dnsRule.put("type", "field");
        dnsRule.put("inboundTag", new JSONArray().put(SOCKS_TAG));
        dnsRule.put("network", "udp,tcp");
        dnsRule.put("port", "53");
        dnsRule.put("outboundTag", DNS_OUT_TAG);
        rules.put(dnsRule);

        JSONObject internalDnsRule = new JSONObject();
        internalDnsRule.put("type", "field");
        internalDnsRule.put("inboundTag", new JSONArray().put(DNS_TAG));
        internalDnsRule.put("outboundTag", DIRECT_TAG);
        rules.put(internalDnsRule);

        JSONObject trafficRule = new JSONObject();
        trafficRule.put("type", "field");
        trafficRule.put("inboundTag", new JSONArray().put(SOCKS_TAG));
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

    private static JSONObject buildSniffing(XraySettings settings) throws Exception {
        JSONObject sniffing = new JSONObject();
        sniffing.put("enabled", settings.sniffingEnabled);
        sniffing.put("destOverride", new JSONArray().put("http").put("tls").put("quic"));
        return sniffing;
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
}
