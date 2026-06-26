package wings.v.xray;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import wings.v.R;
import wings.v.WingsApplication;
import wings.v.core.AppPrefs;
import wings.v.core.ByeDpiSettings;
import wings.v.core.DirectNetworkConnection;
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
    private static final String TUN_BYPASS_TAG = "tun-in-bypass";
    private static final String TPROXY_TAG = "tproxy-in";
    // Transparent REDIRECT inbound for AP/hotspot (tethering) client traffic.
    // Plain dokodemo-door with followRedirect (NO tproxy sockopt), so the app-uid
    // VpnService xray can bind it - unlike the TPROXY inbound which needs
    // IP_TRANSPARENT (root). iptables nat REDIRECT diverts shared-client traffic
    // here, where SO_ORIGINAL_DST is recovered and routed through the active
    // tunnel outbound, bypassing the gVisor TUN (and its unknown-uid policy).
    public static final int REDIRECT_PORT = 12346;
    private static final String REDIR_TAG = "redirect-in";
    private static final String SOCKS_TAG = "socks-in";
    private static final String HTTP_TAG = "http-in";
    private static final String DEFAULT_LOOPBACK_LISTEN = "127.0.0.1";
    private static final String PROXY_TAG = "proxy";
    private static final String BYEDPI_FRONT_TAG = "byedpi-front";
    private static final String DNS_TAG = "dns-internal";
    private static final String DNS_OUT_TAG = "dns-out";
    private static final String DIRECT_TAG = "direct";
    private static final String BLOCK_TAG = "block";
    private static final int DEFAULT_MTU = 1500;

    private XrayConfigFactory() {}

    public static String buildConfigJson(Context context, ProxySettings settings) throws Exception {
        return buildConfigJson(context, settings, null, 0, settings != null ? settings.byeDpiSettings : null);
    }

    public static String buildConfigJson(Context context, ProxySettings settings, boolean includeTunInbound)
        throws Exception {
        return buildConfigJson(
            context,
            settings,
            null,
            0,
            settings != null ? settings.byeDpiSettings : null,
            includeTunInbound
        );
    }

    public static String buildConfigJson(
        Context context,
        ProxySettings settings,
        String outboundHostOverride,
        int outboundPortOverride,
        ByeDpiSettings byeDpiSettings
    ) throws Exception {
        return buildConfigJson(context, settings, outboundHostOverride, outboundPortOverride, byeDpiSettings, true);
    }

    public static String buildConfigJson(
        Context context,
        ProxySettings settings,
        String outboundHostOverride,
        int outboundPortOverride,
        ByeDpiSettings byeDpiSettings,
        boolean includeTunInbound
    ) throws Exception {
        return buildConfigJson(
            context,
            settings,
            outboundHostOverride,
            outboundPortOverride,
            byeDpiSettings,
            includeTunInbound,
            0
        );
    }

    public static String buildTproxyConfigJson(Context context, ProxySettings settings, int tproxyPort)
        throws Exception {
        return buildConfigJson(
            context,
            settings,
            null,
            0,
            settings != null ? settings.byeDpiSettings : null,
            false,
            tproxyPort
        );
    }

    public static String buildConfigJson(
        Context context,
        ProxySettings settings,
        String outboundHostOverride,
        int outboundPortOverride,
        ByeDpiSettings byeDpiSettings,
        boolean includeTunInbound,
        int tproxyPort
    ) throws Exception {
        if (
            settings == null ||
            settings.activeXrayProfile == null ||
            TextUtils.isEmpty(settings.activeXrayProfile.rawLink)
        ) {
            throw new IllegalArgumentException(context.getString(wings.v.R.string.xray_config_profile_not_selected));
        }

        XraySettings xraySettings = settings.xraySettings != null ? settings.xraySettings : new XraySettings();
        JSONObject proxyOutbound = resolveProxyOutbound(settings.activeXrayProfile);
        proxyOutbound.put("tag", PROXY_TAG);
        proxyOutbound.remove("sendThrough");
        sanitizeOutbound(proxyOutbound, settings.activeXrayProfile);
        applySecurityOverrides(proxyOutbound, xraySettings);
        if (!TextUtils.isEmpty(trim(outboundHostOverride)) && outboundPortOverride > 0) {
            rewritePrimaryOutboundEndpoint(proxyOutbound, trim(outboundHostOverride), outboundPortOverride);
        }

        JSONObject root = new JSONObject();
        root.put("log", buildLog(context));
        root.put("dns", buildDns(xraySettings));
        root.put("inbounds", buildInbounds(context, xraySettings, includeTunInbound, tproxyPort));
        root.put("outbounds", buildOutbounds(proxyOutbound, xraySettings, byeDpiSettings));
        root.put("routing", buildRouting(context, xraySettings, includeTunInbound, tproxyPort));
        String configJson = root.toString();
        writeDebugArtifacts(context, configJson, proxyOutbound);
        return configJson;
    }

    /**
     * Builds an xray config that pipes the VpnService TUN through xray-core's
     * gVisor inbound (which honors excludedUids/allowedUids the same way the
     * Xray VPN path does) into a synthetic WireGuard outbound. Used as the
     * non-root replacement for wireguard-android GoBackend on the userspace
     * WG / VK TURN VpnService path: bypassed apps that try to bind to tun
     * directly get filtered by xray's per-connection UID lookup, closing the
     * Android per-app-VPN leak that GoBackend cannot fix from userspace.
     */
    public static String buildUserspaceWireGuardConfigJson(
        Context context,
        ProxySettings settings,
        String peerEndpointOverride
    ) throws Exception {
        if (settings == null) {
            throw new IllegalArgumentException("settings required");
        }
        // DNS server selection for xray's internal resolver. Three buckets:
        //   1. VK TURN backend - mirror vk-turn-proxy's own bootstrap
        //      resolver list (protected_net.go defaultResolverAddrs).
        //      Yandex 77.88.8.x is first because in RU block conditions it
        //      is rarely censored while 1.1.1.1 often is.
        //   2. Other WG-style backends - use settings.wgDns ([Interface]
        //      DNS line), matching wireguard-android's defaults.
        //   3. Fallback - 1.1.1.1, 1.0.0.1.
        XraySettings sourceXraySettings = settings.xraySettings != null ? settings.xraySettings : new XraySettings();
        XraySettings effectiveXraySettings = sourceXraySettings.copy();
        boolean turnFlavor = settings.backendType != null && settings.backendType.usesTurnProxy();
        String dnsServers;
        if (turnFlavor) {
            // For WG-style backends (VK TURN / WB Stream / AmneziaWG) use
            // the wgDns from the WireGuard [Interface] preference. Routed
            // through the WG outbound below so resolve works in block
            // conditions where the underlying network cannot reach public
            // resolvers in the clear.
            String wgDns = settings.wgDns == null ? "" : settings.wgDns.trim();
            dnsServers = wgDns.isEmpty() ? "1.1.1.1, 1.0.0.1" : wgDns;
        } else {
            String wgDns = settings.wgDns == null ? "" : settings.wgDns.trim();
            dnsServers = wgDns.isEmpty() ? "1.1.1.1, 1.0.0.1" : wgDns;
        }
        effectiveXraySettings.remoteDns = dnsServers;
        effectiveXraySettings.directDns = dnsServers;
        JSONObject wgOutbound = buildWireGuardOutbound(settings, peerEndpointOverride);

        // Domains explicitly routed direct must resolve via the provider DNS on
        // the physical network so the answer matches the direct egress and does
        // not hang on the (mobile-flaky) tunnel. Everything else keeps resolving
        // through the tunnel resolver (anti-poisoning default).
        DirectDnsPlan directDnsPlan = computeDirectDnsPlan(context);

        JSONObject root = new JSONObject();
        root.put("log", buildLog(context, "info"));
        root.put("dns", buildDns(effectiveXraySettings, directDnsPlan));
        root.put("inbounds", buildInbounds(context, effectiveXraySettings, true, 0));
        root.put("outbounds", buildOutbounds(wgOutbound, effectiveXraySettings, null));
        // For TURN/WG flavors route xray's internal DNS resolver through
        // the WG outbound so DNS queries ride the tunnel instead of leaking
        // out via direct/freedom on the underlying physical network.
        String internalDnsOutbound = turnFlavor ? PROXY_TAG : DIRECT_TAG;
        root.put("routing", buildRouting(context, effectiveXraySettings, true, 0, internalDnsOutbound, directDnsPlan));
        String configJson = root.toString();
        writeDebugArtifacts(context, configJson, wgOutbound);
        return configJson;
    }

    private static JSONObject buildWireGuardOutbound(ProxySettings settings, String peerEndpointOverride)
        throws Exception {
        String privateKey = settings.wgPrivateKey == null ? "" : settings.wgPrivateKey.trim();
        String peerPublicKey = settings.wgPublicKey == null ? "" : settings.wgPublicKey.trim();
        if (privateKey.isEmpty() || peerPublicKey.isEmpty()) {
            throw new IllegalStateException("WireGuard keys missing for userspace xray-WG outbound");
        }
        String resolvedEndpoint = !TextUtils.isEmpty(trim(peerEndpointOverride))
            ? trim(peerEndpointOverride)
            : settings.backendType == wings.v.core.BackendType.WIREGUARD
                ? settings.endpoint
                : settings.localEndpoint;
        if (TextUtils.isEmpty(resolvedEndpoint)) {
            throw new IllegalStateException("WireGuard peer endpoint missing for userspace xray-WG outbound");
        }

        JSONObject peer = new JSONObject();
        peer.put("publicKey", peerPublicKey);
        if (!TextUtils.isEmpty(settings.wgPresharedKey)) {
            peer.put("preSharedKey", settings.wgPresharedKey.trim());
        }
        peer.put("endpoint", resolvedEndpoint);
        peer.put("keepAlive", 25);
        JSONArray allowedIps = new JSONArray();
        String allowedIpsRaw = settings.wgAllowedIps == null ? "" : settings.wgAllowedIps;
        for (String entry : allowedIpsRaw.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) allowedIps.put(trimmed);
        }
        if (allowedIps.length() == 0) {
            allowedIps.put("0.0.0.0/0").put("::/0");
        }
        peer.put("allowedIPs", allowedIps);

        JSONArray address = new JSONArray();
        String addressRaw = settings.wgAddresses == null ? "" : settings.wgAddresses;
        for (String entry : addressRaw.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) address.put(trimmed);
        }

        JSONObject wgSettings = new JSONObject();
        wgSettings.put("secretKey", privateKey);
        wgSettings.put("address", address);
        wgSettings.put("peers", new JSONArray().put(peer));
        wgSettings.put("mtu", settings.wgMtu > 0 ? settings.wgMtu : 1420);
        wgSettings.put("isClient", true);
        wgSettings.put("noKernelTun", true);

        JSONObject outbound = new JSONObject();
        outbound.put("tag", PROXY_TAG);
        outbound.put("protocol", "wireguard");
        outbound.put("settings", wgSettings);
        return outbound;
    }

    private static JSONObject resolveProxyOutbound(XrayProfile profile) throws Exception {
        String rawPayload = profile == null ? "" : profile.rawLink == null ? "" : profile.rawLink.trim();
        if (TextUtils.isEmpty(rawPayload)) {
            throw new IllegalStateException(WingsApplication.getStringSafe(R.string.xray_config_profile_empty));
        }
        if (looksLikeJsonProfilePayload(rawPayload)) {
            JSONObject container = rawPayload.startsWith("[")
                ? new JSONObject().put("outbounds", new JSONArray(rawPayload))
                : new JSONObject(rawPayload);
            JSONObject outbound = extractPrimaryOutbound(container);
            if (outbound == null) {
                throw new IllegalStateException(
                    WingsApplication.getStringSafe(R.string.xray_config_outbound_from_json_failed)
                );
            }
            return new JSONObject(outbound.toString());
        }

        JSONObject converted = new JSONObject(XrayBridge.convertShareLinkToOutboundJson(rawPayload));
        JSONObject outbound = extractPrimaryOutbound(converted);
        if (outbound == null) {
            throw new IllegalStateException(
                WingsApplication.getStringSafe(R.string.xray_config_outbound_from_share_link_failed)
            );
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
        return buildLog(context, "info");
    }

    private static JSONObject buildLog(Context context, String level) throws Exception {
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
        log.put("loglevel", level);
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
        return buildDns(settings, null);
    }

    private static JSONObject buildDns(XraySettings settings, @Nullable DirectDnsPlan directPlan) throws Exception {
        JSONObject dns = new JSONObject();
        dns.put("tag", DNS_TAG);
        JSONArray servers = new JSONArray();
        // Direct-routed domains first: one provider-DNS server per resolver IP,
        // scoped to those domains so they are resolved on the physical network
        // (the query itself is sent direct by a routing rule in buildRouting that
        // matches these resolver IPs). No skipFallback: if the provider resolver
        // is unreachable (e.g. stale after a Wi-Fi -> mobile switch) the tunnel
        // resolver still answers, degrading gracefully instead of failing.
        if (directPlan != null) {
            JSONArray directDomains = new JSONArray();
            for (String domain : directPlan.domains) {
                directDomains.put(domain);
            }
            for (String resolver : directPlan.dnsServers) {
                JSONObject directServer = new JSONObject();
                directServer.put("address", resolver);
                directServer.put("domains", directDomains);
                servers.put(directServer);
            }
        }
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

    private static JSONArray buildInbounds(
        Context context,
        XraySettings settings,
        boolean includeTunInbound,
        int tproxyPort
    ) throws Exception {
        JSONArray inbounds = new JSONArray();
        if (includeTunInbound) {
            JSONObject tunInbound = new JSONObject();
            tunInbound.put("tag", TUN_TAG);
            tunInbound.put("protocol", "tun");
            tunInbound.put("port", 0);
            JSONObject tunSettings = new JSONObject();
            tunSettings.put("MTU", DEFAULT_MTU);
            tunSettings.put("user_level", 0);
            applyTunUidFilter(context, tunSettings, settings);
            tunInbound.put("settings", tunSettings);
            tunInbound.put("sniffing", buildSniffing(settings));
            inbounds.put(tunInbound);

            // Transparent redirect inbound for shared (tethering) clients. Always
            // present next to the gVisor TUN so the iptables nat REDIRECT can point
            // here while sharing is active; idle otherwise. followRedirect recovers
            // SO_ORIGINAL_DST; no tproxy sockopt, so it binds under the app uid.
            JSONObject redirInbound = new JSONObject();
            redirInbound.put("tag", REDIR_TAG);
            redirInbound.put("protocol", "dokodemo-door");
            redirInbound.put("listen", "0.0.0.0");
            redirInbound.put("port", REDIRECT_PORT);
            JSONObject redirSettings = new JSONObject();
            redirSettings.put("network", "tcp,udp");
            redirSettings.put("followRedirect", true);
            redirInbound.put("settings", redirSettings);
            redirInbound.put("sniffing", buildSniffing(settings));
            inbounds.put(redirInbound);
        }

        if (tproxyPort > 0) {
            JSONObject tproxyInbound = new JSONObject();
            tproxyInbound.put("tag", TPROXY_TAG);
            tproxyInbound.put("protocol", "dokodemo-door");
            tproxyInbound.put("listen", "0.0.0.0");
            tproxyInbound.put("port", tproxyPort);
            JSONObject tproxySettings = new JSONObject();
            tproxySettings.put("network", "tcp,udp");
            tproxySettings.put("followRedirect", true);
            tproxyInbound.put("settings", tproxySettings);
            JSONObject streamSettings = new JSONObject();
            JSONObject sockopt = new JSONObject();
            sockopt.put("tproxy", "tproxy");
            streamSettings.put("sockopt", sockopt);
            tproxyInbound.put("streamSettings", streamSettings);
            tproxyInbound.put("sniffing", buildSniffing(settings));
            inbounds.put(tproxyInbound);
        }

        if (isLocalProxyEnabled(settings)) {
            JSONObject socksInbound = new JSONObject();
            socksInbound.put("tag", SOCKS_TAG);
            socksInbound.put("protocol", "socks");
            socksInbound.put("listen", resolveSocksListenAddress(settings));
            socksInbound.put("port", settings.localProxyPort);
            socksInbound.put("settings", buildSocksInboundSettings(settings));
            socksInbound.put("sniffing", buildSniffing(settings));
            inbounds.put(socksInbound);
        }
        if (isHttpProxyEnabled(settings)) {
            JSONObject httpInbound = new JSONObject();
            httpInbound.put("tag", HTTP_TAG);
            httpInbound.put("protocol", "http");
            httpInbound.put("listen", resolveHttpListenAddress(settings));
            httpInbound.put("port", settings.httpProxyPort);
            httpInbound.put("settings", buildHttpInboundSettings(settings));
            httpInbound.put("sniffing", buildSniffing(settings));
            inbounds.put(httpInbound);
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

    private static JSONObject buildRouting(
        Context context,
        XraySettings settings,
        boolean includeTunInbound,
        int tproxyPort
    ) throws Exception {
        return buildRouting(context, settings, includeTunInbound, tproxyPort, DIRECT_TAG);
    }

    private static JSONObject buildRouting(
        Context context,
        XraySettings settings,
        boolean includeTunInbound,
        int tproxyPort,
        String internalDnsOutboundTag
    ) throws Exception {
        return buildRouting(context, settings, includeTunInbound, tproxyPort, internalDnsOutboundTag, null);
    }

    private static JSONObject buildRouting(
        Context context,
        XraySettings settings,
        boolean includeTunInbound,
        int tproxyPort,
        String internalDnsOutboundTag,
        @Nullable DirectDnsPlan directPlan
    ) throws Exception {
        JSONObject routing = new JSONObject();
        routing.put("domainStrategy", settings.ipv6 ? "AsIs" : "IPIfNonMatch");
        JSONArray rules = new JSONArray();

        JSONArray trafficInboundTags = buildTrafficInboundTags(settings, includeTunInbound, tproxyPort);
        // Bypass rule MUST come before the DNS rule so even DNS queries from
        // bypass UIDs get diverted to direct. Otherwise the tun-bypass tag
        // matches "tun-in" routing decision twice (DNS -> dns-out, then
        // proxy), and the DNS rule wins because it is listed earlier.
        if (includeTunInbound) {
            JSONObject bypassRule = new JSONObject();
            bypassRule.put("type", "field");
            bypassRule.put("inboundTag", new JSONArray().put(TUN_BYPASS_TAG));
            bypassRule.put("outboundTag", DIRECT_TAG);
            rules.put(bypassRule);
        }
        JSONObject dnsRule = new JSONObject();
        dnsRule.put("type", "field");
        dnsRule.put("inboundTag", trafficInboundTags);
        dnsRule.put("network", "udp,tcp");
        dnsRule.put("port", "53");
        dnsRule.put("outboundTag", DNS_OUT_TAG);
        rules.put(dnsRule);

        // Send internal-DNS queries aimed at the provider resolvers out direct, so
        // direct-routed domains resolve on the physical network. Must precede the
        // catch-all internal-DNS rule below (which sends the rest to the tunnel).
        if (directPlan != null) {
            JSONObject directDnsRule = new JSONObject();
            directDnsRule.put("type", "field");
            directDnsRule.put("inboundTag", new JSONArray().put(DNS_TAG));
            JSONArray resolverIps = new JSONArray();
            for (String resolver : directPlan.dnsServers) {
                resolverIps.put(resolver);
            }
            directDnsRule.put("ip", resolverIps);
            directDnsRule.put("outboundTag", DIRECT_TAG);
            rules.put(directDnsRule);
        }

        JSONObject internalDnsRule = new JSONObject();
        internalDnsRule.put("type", "field");
        internalDnsRule.put("inboundTag", new JSONArray().put(DNS_TAG));
        internalDnsRule.put("outboundTag", internalDnsOutboundTag);
        rules.put(internalDnsRule);

        if (!settings.proxyQuicEnabled) {
            JSONObject blockQuicRule = new JSONObject();
            blockQuicRule.put("type", "field");
            blockQuicRule.put("inboundTag", trafficInboundTags);
            blockQuicRule.put("network", "udp");
            blockQuicRule.put("port", "443");
            blockQuicRule.put("outboundTag", BLOCK_TAG);
            rules.put(blockQuicRule);
        }

        addCustomRoutingRules(rules, context, trafficInboundTags);

        JSONObject trafficRule = new JSONObject();
        trafficRule.put("type", "field");
        trafficRule.put("inboundTag", trafficInboundTags);
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

    private static JSONArray buildTrafficInboundTags(XraySettings settings, boolean includeTunInbound, int tproxyPort) {
        JSONArray inboundTags = new JSONArray();
        if (includeTunInbound) {
            inboundTags.put(TUN_TAG);
            inboundTags.put(REDIR_TAG);
        }
        if (tproxyPort > 0) {
            inboundTags.put(TPROXY_TAG);
        }
        if (isLocalProxyEnabled(settings)) {
            inboundTags.put(SOCKS_TAG);
        }
        if (isHttpProxyEnabled(settings)) {
            inboundTags.put(HTTP_TAG);
        }
        return inboundTags;
    }

    private static void addCustomRoutingRules(JSONArray rules, Context context, JSONArray inboundTags)
        throws Exception {
        for (XrayRoutingRule rule : XrayRoutingStore.getValidRules(context)) {
            if (rule == null || !rule.enabled || TextUtils.isEmpty(rule.code)) {
                continue;
            }
            JSONObject routingRule = new JSONObject();
            routingRule.put("type", "field");
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
        if (rule.matchType == XrayRoutingRule.MatchType.NETWORK) {
            // Xray "network" is a comma-joined string, e.g. "tcp", "udp", "tcp,udp".
            String network = joinLowerTokens(rule.code);
            if (TextUtils.isEmpty(network)) {
                return false;
            }
            routingRule.put("network", network);
            return true;
        }
        if (rule.matchType == XrayRoutingRule.MatchType.PROTOCOL) {
            // Xray "protocol" is an array, e.g. ["bittorrent"]. Needs sniffing on.
            JSONArray protocols = new JSONArray();
            for (String token : rule.code.split("[,\\s]+")) {
                String normalized = trim(token).toLowerCase(java.util.Locale.ROOT);
                if (!TextUtils.isEmpty(normalized)) {
                    protocols.put(normalized);
                }
            }
            if (protocols.length() == 0) {
                return false;
            }
            routingRule.put("protocol", protocols);
            return true;
        }
        return false;
    }

    private static String joinLowerTokens(String value) {
        StringBuilder builder = new StringBuilder();
        for (String token : value.split("[,\\s]+")) {
            String normalized = trim(token).toLowerCase(java.util.Locale.ROOT);
            if (TextUtils.isEmpty(normalized)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(normalized);
        }
        return builder.toString();
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

    /**
     * Plan for resolving direct-routed domains via the provider DNS on the
     * physical network. Null when there are no direct domain rules or no usable
     * physical resolver (then everything keeps resolving through the tunnel).
     */
    @Nullable
    private static DirectDnsPlan computeDirectDnsPlan(Context context) {
        List<String> domains = directRoutedDomainMatchers(context);
        if (domains.isEmpty()) {
            return null;
        }
        List<String> dnsServers = physicalDnsServers(context);
        if (dnsServers.isEmpty()) {
            return null;
        }
        return new DirectDnsPlan(domains, dnsServers);
    }

    private static List<String> directRoutedDomainMatchers(Context context) {
        List<String> matchers = new ArrayList<>();
        try {
            for (XrayRoutingRule rule : XrayRoutingStore.getValidRules(context)) {
                if (rule == null || !rule.enabled || rule.action != XrayRoutingRule.Action.DIRECT) {
                    continue;
                }
                if (rule.matchType == XrayRoutingRule.MatchType.GEOSITE) {
                    if (!TextUtils.isEmpty(rule.code)) {
                        matchers.add("geosite:" + rule.code);
                    }
                } else if (rule.matchType == XrayRoutingRule.MatchType.DOMAIN) {
                    JSONArray domainRules = buildRoutingValueArray(rule.code, true);
                    for (int index = 0; index < domainRules.length(); index++) {
                        String matcher = domainRules.optString(index);
                        if (!TextUtils.isEmpty(matcher)) {
                            matchers.add(matcher);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return matchers;
    }

    private static List<String> physicalDnsServers(Context context) {
        List<String> result = new ArrayList<>();
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            );
            if (connectivityManager == null) {
                return result;
            }
            Network network = DirectNetworkConnection.findUsablePhysicalNetwork(context);
            if (network == null) {
                return result;
            }
            LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
            if (linkProperties == null) {
                return result;
            }
            for (InetAddress address : linkProperties.getDnsServers()) {
                String host = address == null ? "" : address.getHostAddress();
                if (TextUtils.isEmpty(host) || host.contains("%")) {
                    continue;
                }
                String lower = host.toLowerCase(Locale.ROOT);
                if (lower.startsWith("fe80") || lower.startsWith("127.") || "::1".equals(lower)) {
                    continue;
                }
                if (!result.contains(host)) {
                    result.add(host);
                }
            }
        } catch (RuntimeException ignored) {}
        return result;
    }

    private static final class DirectDnsPlan {

        private final List<String> domains;
        private final List<String> dnsServers;

        DirectDnsPlan(List<String> domains, List<String> dnsServers) {
            this.domains = domains;
            this.dnsServers = dnsServers;
        }
    }

    private static JSONObject buildSniffing(XraySettings settings) throws Exception {
        JSONObject sniffing = new JSONObject();
        sniffing.put("enabled", settings.sniffingEnabled);
        sniffing.put("destOverride", new JSONArray().put("http").put("tls").put("quic"));
        return sniffing;
    }

    // Mirrors Android's per-app VPN routing into xray's TUN inbound as a
    // secondary gVisor-level filter. Android's addDisallowedApplication does
    // not reliably stop excluded apps from binding to the tun interface
    // directly, so we look up the UID of every new TCP/UDP connection inside
    // xray and drop it when it does not match. See
    // external/Xray-core/proxy/tun/uid_lookup_linux.go for the lookup, and
    // proxy/tun/stack_gvisor.go for the enforcement point.
    // Maps the unknown-UID router/policy settings onto the gVisor TUN filter
    // flags: router off -> let unresolved-UID connections fall through to the
    // tunnel; router on + "direct" -> divert them to the bypass inbound (direct);
    // router on + "drop" -> leave both flags unset so the filter drops them.
    private static void applyUnknownUidPolicy(JSONObject tunSettings, XraySettings xraySettings) throws Exception {
        if (xraySettings == null) {
            return;
        }
        if (!xraySettings.tunUnknownUidRouter) {
            tunSettings.put("tunnelUnknownUid", true);
        } else if ("direct".equals(xraySettings.tunUnknownUidPolicy)) {
            tunSettings.put("bypassUnknownUid", true);
        }
    }

    private static void applyTunUidFilter(Context context, JSONObject tunSettings, XraySettings xraySettings)
        throws Exception {
        if (context == null) {
            return;
        }
        wings.v.core.AppRoutingMode routingMode = AppPrefs.getAppRoutingMode(context);
        if (routingMode == wings.v.core.AppRoutingMode.BYPASS) {
            // Plain Bypass excludes the selected apps at the VpnService layer
            // (addDisallowedApplication); the gVisor TUN UID filter is only used by
            // XBYPASS (divert) and WHITELIST (allowlist).
            android.util.Log.i(
                "WINGSV-Xray",
                "applyTunUidFilter: plain BYPASS uses VpnService disallow, no gVisor filter"
            );
            return;
        }
        Set<String> packages = AppPrefs.getEffectiveAppRoutingPackages(context);
        if (packages == null || packages.isEmpty()) {
            android.util.Log.i(
                "WINGSV-Xray",
                "applyTunUidFilter: no effective app-routing packages, gVisor UID filter disabled"
            );
            return;
        }
        List<Integer> uids = wings.v.core.XrayTproxyRouter.resolveRoutedUids(context, packages);
        if (uids.isEmpty()) {
            return;
        }
        JSONArray uidArray = new JSONArray();
        for (Integer uid : uids) {
            if (uid != null && uid > 0) {
                uidArray.put(uid.longValue());
            }
        }
        if (uidArray.length() == 0) {
            return;
        }
        if (routingMode == wings.v.core.AppRoutingMode.XBYPASS) {
            // XBypass: tag listed-UID connections with bypass_inbound_tag
            // so xray routing diverts them to the freedom/direct outbound
            // (= underlying network via protect-bridge). Combined with the
            // VpnService running WITHOUT addDisallowedApplication, all apps'
            // traffic enters the tunnel and ConnectivityManager
            // .getConnectionOwnerUid resolves their UIDs - including
            // bypass apps that try --interface tun0 tricks. Their packets
            // are redirected back to the underlying network at gVisor
            // level, transparently to the app, instead of being dropped.
            tunSettings.put("bypassUids", uidArray);
            tunSettings.put("bypassInboundTag", TUN_BYPASS_TAG);
            // Optional: also bypass connections whose UID could not be
            // resolved (Android's API returns INVALID_UID for SO_BINDTODEVICE
            // / explicit source-IP-bind leakers like `curl --interface tun0`
            // because Android never registered the connection as VPN-tracked).
            // Closes the deliberate-leak path at the cost of also routing
            // system services that happen to not have a UID through direct.
            applyUnknownUidPolicy(tunSettings, xraySettings);
        } else if (routingMode == wings.v.core.AppRoutingMode.XWHITELIST) {
            // XWhitelist (inverse of XBypass): keep every app inside the tunnel
            // but tunnel ONLY the listed UIDs; divert the rest to direct at the
            // gVisor level. allowedUids restricts the tunnel to the listed UIDs,
            // and bypass_inbound_tag makes the core re-route the non-listed (and,
            // when opted-in, unknown) UIDs to the direct/freedom outbound instead
            // of dropping them - so non-selected apps keep working while the
            // SO_BINDTODEVICE escape path is still closed.
            tunSettings.put("allowedUids", uidArray);
            tunSettings.put("bypassInboundTag", TUN_BYPASS_TAG);
            applyUnknownUidPolicy(tunSettings, xraySettings);
        } else {
            // Plain Whitelist: only listed packages are tunneled. The VpnService
            // addAllowedApplication already keeps the rest out of the tunnel, so
            // the gVisor allowlist only needs to drop anything that still slips in.
            tunSettings.put("allowedUids", uidArray);
            // Let connections whose UID cannot be resolved fall through to the
            // tunnel instead of being dropped. addAllowedApplication already
            // confines the tunnel to the selected apps, so the only traffic that
            // reaches tun with an unresolvable UID is the platform itself (the
            // system DoT / Private DNS resolver and similar netd/network-stack
            // connections that getConnectionOwnerUid cannot attribute). The core
            // started dropping unknown-UID connections once a UID filter is
            // active, which black-holed the system DoT resolver and broke
            // Private DNS in Whitelist mode; matching the pre-filter behaviour
            // here keeps the per-app whitelist intact while restoring system DoT.
            // (XBypass/XWhitelist use bypassInboundTag + the unknown-uid policy
            // instead, so they are unaffected and untouched.)
            tunSettings.put("tunnelUnknownUid", true);
        }
        android.util.Log.i(
            "WINGSV-Xray",
            "applyTunUidFilter: mode=" + routingMode.prefValue + " uids=" + uids + " packages=" + packages
        );
        // Unresolved /proc/net UID is now always treated as a filter failure
        // and the connection is dropped, closing the SO_BINDTODEVICE escape
        // route. To absorb the race between an app opening sockets in rapid
        // succession (Tor pulling 6+ guards at once, SSH multiplexing) and
        // the kernel publishing those sockets in /proc/net/tcp*, gVisor will
        // retry the lookup every ~5 ms for up to uidLookupTimeoutMs before
        // dropping. 0 disables retries entirely.
        if (xraySettings != null && xraySettings.tunUidLookupTimeoutMs > 0) {
            tunSettings.put("uidLookupTimeoutMs", xraySettings.tunUidLookupTimeoutMs);
        }
    }

    static boolean isLocalProxyEnabled(XraySettings settings) {
        return settings != null && settings.localProxyEnabled && settings.localProxyPort > 0;
    }

    static boolean isHttpProxyEnabled(XraySettings settings) {
        return settings != null && settings.httpProxyEnabled && settings.httpProxyPort > 0;
    }

    static String resolveSocksListenAddress(XraySettings settings) {
        String configured = settings == null ? null : trim(settings.localProxyListenAddress);
        if (!TextUtils.isEmpty(configured)) {
            return configured;
        }
        return settings != null && settings.allowLan ? "0.0.0.0" : DEFAULT_LOOPBACK_LISTEN;
    }

    static String resolveHttpListenAddress(XraySettings settings) {
        String configured = settings == null ? null : trim(settings.httpProxyListenAddress);
        if (!TextUtils.isEmpty(configured)) {
            return configured;
        }
        return settings != null && settings.allowLan ? "0.0.0.0" : DEFAULT_LOOPBACK_LISTEN;
    }

    static JSONObject buildHttpInboundSettings(XraySettings settings) throws Exception {
        JSONObject httpSettings = new JSONObject();
        if (
            settings != null &&
            settings.httpProxyAuthEnabled &&
            !TextUtils.isEmpty(trim(settings.httpProxyUsername)) &&
            !TextUtils.isEmpty(trim(settings.httpProxyPassword))
        ) {
            httpSettings.put(
                "accounts",
                new JSONArray().put(
                    new JSONObject()
                        .put("user", trim(settings.httpProxyUsername))
                        .put("pass", trim(settings.httpProxyPassword))
                )
            );
        }
        return httpSettings;
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

    static void rewritePrimaryOutboundEndpoint(JSONObject outbound, String host, int port) throws Exception {
        JSONObject settings = outbound == null ? null : outbound.optJSONObject("settings");
        if (settings == null || TextUtils.isEmpty(trim(host)) || port <= 0) {
            throw new IllegalStateException(
                WingsApplication.getStringSafe(R.string.xray_config_outbound_override_failed)
            );
        }
        if (
            rewriteEndpointArray(settings.optJSONArray("vnext"), trim(host), port) ||
            rewriteEndpointArray(settings.optJSONArray("servers"), trim(host), port)
        ) {
            return;
        }
        if (settings.has("address")) {
            settings.put("address", trim(host));
            settings.put("port", port);
            return;
        }
        throw new IllegalStateException(WingsApplication.getStringSafe(R.string.xray_config_outbound_no_vk_turn_relay));
    }

    private static boolean rewriteEndpointArray(JSONArray servers, String host, int port) throws Exception {
        if (servers == null || servers.length() == 0) {
            return false;
        }
        JSONObject server = servers.optJSONObject(0);
        if (server == null) {
            return false;
        }
        server.put("address", host);
        server.put("port", port);
        return true;
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
