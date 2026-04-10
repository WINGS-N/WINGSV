package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.amnezia.awg.config.Config;
import org.json.JSONObject;
import wings.v.proto.WingsvProto;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.SignatureDeclareThrowsException",
        "PMD.CommentRequired",
        "PMD.LongVariable",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.LawOfDemeter",
    }
)
public final class WingsImportParser {

    private static final Pattern LINK_PATTERN = Pattern.compile("wingsv://[A-Za-z0-9_\\-+/=]+");
    private static final Pattern SUBSCRIPTION_URL_PATTERN = Pattern.compile("https?://[^\\s\"']+");
    private static final String SCHEME_PREFIX = "wingsv://";
    private static final int CURRENT_VERSION = 1;
    private static final byte FORMAT_PROTOBUF_DEFLATE = 0x12;

    private static final int DEFAULT_THREADS = 8;
    private static final boolean DEFAULT_USE_UDP = true;
    private static final boolean DEFAULT_NO_OBFUSCATION = false;
    private static final String DEFAULT_SESSION_MODE = "auto";
    private static final String DEFAULT_LOCAL_ENDPOINT = "127.0.0.1:9000";
    private static final String DEFAULT_WG_DNS = "1.1.1.1, 1.0.0.1";
    private static final int DEFAULT_WG_MTU = 1280;
    private static final String DEFAULT_ALLOWED_IPS = "0.0.0.0/0, ::/0";
    private static final int DEFAULT_SUBSCRIPTION_REFRESH_HOURS = 24;

    private WingsImportParser() {}

    public static String buildLink(ProxySettings settings) throws Exception {
        return buildLink(null, settings);
    }

    public static String buildLink(Context context, ProxySettings settings) throws Exception {
        WingsvProto.Config config = buildProtoConfig(context, settings);
        return encodeConfig(config);
    }

    public static String buildXrayProfilesLink(Context context, List<XrayProfile> profiles, String activeProfileId)
        throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("Context is required");
        }
        LinkedHashMap<String, XrayProfile> dedupedProfiles = new LinkedHashMap<>();
        if (profiles != null) {
            for (XrayProfile profile : profiles) {
                if (profile == null || TextUtils.isEmpty(profile.rawLink)) {
                    continue;
                }
                dedupedProfiles.put(profile.stableDedupKey(), profile);
            }
        }
        if (dedupedProfiles.isEmpty()) {
            throw new IllegalArgumentException("No Xray profiles to export");
        }

        WingsvProto.Config.Builder config = WingsvProto.Config.newBuilder()
            .setVer(CURRENT_VERSION)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_XRAY)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_XRAY);

        WingsvProto.Xray.Builder xray = WingsvProto.Xray.newBuilder();
        xray.setMergeOnly(true);
        String resolvedActiveId = value(activeProfileId);
        if (TextUtils.isEmpty(resolvedActiveId)) {
            resolvedActiveId = dedupedProfiles.values().iterator().next().id;
        }
        xray.setActiveProfileId(resolvedActiveId);
        for (XrayProfile profile : dedupedProfiles.values()) {
            xray.addProfiles(toProtoProfile(profile));
        }

        LinkedHashMap<String, XraySubscription> matchingSubscriptions = new LinkedHashMap<>();
        for (XrayProfile profile : dedupedProfiles.values()) {
            if (TextUtils.isEmpty(profile.subscriptionId)) {
                continue;
            }
            matchingSubscriptions.put(profile.subscriptionId, null);
        }
        if (!matchingSubscriptions.isEmpty()) {
            for (XraySubscription subscription : XrayStore.getSubscriptions(context)) {
                if (subscription == null || TextUtils.isEmpty(subscription.id)) {
                    continue;
                }
                if (!matchingSubscriptions.containsKey(subscription.id)) {
                    continue;
                }
                WingsvProto.Subscription.Builder subscriptionBuilder = WingsvProto.Subscription.newBuilder()
                    .setId(value(subscription.id))
                    .setTitle(value(subscription.title))
                    .setUrl(value(subscription.url))
                    .setFormatHint(value(subscription.formatHint));
                if (subscription.refreshIntervalHours > 0) {
                    subscriptionBuilder.setRefreshIntervalHours(subscription.refreshIntervalHours);
                }
                if (!subscription.autoUpdate) {
                    subscriptionBuilder.setAutoUpdate(false);
                }
                if (subscription.lastUpdatedAt > 0L) {
                    subscriptionBuilder.setLastUpdatedAt(subscription.lastUpdatedAt);
                }
                xray.addSubscriptions(subscriptionBuilder.build());
            }
        }

        config.setXray(xray.build());
        return encodeConfig(config.build());
    }

    public static String buildSingleXrayProfileLink(XrayProfile profile) throws Exception {
        if (profile == null || TextUtils.isEmpty(profile.rawLink)) {
            throw new IllegalArgumentException("No active Xray profile to export");
        }

        WingsvProto.Config.Builder config = WingsvProto.Config.newBuilder()
            .setVer(CURRENT_VERSION)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_XRAY)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_XRAY);

        WingsvProto.Xray.Builder xray = WingsvProto.Xray.newBuilder()
            .setMergeOnly(true)
            .setActiveProfileId(value(profile.id))
            .addProfiles(toProtoProfile(profile));

        config.setXray(xray.build());
        return encodeConfig(config.build());
    }

    public static String buildXraySubscriptionsLink(Context context, List<XraySubscription> subscriptions)
        throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("Context is required");
        }
        LinkedHashMap<String, XraySubscription> dedupedSubscriptions = new LinkedHashMap<>();
        if (subscriptions != null) {
            for (XraySubscription subscription : subscriptions) {
                if (subscription == null || TextUtils.isEmpty(subscription.url)) {
                    continue;
                }
                dedupedSubscriptions.put(subscription.stableDedupKey(), subscription);
            }
        }
        if (dedupedSubscriptions.isEmpty()) {
            throw new IllegalArgumentException("No Xray subscriptions to export");
        }

        WingsvProto.Config.Builder config = WingsvProto.Config.newBuilder()
            .setVer(CURRENT_VERSION)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_XRAY)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_XRAY);

        WingsvProto.Xray.Builder xray = WingsvProto.Xray.newBuilder().setMergeOnly(true);
        for (XraySubscription subscription : dedupedSubscriptions.values()) {
            WingsvProto.Subscription.Builder subscriptionBuilder = WingsvProto.Subscription.newBuilder()
                .setId(value(subscription.id))
                .setTitle(value(subscription.title))
                .setUrl(value(subscription.url))
                .setFormatHint(value(subscription.formatHint));
            if (subscription.refreshIntervalHours > 0) {
                subscriptionBuilder.setRefreshIntervalHours(subscription.refreshIntervalHours);
            }
            if (!subscription.autoUpdate) {
                subscriptionBuilder.setAutoUpdate(false);
            }
            if (subscription.lastUpdatedAt > 0L) {
                subscriptionBuilder.setLastUpdatedAt(subscription.lastUpdatedAt);
            }
            xray.addSubscriptions(subscriptionBuilder.build());
        }
        config.setXray(xray.build());
        return encodeConfig(config.build());
    }

    private static String encodeConfig(WingsvProto.Config config) {
        byte[] protobufPayload = config.toByteArray();
        byte[] compressedPayload = deflate(protobufPayload);
        byte[] framedPayload = new byte[compressedPayload.length + 1];
        framedPayload[0] = FORMAT_PROTOBUF_DEFLATE;
        System.arraycopy(compressedPayload, 0, framedPayload, 1, compressedPayload.length);
        return SCHEME_PREFIX + Base64.encodeToString(framedPayload, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    public static ImportedConfig parseFromText(String rawText) throws Exception {
        XrayProfile directProfile = VlessLinkParser.parseProfile(rawText, "", "");
        if (directProfile != null) {
            ImportedConfig directImport = new ImportedConfig();
            directImport.backendType = BackendType.XRAY;
            directImport.xrayMergeOnly = true;
            directImport.xrayProfiles.add(directProfile);
            directImport.activeXrayProfileId = directProfile.id;
            directImport.xraySettings = defaultXraySettings();
            return directImport;
        }
        if (looksLikeAmneziaQuickConfig(rawText)) {
            ImportedConfig directImport = new ImportedConfig();
            directImport.backendType = BackendType.AMNEZIAWG_PLAIN;
            directImport.awgQuickConfig = rawText.trim();
            return directImport;
        }
        List<XraySubscription> subscriptionImports = parseSubscriptionImports(rawText);
        if (!subscriptionImports.isEmpty()) {
            ImportedConfig directImport = new ImportedConfig();
            directImport.backendType = BackendType.XRAY;
            directImport.xrayMergeOnly = true;
            directImport.xraySubscriptions.addAll(subscriptionImports);
            directImport.xraySettings = defaultXraySettings();
            return directImport;
        }
        String link = extractLink(rawText);
        if (TextUtils.isEmpty(link)) {
            throw new IllegalArgumentException("WINGSV ссылка не найдена");
        }

        byte[] decodedPayload = decodePayload(link);
        if (decodedPayload.length == 0) {
            throw new IllegalArgumentException("WINGSV payload пуст");
        }

        if (decodedPayload[0] == FORMAT_PROTOBUF_DEFLATE) {
            byte[] protobufPayload = inflate(slice(decodedPayload, 1, decodedPayload.length));
            return parseProtoConfig(WingsvProto.Config.parseFrom(protobufPayload));
        }
        if (isLikelyJsonPayload(decodedPayload)) {
            return parseJsonPayload(decodedPayload);
        }
        throw new IllegalArgumentException("Неподдерживаемый формат WINGSV ссылки");
    }

    public static boolean isSubscriptionOnlyXrayImport(ImportedConfig importedConfig) {
        return (
            importedConfig != null &&
            importedConfig.backendType == BackendType.XRAY &&
            importedConfig.xrayMergeOnly &&
            !importedConfig.xraySubscriptions.isEmpty() &&
            importedConfig.xrayProfiles.isEmpty()
        );
    }

    public static String extractLink(String rawText) {
        if (TextUtils.isEmpty(rawText)) {
            return null;
        }
        Matcher matcher = LINK_PATTERN.matcher(rawText);
        if (matcher.find()) {
            return matcher.group();
        }
        if (rawText.startsWith(SCHEME_PREFIX)) {
            return rawText.trim();
        }
        return null;
    }

    private static List<XraySubscription> parseSubscriptionImports(String rawText) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        Matcher matcher = SUBSCRIPTION_URL_PATTERN.matcher(value(rawText));
        while (matcher.find()) {
            String candidate = value(matcher.group());
            if (isValidSubscriptionUrl(candidate)) {
                urls.add(candidate);
            }
        }
        ArrayList<XraySubscription> subscriptions = new ArrayList<>();
        for (String url : urls) {
            subscriptions.add(
                new XraySubscription(
                    null,
                    deriveSubscriptionTitle(url),
                    url,
                    "auto",
                    DEFAULT_SUBSCRIPTION_REFRESH_HOURS,
                    true,
                    0L
                )
            );
        }
        return subscriptions;
    }

    private static boolean isValidSubscriptionUrl(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) {
            return false;
        }
        try {
            URI uri = URI.create(rawUrl);
            String scheme = value(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return false;
            }
            return !TextUtils.isEmpty(value(uri.getHost()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String deriveSubscriptionTitle(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String host = value(uri.getHost());
            if (!TextUtils.isEmpty(host)) {
                return host;
            }
        } catch (RuntimeException ignored) {}
        return "Imported";
    }

    private static WingsvProto.Config buildProtoConfig(Context context, ProxySettings settings) throws Exception {
        BackendType backendType =
            settings != null && settings.backendType != null ? settings.backendType : BackendType.VK_TURN_WIREGUARD;
        WingsvProto.Config.Builder builder = WingsvProto.Config.newBuilder()
            .setVer(CURRENT_VERSION)
            .setBackend(backendType.toProto())
            .setType(
                backendType == BackendType.XRAY
                    ? WingsvProto.ConfigType.CONFIG_TYPE_XRAY
                    : backendType != null && backendType.usesAmneziaSettings()
                        ? WingsvProto.ConfigType.CONFIG_TYPE_AMNEZIAWG
                        : WingsvProto.ConfigType.CONFIG_TYPE_VK
            );

        if (settings != null && backendType == BackendType.XRAY) {
            WingsvProto.Xray xray = buildXray(context, settings);
            if (!xray.equals(WingsvProto.Xray.getDefaultInstance())) {
                builder.setXray(xray);
            }
            return builder.build();
        }
        if (settings != null && backendType != null && backendType.usesAmneziaSettings()) {
            WingsvProto.AmneziaWG.Builder awg = WingsvProto.AmneziaWG.newBuilder();
            if (!TextUtils.isEmpty(value(settings.awgQuickConfig))) {
                awg.setAwgQuickConfig(value(settings.awgQuickConfig));
            }
            WingsvProto.AmneziaWG awgMessage = awg.build();
            if (!awgMessage.equals(WingsvProto.AmneziaWG.getDefaultInstance())) {
                builder.setAwg(awgMessage);
            }
            return builder.build();
        }

        if (settings != null) {
            WingsvProto.Turn turn = buildTurn(settings);
            if (!turn.equals(WingsvProto.Turn.getDefaultInstance())) {
                builder.setTurn(turn);
            }
            WingsvProto.WireGuard wg = buildWireGuard(settings);
            if (!wg.equals(WingsvProto.WireGuard.getDefaultInstance())) {
                builder.setWg(wg);
            }
        }
        return builder.build();
    }

    private static WingsvProto.Xray buildXray(Context context, ProxySettings settings) {
        WingsvProto.Xray.Builder builder = WingsvProto.Xray.newBuilder();
        XrayProfile activeProfile = settings.activeXrayProfile;
        LinkedHashMap<String, XrayProfile> profiles = new LinkedHashMap<>();
        if (context != null) {
            for (XrayProfile profile : XrayStore.getProfiles(context)) {
                if (profile != null && !TextUtils.isEmpty(profile.rawLink)) {
                    profiles.put(profile.stableDedupKey(), profile);
                }
            }
        }
        if (activeProfile != null && !TextUtils.isEmpty(activeProfile.rawLink)) {
            profiles.put(activeProfile.stableDedupKey(), activeProfile);
        }
        String activeProfileId = activeProfile != null ? value(activeProfile.id) : "";
        if (TextUtils.isEmpty(activeProfileId) && context != null) {
            activeProfileId = value(XrayStore.getActiveProfileId(context));
        }
        if (!TextUtils.isEmpty(activeProfileId)) {
            builder.setActiveProfileId(activeProfileId);
        }
        for (XrayProfile profile : profiles.values()) {
            builder.addProfiles(toProtoProfile(profile));
        }
        XraySettings xraySettings = settings.xraySettings;
        if (xraySettings != null) {
            WingsvProto.XraySettings protoSettings = toProtoXraySettings(xraySettings);
            if (!protoSettings.equals(WingsvProto.XraySettings.getDefaultInstance())) {
                builder.setSettings(protoSettings);
            }
        }
        if (context != null) {
            for (XraySubscription subscription : XrayStore.getSubscriptions(context)) {
                if (subscription == null || TextUtils.isEmpty(subscription.url)) {
                    continue;
                }
                WingsvProto.Subscription.Builder subscriptionBuilder = WingsvProto.Subscription.newBuilder()
                    .setId(value(subscription.id))
                    .setTitle(value(subscription.title))
                    .setUrl(value(subscription.url))
                    .setFormatHint(value(subscription.formatHint));
                if (subscription.refreshIntervalHours > 0) {
                    subscriptionBuilder.setRefreshIntervalHours(subscription.refreshIntervalHours);
                }
                if (!subscription.autoUpdate) {
                    subscriptionBuilder.setAutoUpdate(false);
                }
                if (subscription.lastUpdatedAt > 0L) {
                    subscriptionBuilder.setLastUpdatedAt(subscription.lastUpdatedAt);
                }
                builder.addSubscriptions(subscriptionBuilder.build());
            }
            String importedSubscriptionJson = XrayStore.getImportedSubscriptionJson(context);
            if (!TextUtils.isEmpty(importedSubscriptionJson)) {
                builder.setSubscriptionJson(importedSubscriptionJson);
            }
        }
        return builder.build();
    }

    private static WingsvProto.Turn buildTurn(ProxySettings settings) throws Exception {
        WingsvProto.Turn.Builder builder = WingsvProto.Turn.newBuilder();
        setEndpoint(builder, value(settings.endpoint), true);
        if (!TextUtils.isEmpty(value(settings.vkLink))) {
            builder.setLink(value(settings.vkLink));
        }
        if (settings.threads > 0 && settings.threads != DEFAULT_THREADS) {
            builder.setThreads(settings.threads);
        }
        if (settings.useUdp != DEFAULT_USE_UDP) {
            builder.setUseUdp(settings.useUdp);
        }
        if (settings.noObfuscation != DEFAULT_NO_OBFUSCATION) {
            builder.setNoObfuscation(settings.noObfuscation);
        }
        String sessionMode = value(settings.turnSessionMode);
        WingsvProto.TurnSessionMode protoSessionMode = toProtoSessionMode(sessionMode);
        if (
            protoSessionMode != WingsvProto.TurnSessionMode.TURN_SESSION_MODE_AUTO &&
            protoSessionMode != WingsvProto.TurnSessionMode.TURN_SESSION_MODE_UNSPECIFIED
        ) {
            builder.setSessionMode(protoSessionMode);
        }
        String localEndpoint = value(settings.localEndpoint);
        if (!TextUtils.isEmpty(localEndpoint) && !DEFAULT_LOCAL_ENDPOINT.equals(localEndpoint)) {
            setEndpoint(builder, localEndpoint, false);
        }
        if (!TextUtils.isEmpty(value(settings.turnHost))) {
            builder.setHost(value(settings.turnHost));
        }
        if (!TextUtils.isEmpty(value(settings.turnPort))) {
            try {
                builder.setPort(Integer.parseInt(value(settings.turnPort)));
            } catch (NumberFormatException ignored) {}
        }
        return builder.build();
    }

    private static void setEndpoint(WingsvProto.Turn.Builder builder, String endpoint, boolean remote)
        throws Exception {
        WingsvProto.Endpoint parsed = parseEndpoint(endpoint);
        if (parsed == null) {
            return;
        }
        if (remote) {
            builder.setEndpoint(parsed);
        } else {
            builder.setLocalEndpoint(parsed);
        }
    }

    private static WingsvProto.WireGuard buildWireGuard(ProxySettings settings) throws Exception {
        WingsvProto.WireGuard.Builder builder = WingsvProto.WireGuard.newBuilder();

        WingsvProto.Interface.Builder iface = WingsvProto.Interface.newBuilder();
        if (!TextUtils.isEmpty(value(settings.wgPrivateKey))) {
            iface.setPrivateKey(
                com.google.protobuf.ByteString.copyFrom(decodeWireGuardKey(value(settings.wgPrivateKey)))
            );
        }
        for (String address : splitCsv(value(settings.wgAddresses))) {
            iface.addAddrs(address);
        }
        String dns = value(settings.wgDns);
        if (!TextUtils.isEmpty(dns) && !DEFAULT_WG_DNS.equals(dns)) {
            for (String entry : splitCsv(dns)) {
                iface.addDns(entry);
            }
        }
        if (settings.wgMtu > 0 && settings.wgMtu != DEFAULT_WG_MTU) {
            iface.setMtu(settings.wgMtu);
        }
        WingsvProto.Interface ifaceMessage = iface.build();
        if (!ifaceMessage.equals(WingsvProto.Interface.getDefaultInstance())) {
            builder.setIface(ifaceMessage);
        }

        WingsvProto.Peer.Builder peer = WingsvProto.Peer.newBuilder();
        if (!TextUtils.isEmpty(value(settings.wgPublicKey))) {
            peer.setPublicKey(com.google.protobuf.ByteString.copyFrom(decodeWireGuardKey(value(settings.wgPublicKey))));
        }
        if (!TextUtils.isEmpty(value(settings.wgPresharedKey))) {
            peer.setPresharedKey(
                com.google.protobuf.ByteString.copyFrom(decodeWireGuardKey(value(settings.wgPresharedKey)))
            );
        }
        String allowedIps = value(settings.wgAllowedIps);
        if (!TextUtils.isEmpty(allowedIps) && !DEFAULT_ALLOWED_IPS.equals(allowedIps)) {
            for (String cidr : splitCsv(allowedIps)) {
                WingsvProto.Cidr parsed = parseCidr(cidr);
                if (parsed != null) {
                    peer.addAllowedIps(parsed);
                }
            }
        }
        WingsvProto.Peer peerMessage = peer.build();
        if (!peerMessage.equals(WingsvProto.Peer.getDefaultInstance())) {
            builder.setPeer(peerMessage);
        }

        return builder.build();
    }

    private static ImportedConfig parseProtoConfig(WingsvProto.Config config) throws Exception {
        if (config.getVer() <= 0) {
            throw new IllegalArgumentException("Отсутствует или некорректен ver");
        }

        ImportedConfig importedConfig = new ImportedConfig();
        importedConfig.backendType = BackendType.fromProto(config.getBackend());
        if (
            config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_XRAY ||
            importedConfig.backendType == BackendType.XRAY ||
            config.hasXray()
        ) {
            importedConfig.backendType = BackendType.XRAY;
            parseXray(config, importedConfig);
            return importedConfig;
        }
        if (
            config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_AMNEZIAWG ||
            importedConfig.backendType == BackendType.AMNEZIAWG ||
            importedConfig.backendType == BackendType.AMNEZIAWG_PLAIN ||
            config.hasAwg()
        ) {
            if (importedConfig.backendType != BackendType.AMNEZIAWG_PLAIN) {
                importedConfig.backendType = BackendType.AMNEZIAWG;
            }
            if (config.hasAwg()) {
                importedConfig.awgQuickConfig = value(config.getAwg().getAwgQuickConfig());
            }
            return importedConfig;
        }
        if (config.getType() != WingsvProto.ConfigType.CONFIG_TYPE_VK) {
            throw new IllegalArgumentException("Поддерживается только type=vk/xray/amneziawg");
        }

        if (config.hasTurn()) {
            WingsvProto.Turn turn = config.getTurn();
            if (turn.hasEndpoint()) {
                importedConfig.endpoint = formatEndpoint(turn.getEndpoint());
            }
            importedConfig.link = value(turn.getLink());
            if (turn.hasThreads()) {
                importedConfig.threads = turn.getThreads();
            }
            if (turn.hasUseUdp()) {
                importedConfig.useUdp = turn.getUseUdp();
            }
            if (turn.hasNoObfuscation()) {
                importedConfig.noObfuscation = turn.getNoObfuscation();
            }
            if (turn.getSessionMode() != WingsvProto.TurnSessionMode.TURN_SESSION_MODE_UNSPECIFIED) {
                importedConfig.turnSessionMode = fromProtoSessionMode(turn.getSessionMode());
            }
            if (turn.hasLocalEndpoint()) {
                importedConfig.localEndpoint = formatEndpoint(turn.getLocalEndpoint());
            }
            importedConfig.turnHost = value(turn.getHost());
            if (turn.hasPort()) {
                importedConfig.turnPort = String.valueOf(turn.getPort());
            }
        }

        if (config.hasWg()) {
            WingsvProto.WireGuard wg = config.getWg();
            if (wg.hasIface()) {
                WingsvProto.Interface iface = wg.getIface();
                if (!iface.getPrivateKey().isEmpty()) {
                    importedConfig.wgPrivateKey = encodeWireGuardKey(iface.getPrivateKey().toByteArray());
                }
                if (iface.getAddrsCount() > 0) {
                    importedConfig.wgAddresses = TextUtils.join(", ", iface.getAddrsList());
                }
                if (iface.getDnsCount() > 0) {
                    importedConfig.wgDns = TextUtils.join(", ", iface.getDnsList());
                }
                if (iface.hasMtu()) {
                    importedConfig.wgMtu = iface.getMtu();
                }
            }
            if (wg.hasPeer()) {
                WingsvProto.Peer peer = wg.getPeer();
                if (!peer.getPublicKey().isEmpty()) {
                    importedConfig.wgPublicKey = encodeWireGuardKey(peer.getPublicKey().toByteArray());
                }
                if (!peer.getPresharedKey().isEmpty()) {
                    importedConfig.wgPresharedKey = encodeWireGuardKey(peer.getPresharedKey().toByteArray());
                }
                if (peer.getAllowedIpsCount() > 0) {
                    List<String> cidrs = new ArrayList<>(peer.getAllowedIpsCount());
                    for (WingsvProto.Cidr cidr : peer.getAllowedIpsList()) {
                        cidrs.add(formatCidr(cidr));
                    }
                    importedConfig.wgAllowedIps = TextUtils.join(", ", cidrs);
                }
            }
        }
        return importedConfig;
    }

    private static void parseXray(WingsvProto.Config config, ImportedConfig importedConfig) {
        WingsvProto.Xray xray = config.hasXray() ? config.getXray() : WingsvProto.Xray.getDefaultInstance();
        importedConfig.xrayMergeOnly = xray.hasMergeOnly() && xray.getMergeOnly();
        importedConfig.xraySettings = xray.hasSettings()
            ? fromProtoXraySettings(xray.getSettings())
            : defaultXraySettings();
        importedConfig.activeXrayProfileId = value(xray.getActiveProfileId());
        importedConfig.xraySubscriptionJson = value(xray.getSubscriptionJson());
        if (xray.getSubscriptionsCount() > 0) {
            for (WingsvProto.Subscription subscription : xray.getSubscriptionsList()) {
                importedConfig.xraySubscriptions.add(fromProtoSubscription(subscription));
            }
        }
        if (xray.getProfilesCount() > 0) {
            for (WingsvProto.VlessProfile profile : xray.getProfilesList()) {
                importedConfig.xrayProfiles.add(fromProtoProfile(profile));
            }
        }
    }

    private static ImportedConfig parseJsonPayload(byte[] decodedPayload) throws Exception {
        JSONObject root = new JSONObject(new String(decodedPayload, StandardCharsets.UTF_8));
        int version = root.optInt("ver", -1);
        if (version <= 0) {
            throw new IllegalArgumentException("Отсутствует или некорректен ver");
        }
        String type = root.optString("type");
        if (!"vk".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Поддерживается только type=vk");
        }

        JSONObject turn = root.optJSONObject("turn");
        if (turn == null) {
            throw new IllegalArgumentException("Отсутствует turn объект");
        }

        ImportedConfig importedConfig = new ImportedConfig();
        importedConfig.backendType = BackendType.VK_TURN_WIREGUARD;
        importedConfig.endpoint = turn.optString("endpoint");
        importedConfig.link = turn.optString("link");
        if (turn.has("threads")) {
            importedConfig.threads = turn.optInt("threads");
        }
        if (turn.has("use_udp")) {
            importedConfig.useUdp = turn.optBoolean("use_udp");
        }
        if (turn.has("no_obfuscation")) {
            importedConfig.noObfuscation = turn.optBoolean("no_obfuscation");
        }
        importedConfig.turnSessionMode = turn.optString("session_mode");
        importedConfig.localEndpoint = turn.optString("local_endpoint");
        importedConfig.turnHost = turn.optString("host");
        importedConfig.turnPort = turn.optString("port");

        JSONObject wg = root.optJSONObject("wg");
        if (wg != null) {
            JSONObject iface = wg.optJSONObject("if");
            if (iface != null) {
                importedConfig.wgPrivateKey = iface.optString("private_key");
                importedConfig.wgAddresses = iface.optString("addrs");
                importedConfig.wgDns = iface.optString("dns");
                if (iface.has("mtu")) {
                    importedConfig.wgMtu = iface.optInt("mtu");
                }
            }

            JSONObject peer = wg.optJSONObject("peer");
            if (peer != null) {
                importedConfig.wgPublicKey = peer.optString("public_key");
                importedConfig.wgPresharedKey = peer.optString("preshared_key");
                importedConfig.wgAllowedIps = peer.optString("allowed_ips");
            }
        }

        return importedConfig;
    }

    private static WingsvProto.Endpoint parseEndpoint(String endpoint) throws Exception {
        if (TextUtils.isEmpty(endpoint) || !endpoint.contains(":")) {
            return null;
        }
        int separator = endpoint.lastIndexOf(':');
        String host = endpoint.substring(0, separator).trim();
        String portRaw = endpoint.substring(separator + 1).trim();
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(portRaw)) {
            return null;
        }
        try {
            return WingsvProto.Endpoint.newBuilder().setHost(host).setPort(Integer.parseInt(portRaw)).build();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatEndpoint(WingsvProto.Endpoint endpoint) {
        if (TextUtils.isEmpty(endpoint.getHost())) {
            return "";
        }
        return endpoint.getHost() + ":" + endpoint.getPort();
    }

    private static WingsvProto.Cidr parseCidr(String rawValue) throws Exception {
        if (TextUtils.isEmpty(rawValue)) {
            return null;
        }
        int separator = rawValue.indexOf('/');
        if (separator <= 0 || separator >= rawValue.length() - 1) {
            return null;
        }
        String address = rawValue.substring(0, separator).trim();
        String prefixRaw = rawValue.substring(separator + 1).trim();
        try {
            InetAddress inetAddress = InetAddress.getByName(address);
            int prefix = Integer.parseInt(prefixRaw);
            return WingsvProto.Cidr.newBuilder()
                .setAddr(com.google.protobuf.ByteString.copyFrom(inetAddress.getAddress()))
                .setPrefix(prefix)
                .build();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String formatCidr(WingsvProto.Cidr cidr) throws Exception {
        return InetAddress.getByAddress(cidr.getAddr().toByteArray()).getHostAddress() + "/" + cidr.getPrefix();
    }

    private static byte[] decodePayload(String link) {
        String payload = link.substring(SCHEME_PREFIX.length()).trim();
        while (payload.startsWith("/")) {
            payload = payload.substring(1);
        }
        payload = payload.replaceAll("\\s+", "");

        try {
            return Base64.decode(normalizePadding(payload), Base64.URL_SAFE);
        } catch (Exception ignored) {
            return Base64.decode(normalizePadding(payload), Base64.DEFAULT);
        }
    }

    private static boolean isLikelyJsonPayload(byte[] payload) {
        for (byte value : payload) {
            char c = (char) value;
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '{';
        }
        return false;
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            if (count <= 0) {
                break;
            }
            output.write(buffer, 0, count);
        }
        deflater.end();
        return output.toByteArray();
    }

    private static byte[] inflate(byte[] input) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (!inflater.finished()) {
            int count = inflater.inflate(buffer);
            if (count > 0) {
                output.write(buffer, 0, count);
                continue;
            }
            if (inflater.needsInput()) {
                break;
            }
            throw new IllegalArgumentException("Не удалось распаковать payload");
        }
        inflater.end();
        return output.toByteArray();
    }

    private static List<String> splitCsv(String rawValue) {
        List<String> values = new ArrayList<>();
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

    private static byte[] slice(byte[] source, int from, int to) {
        byte[] result = new byte[to - from];
        System.arraycopy(source, from, result, 0, result.length);
        return result;
    }

    private static byte[] decodeWireGuardKey(String value) {
        try {
            return Base64.decode(value, Base64.DEFAULT);
        } catch (Exception ignored) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String encodeWireGuardKey(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static String normalizePadding(String payload) {
        int mod = payload.length() % 4;
        if (mod == 0) {
            return payload;
        }
        StringBuilder builder = new StringBuilder(payload);
        for (int i = mod; i < 4; i++) {
            builder.append('=');
        }
        return builder.toString();
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean looksLikeAmneziaQuickConfig(String rawText) {
        if (TextUtils.isEmpty(rawText)) {
            return false;
        }
        String trimmed = rawText.trim();
        if (!trimmed.contains("[Interface]") || !trimmed.contains("[Peer]")) {
            return false;
        }
        try {
            Config.parse(new java.io.ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8)));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static WingsvProto.VlessProfile toProtoProfile(XrayProfile profile) {
        WingsvProto.VlessProfile.Builder builder = WingsvProto.VlessProfile.newBuilder();
        builder.setId(value(profile.id));
        if (!TextUtils.isEmpty(value(profile.title))) {
            builder.setTitle(value(profile.title));
        }
        builder.setRawLink(value(profile.rawLink));
        if (!TextUtils.isEmpty(value(profile.subscriptionId))) {
            builder.setSubscriptionId(value(profile.subscriptionId));
        }
        if (!TextUtils.isEmpty(value(profile.subscriptionTitle))) {
            builder.setSubscriptionTitle(value(profile.subscriptionTitle));
        }
        if (!TextUtils.isEmpty(value(profile.address))) {
            builder.setAddress(value(profile.address));
        }
        if (profile.port > 0) {
            builder.setPort(profile.port);
        }
        return builder.build();
    }

    private static XrayProfile fromProtoProfile(WingsvProto.VlessProfile profile) {
        return new XrayProfile(
            value(profile.getId()),
            value(profile.getTitle()),
            value(profile.getRawLink()),
            value(profile.getSubscriptionId()),
            value(profile.getSubscriptionTitle()),
            value(profile.getAddress()),
            profile.hasPort() ? profile.getPort() : 0
        );
    }

    private static XraySettings fromProtoXraySettings(WingsvProto.XraySettings settings) {
        XraySettings result = defaultXraySettings();
        if (settings.hasAllowLan()) {
            result.allowLan = settings.getAllowLan();
        }
        if (settings.hasAllowInsecure()) {
            result.allowInsecure = settings.getAllowInsecure();
        }
        if (settings.hasLocalProxyPort()) {
            result.localProxyPort = settings.getLocalProxyPort();
        }
        if (!TextUtils.isEmpty(value(settings.getRemoteDns()))) {
            result.remoteDns = value(settings.getRemoteDns());
        }
        if (!TextUtils.isEmpty(value(settings.getDirectDns()))) {
            result.directDns = value(settings.getDirectDns());
        }
        if (settings.hasIpv6()) {
            result.ipv6 = settings.getIpv6();
        }
        if (settings.hasSniffingEnabled()) {
            result.sniffingEnabled = settings.getSniffingEnabled();
        }
        if (settings.hasProxyQuicEnabled()) {
            result.proxyQuicEnabled = settings.getProxyQuicEnabled();
        }
        return result;
    }

    private static WingsvProto.XraySettings toProtoXraySettings(XraySettings settings) {
        WingsvProto.XraySettings.Builder builder = WingsvProto.XraySettings.newBuilder();
        if (settings == null) {
            return builder.build();
        }
        if (settings.allowLan) {
            builder.setAllowLan(true);
        }
        if (settings.allowInsecure) {
            builder.setAllowInsecure(true);
        }
        if (settings.localProxyPort > 0) {
            builder.setLocalProxyPort(settings.localProxyPort);
        }
        if (!TextUtils.isEmpty(value(settings.remoteDns))) {
            builder.setRemoteDns(value(settings.remoteDns));
        }
        if (!TextUtils.isEmpty(value(settings.directDns))) {
            builder.setDirectDns(value(settings.directDns));
        }
        if (!settings.ipv6) {
            builder.setIpv6(false);
        }
        if (!settings.sniffingEnabled) {
            builder.setSniffingEnabled(false);
        }
        if (settings.proxyQuicEnabled) {
            builder.setProxyQuicEnabled(true);
        }
        return builder.build();
    }

    private static XraySubscription fromProtoSubscription(WingsvProto.Subscription subscription) {
        return new XraySubscription(
            value(subscription.getId()),
            value(subscription.getTitle()),
            value(subscription.getUrl()),
            value(subscription.getFormatHint()),
            subscription.hasRefreshIntervalHours() ? subscription.getRefreshIntervalHours() : 0,
            !subscription.hasAutoUpdate() || subscription.getAutoUpdate(),
            subscription.hasLastUpdatedAt() ? subscription.getLastUpdatedAt() : 0L
        );
    }

    private static XraySettings defaultXraySettings() {
        XraySettings settings = new XraySettings();
        settings.allowLan = false;
        settings.allowInsecure = false;
        settings.localProxyPort = 10808;
        settings.remoteDns = "https://common.dot.dns.yandex.net/dns-query";
        settings.directDns = "https://common.dot.dns.yandex.net/dns-query";
        settings.ipv6 = true;
        settings.sniffingEnabled = true;
        settings.proxyQuicEnabled = false;
        settings.restartOnNetworkChange = false;
        return settings;
    }

    private static WingsvProto.TurnSessionMode toProtoSessionMode(String rawValue) {
        String normalized = value(rawValue);
        if (TextUtils.isEmpty(normalized) || DEFAULT_SESSION_MODE.equals(normalized)) {
            return WingsvProto.TurnSessionMode.TURN_SESSION_MODE_AUTO;
        }
        if ("mainline".equals(normalized)) {
            return WingsvProto.TurnSessionMode.TURN_SESSION_MODE_MAINLINE;
        }
        if ("mux".equals(normalized)) {
            return WingsvProto.TurnSessionMode.TURN_SESSION_MODE_MUX;
        }
        return WingsvProto.TurnSessionMode.TURN_SESSION_MODE_AUTO;
    }

    private static String fromProtoSessionMode(WingsvProto.TurnSessionMode value) {
        if (value == WingsvProto.TurnSessionMode.TURN_SESSION_MODE_MAINLINE) {
            return "mainline";
        }
        if (value == WingsvProto.TurnSessionMode.TURN_SESSION_MODE_MUX) {
            return "mux";
        }
        return DEFAULT_SESSION_MODE;
    }

    public static final class ImportedConfig {

        public BackendType backendType = BackendType.VK_TURN_WIREGUARD;
        public String endpoint;
        public String link;
        public Integer threads;
        public Boolean useUdp;
        public Boolean noObfuscation;
        public String turnSessionMode;
        public String localEndpoint;
        public String turnHost;
        public String turnPort;
        public String wgPrivateKey;
        public String wgAddresses;
        public String wgDns;
        public Integer wgMtu;
        public String wgPublicKey;
        public String wgPresharedKey;
        public String wgAllowedIps;
        public String awgQuickConfig;
        public String activeXrayProfileId;
        public final List<XrayProfile> xrayProfiles = new ArrayList<>();
        public final List<XraySubscription> xraySubscriptions = new ArrayList<>();
        public XraySettings xraySettings = defaultXraySettings();
        public String xraySubscriptionJson;
        public boolean xrayMergeOnly;
    }
}
