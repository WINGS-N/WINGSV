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
import java.util.Set;
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
    private static final int DEFAULT_SUBSCRIPTION_REFRESH_MINUTES = 24 * 60;

    private enum ExportScope {
        ACTIVE,
        ALL,
        XRAY,
        VK_TURN,
        WIREGUARD,
        AMNEZIAWG,
    }

    private WingsImportParser() {}

    public static String buildLink(ProxySettings settings) throws Exception {
        return buildLink(null, settings);
    }

    public static String buildLink(Context context, ProxySettings settings) throws Exception {
        WingsvProto.Config config = buildProtoConfig(context, settings, ExportScope.ACTIVE);
        return encodeConfig(config);
    }

    public static String buildAllSettingsLink(Context context) throws Exception {
        requireContext(context);
        WingsvProto.Config config = buildProtoConfig(context, AppPrefs.getSettings(context), ExportScope.ALL);
        return encodeConfig(config);
    }

    public static String buildXraySettingsLink(Context context) throws Exception {
        requireContext(context);
        WingsvProto.Config config = buildProtoConfig(
            context,
            scopedSettings(context, ExportScope.XRAY),
            ExportScope.XRAY
        );
        return encodeConfig(config);
    }

    public static String buildVkTurnSettingsLink(Context context) throws Exception {
        requireContext(context);
        WingsvProto.Config config = buildProtoConfig(
            context,
            scopedSettings(context, ExportScope.VK_TURN),
            ExportScope.VK_TURN
        );
        return encodeConfig(config);
    }

    public static String buildWireGuardSettingsLink(Context context) throws Exception {
        requireContext(context);
        WingsvProto.Config config = buildProtoConfig(
            context,
            scopedSettings(context, ExportScope.WIREGUARD),
            ExportScope.WIREGUARD
        );
        return encodeConfig(config);
    }

    public static String buildAmneziaSettingsLink(Context context) throws Exception {
        requireContext(context);
        WingsvProto.Config config = buildProtoConfig(
            context,
            scopedSettings(context, ExportScope.AMNEZIAWG),
            ExportScope.AMNEZIAWG
        );
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
                applyProtoSubscriptionRefreshInterval(subscriptionBuilder, subscription.refreshIntervalMinutes, false);
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
            applyProtoSubscriptionRefreshInterval(subscriptionBuilder, subscription.refreshIntervalMinutes, false);
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
            directImport.hasAmneziaSettings = true;
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
                    DEFAULT_SUBSCRIPTION_REFRESH_MINUTES,
                    true,
                    0L,
                    0L,
                    0L,
                    0L,
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

    private static void requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context is required");
        }
    }

    private static ProxySettings scopedSettings(Context context, ExportScope scope) {
        ProxySettings settings = AppPrefs.getSettings(context);
        if (scope == ExportScope.VK_TURN) {
            settings.backendType = BackendType.VK_TURN_WIREGUARD;
            settings.endpoint = AppPrefs.getTurnEndpoint(context);
        } else if (scope == ExportScope.WIREGUARD) {
            settings.backendType = BackendType.WIREGUARD;
            settings.endpoint = AppPrefs.getWireGuardEndpoint(context);
        } else if (scope == ExportScope.AMNEZIAWG) {
            BackendType currentBackend = XrayStore.getBackendType(context);
            settings.backendType =
                currentBackend == BackendType.AMNEZIAWG ? BackendType.AMNEZIAWG : BackendType.AMNEZIAWG_PLAIN;
            settings.endpoint = AppPrefs.resolveEndpointForBackend(context, settings.backendType);
            settings.awgQuickConfig = AmneziaStore.getEffectiveQuickConfig(context);
        } else if (scope == ExportScope.XRAY) {
            settings.backendType = BackendType.XRAY;
            settings.activeXrayProfile = XrayStore.getActiveProfile(context);
            settings.xraySettings = XrayStore.getXraySettings(context);
        }
        return settings;
    }

    private static WingsvProto.Config buildProtoConfig(Context context, ProxySettings settings, ExportScope scope)
        throws Exception {
        if (scope == ExportScope.ALL) {
            requireContext(context);
            BackendType backendType = XrayStore.getBackendType(context);
            WingsvProto.Config.Builder builder = WingsvProto.Config.newBuilder()
                .setVer(CURRENT_VERSION)
                .setBackend(backendType.toProto())
                .setType(WingsvProto.ConfigType.CONFIG_TYPE_ALL)
                .setAppRouting(buildAppRouting(context));

            WingsvProto.Turn turn = buildTurn(scopedSettings(context, ExportScope.VK_TURN), true);
            if (!turn.equals(WingsvProto.Turn.getDefaultInstance())) {
                builder.setTurn(turn);
            }
            WingsvProto.WireGuard wg = buildWireGuard(scopedSettings(context, ExportScope.WIREGUARD), true);
            if (!wg.equals(WingsvProto.WireGuard.getDefaultInstance())) {
                builder.setWg(wg);
            }
            WingsvProto.AmneziaWG awg = buildAmnezia(scopedSettings(context, ExportScope.AMNEZIAWG), true);
            if (!awg.equals(WingsvProto.AmneziaWG.getDefaultInstance())) {
                builder.setAwg(awg);
            }
            WingsvProto.Xray xray = buildXray(
                context,
                scopedSettings(context, ExportScope.XRAY),
                true,
                false,
                true,
                true
            );
            if (!xray.equals(WingsvProto.Xray.getDefaultInstance())) {
                builder.setXray(xray);
            }
            return builder.build();
        }

        BackendType backendType =
            settings != null && settings.backendType != null ? settings.backendType : BackendType.VK_TURN_WIREGUARD;
        WingsvProto.Config.Builder builder = WingsvProto.Config.newBuilder()
            .setVer(CURRENT_VERSION)
            .setBackend(backendType.toProto())
            .setType(resolveConfigType(scope, backendType));

        if (settings != null && (scope == ExportScope.XRAY || backendType == BackendType.XRAY)) {
            boolean includeSubscriptionProfiles = scope == ExportScope.ACTIVE;
            WingsvProto.Xray xray = buildXray(
                context,
                settings,
                true,
                includeSubscriptionProfiles,
                true,
                scope != ExportScope.ACTIVE
            );
            if (!xray.equals(WingsvProto.Xray.getDefaultInstance())) {
                builder.setXray(xray);
            }
            return builder.build();
        }
        if (
            settings != null &&
            (scope == ExportScope.AMNEZIAWG || (backendType != null && backendType.usesAmneziaSettings()))
        ) {
            WingsvProto.AmneziaWG awg = buildAmnezia(settings, scope != ExportScope.ACTIVE);
            if (!awg.equals(WingsvProto.AmneziaWG.getDefaultInstance())) {
                builder.setAwg(awg);
            }
            return builder.build();
        }

        if (settings != null) {
            WingsvProto.Turn turn = buildTurn(settings, scope != ExportScope.ACTIVE);
            if (
                scope == ExportScope.VK_TURN ||
                scope == ExportScope.WIREGUARD ||
                !turn.equals(WingsvProto.Turn.getDefaultInstance())
            ) {
                builder.setTurn(turn);
            }
            if (scope != ExportScope.VK_TURN) {
                WingsvProto.WireGuard wg = buildWireGuard(settings, scope != ExportScope.ACTIVE);
                if (scope == ExportScope.WIREGUARD || !wg.equals(WingsvProto.WireGuard.getDefaultInstance())) {
                    builder.setWg(wg);
                }
            }
        }
        return builder.build();
    }

    private static WingsvProto.ConfigType resolveConfigType(ExportScope scope, BackendType backendType) {
        if (scope == ExportScope.XRAY || backendType == BackendType.XRAY) {
            return WingsvProto.ConfigType.CONFIG_TYPE_XRAY;
        }
        if (scope == ExportScope.AMNEZIAWG || (backendType != null && backendType.usesAmneziaSettings())) {
            return WingsvProto.ConfigType.CONFIG_TYPE_AMNEZIAWG;
        }
        return WingsvProto.ConfigType.CONFIG_TYPE_VK;
    }

    private static WingsvProto.AmneziaWG buildAmnezia(ProxySettings settings, boolean includeDefaults) {
        WingsvProto.AmneziaWG.Builder builder = WingsvProto.AmneziaWG.newBuilder();
        if (settings != null && (includeDefaults || !TextUtils.isEmpty(value(settings.awgQuickConfig)))) {
            builder.setAwgQuickConfig(value(settings.awgQuickConfig));
        }
        return builder.build();
    }

    private static WingsvProto.AppRouting buildAppRouting(Context context) {
        WingsvProto.AppRouting.Builder builder = WingsvProto.AppRouting.newBuilder().setBypass(
            AppPrefs.isAppRoutingBypassEnabled(context)
        );
        Set<String> packages = AppPrefs.getAppRoutingPackages(context);
        for (String packageName : packages) {
            if (!TextUtils.isEmpty(value(packageName))) {
                builder.addPackages(value(packageName));
            }
        }
        return builder.build();
    }

    private static WingsvProto.Xray buildXray(
        Context context,
        ProxySettings settings,
        boolean includeProfiles,
        boolean includeSubscriptionProfiles,
        boolean includeRouting,
        boolean includeDefaults
    ) {
        WingsvProto.Xray.Builder builder = WingsvProto.Xray.newBuilder();
        XrayProfile activeProfile = settings.activeXrayProfile;
        LinkedHashMap<String, XrayProfile> profiles = new LinkedHashMap<>();
        if (includeProfiles && context != null) {
            for (XrayProfile profile : XrayStore.getProfiles(context)) {
                if (shouldExportProfile(profile, includeSubscriptionProfiles)) {
                    profiles.put(profile.stableDedupKey(), profile);
                }
            }
        }
        if (includeProfiles && shouldExportProfile(activeProfile, includeSubscriptionProfiles)) {
            profiles.put(activeProfile.stableDedupKey(), activeProfile);
        }
        String activeProfileId = activeProfile != null ? value(activeProfile.id) : "";
        if (TextUtils.isEmpty(activeProfileId) && context != null) {
            activeProfileId = value(XrayStore.getActiveProfileId(context));
        }
        if (!TextUtils.isEmpty(activeProfileId) && containsProfileId(profiles, activeProfileId)) {
            builder.setActiveProfileId(activeProfileId);
        }
        for (XrayProfile profile : profiles.values()) {
            builder.addProfiles(toProtoProfile(profile));
        }
        XraySettings xraySettings = settings.xraySettings;
        if (xraySettings != null) {
            WingsvProto.XraySettings protoSettings = toProtoXraySettings(xraySettings, includeDefaults);
            if (includeDefaults || !protoSettings.equals(WingsvProto.XraySettings.getDefaultInstance())) {
                builder.setSettings(protoSettings);
            }
        }
        if (context != null) {
            for (XraySubscription subscription : XrayStore.getSubscriptions(context)) {
                if (subscription == null || TextUtils.isEmpty(subscription.url)) {
                    continue;
                }
                builder.addSubscriptions(toProtoSubscription(subscription, includeDefaults));
            }
            if (includeSubscriptionProfiles) {
                String importedSubscriptionJson = XrayStore.getImportedSubscriptionJson(context);
                if (!TextUtils.isEmpty(importedSubscriptionJson)) {
                    builder.setSubscriptionJson(importedSubscriptionJson);
                }
            }
            if (includeRouting) {
                WingsvProto.XrayRouting routing = toProtoXrayRouting(context);
                if (includeDefaults || !routing.equals(WingsvProto.XrayRouting.getDefaultInstance())) {
                    builder.setRouting(routing);
                }
            }
        }
        return builder.build();
    }

    private static boolean shouldExportProfile(XrayProfile profile, boolean includeSubscriptionProfiles) {
        return (
            profile != null &&
            !TextUtils.isEmpty(profile.rawLink) &&
            (includeSubscriptionProfiles || TextUtils.isEmpty(profile.subscriptionId))
        );
    }

    private static boolean containsProfileId(LinkedHashMap<String, XrayProfile> profiles, String activeProfileId) {
        for (XrayProfile profile : profiles.values()) {
            if (profile != null && TextUtils.equals(value(profile.id), value(activeProfileId))) {
                return true;
            }
        }
        return false;
    }

    private static WingsvProto.Subscription toProtoSubscription(
        XraySubscription subscription,
        boolean includeDefaults
    ) {
        WingsvProto.Subscription.Builder subscriptionBuilder = WingsvProto.Subscription.newBuilder()
            .setId(value(subscription.id))
            .setTitle(value(subscription.title))
            .setUrl(value(subscription.url))
            .setFormatHint(value(subscription.formatHint));
        applyProtoSubscriptionRefreshInterval(
            subscriptionBuilder,
            subscription.refreshIntervalMinutes,
            includeDefaults
        );
        if (includeDefaults || !subscription.autoUpdate) {
            subscriptionBuilder.setAutoUpdate(subscription.autoUpdate);
        }
        if (subscription.lastUpdatedAt > 0L) {
            subscriptionBuilder.setLastUpdatedAt(subscription.lastUpdatedAt);
        }
        return subscriptionBuilder.build();
    }

    private static WingsvProto.XrayRouting toProtoXrayRouting(Context context) {
        WingsvProto.XrayRouting.Builder builder = WingsvProto.XrayRouting.newBuilder()
            .setGeoipUrl(XrayRoutingStore.getSourceUrl(context, XrayRoutingRule.MatchType.GEOIP))
            .setGeositeUrl(XrayRoutingStore.getSourceUrl(context, XrayRoutingRule.MatchType.GEOSITE));
        for (XrayRoutingRule rule : XrayRoutingStore.getRules(context)) {
            if (rule == null || TextUtils.isEmpty(rule.code)) {
                continue;
            }
            WingsvProto.XrayRoutingRule.Builder ruleBuilder = WingsvProto.XrayRoutingRule.newBuilder()
                .setId(value(rule.id))
                .setMatchType(toProtoRoutingMatchType(rule.matchType))
                .setCode(value(rule.code))
                .setAction(toProtoRoutingAction(rule.action))
                .setEnabled(rule.enabled);
            builder.addRules(ruleBuilder.build());
        }
        return builder.build();
    }

    private static void applyProtoSubscriptionRefreshInterval(
        WingsvProto.Subscription.Builder subscriptionBuilder,
        int refreshIntervalMinutes,
        boolean includeDefaults
    ) {
        int normalizedMinutes = Math.max(refreshIntervalMinutes, 0);
        if (normalizedMinutes > 0) {
            subscriptionBuilder.setRefreshIntervalMinutes(normalizedMinutes);
            if (normalizedMinutes % 60 == 0) {
                subscriptionBuilder.setRefreshIntervalHours(normalizedMinutes / 60);
            }
            return;
        }
        if (!includeDefaults) {
            return;
        }
        subscriptionBuilder.setRefreshIntervalMinutes(DEFAULT_SUBSCRIPTION_REFRESH_MINUTES);
        subscriptionBuilder.setRefreshIntervalHours(DEFAULT_SUBSCRIPTION_REFRESH_MINUTES / 60);
    }

    private static WingsvProto.XrayRoutingMatchType toProtoRoutingMatchType(XrayRoutingRule.MatchType matchType) {
        if (matchType == XrayRoutingRule.MatchType.GEOSITE) {
            return WingsvProto.XrayRoutingMatchType.XRAY_ROUTING_MATCH_GEOSITE;
        }
        return WingsvProto.XrayRoutingMatchType.XRAY_ROUTING_MATCH_GEOIP;
    }

    private static WingsvProto.XrayRoutingAction toProtoRoutingAction(XrayRoutingRule.Action action) {
        if (action == XrayRoutingRule.Action.DIRECT) {
            return WingsvProto.XrayRoutingAction.XRAY_ROUTING_ACTION_DIRECT;
        }
        if (action == XrayRoutingRule.Action.BLOCK) {
            return WingsvProto.XrayRoutingAction.XRAY_ROUTING_ACTION_BLOCK;
        }
        return WingsvProto.XrayRoutingAction.XRAY_ROUTING_ACTION_PROXY;
    }

    private static WingsvProto.Turn buildTurn(ProxySettings settings) throws Exception {
        return buildTurn(settings, false);
    }

    private static WingsvProto.Turn buildTurn(ProxySettings settings, boolean includeDefaults) throws Exception {
        WingsvProto.Turn.Builder builder = WingsvProto.Turn.newBuilder();
        setEndpoint(builder, value(settings.endpoint), true);
        if (includeDefaults || !TextUtils.isEmpty(value(settings.vkLink))) {
            builder.setLink(value(settings.vkLink));
        }
        if (settings.threads > 0 && (includeDefaults || settings.threads != DEFAULT_THREADS)) {
            builder.setThreads(settings.threads);
        }
        if (includeDefaults || settings.useUdp != DEFAULT_USE_UDP) {
            builder.setUseUdp(settings.useUdp);
        }
        if (includeDefaults || settings.noObfuscation != DEFAULT_NO_OBFUSCATION) {
            builder.setNoObfuscation(settings.noObfuscation);
        }
        String sessionMode = value(settings.turnSessionMode);
        WingsvProto.TurnSessionMode protoSessionMode = toProtoSessionMode(sessionMode);
        if (
            includeDefaults ||
            (protoSessionMode != WingsvProto.TurnSessionMode.TURN_SESSION_MODE_AUTO &&
                protoSessionMode != WingsvProto.TurnSessionMode.TURN_SESSION_MODE_UNSPECIFIED)
        ) {
            builder.setSessionMode(protoSessionMode);
        }
        String localEndpoint = value(settings.localEndpoint);
        if (!TextUtils.isEmpty(localEndpoint) && (includeDefaults || !DEFAULT_LOCAL_ENDPOINT.equals(localEndpoint))) {
            setEndpoint(builder, localEndpoint, false);
        }
        if (includeDefaults || !TextUtils.isEmpty(value(settings.turnHost))) {
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
        return buildWireGuard(settings, false);
    }

    private static WingsvProto.WireGuard buildWireGuard(ProxySettings settings, boolean includeDefaults)
        throws Exception {
        WingsvProto.WireGuard.Builder builder = WingsvProto.WireGuard.newBuilder();
        WingsvProto.Endpoint endpoint = parseEndpoint(value(settings.endpoint));
        if (endpoint != null) {
            builder.setEndpoint(endpoint);
        }

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
        if (!TextUtils.isEmpty(dns) && (includeDefaults || !DEFAULT_WG_DNS.equals(dns))) {
            for (String entry : splitCsv(dns)) {
                iface.addDns(entry);
            }
        }
        if (settings.wgMtu > 0 && (includeDefaults || settings.wgMtu != DEFAULT_WG_MTU)) {
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
        if (!TextUtils.isEmpty(allowedIps) && (includeDefaults || !DEFAULT_ALLOWED_IPS.equals(allowedIps))) {
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
        boolean allSettings = config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_ALL;
        importedConfig.hasAllSettings = allSettings;
        boolean handled = allSettings;

        if (allSettings && config.hasAppRouting()) {
            parseAppRouting(config.getAppRouting(), importedConfig);
        }

        if (
            allSettings ||
            config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_XRAY ||
            importedConfig.backendType == BackendType.XRAY ||
            config.hasXray()
        ) {
            if (!allSettings) {
                importedConfig.backendType = BackendType.XRAY;
            }
            parseXray(config, importedConfig);
            handled = true;
            if (!allSettings) {
                return importedConfig;
            }
        }
        if (
            allSettings ||
            config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_AMNEZIAWG ||
            importedConfig.backendType == BackendType.AMNEZIAWG ||
            importedConfig.backendType == BackendType.AMNEZIAWG_PLAIN ||
            config.hasAwg()
        ) {
            if (!allSettings && importedConfig.backendType != BackendType.AMNEZIAWG_PLAIN) {
                importedConfig.backendType = BackendType.AMNEZIAWG;
            }
            if (config.hasAwg()) {
                importedConfig.hasAmneziaSettings = true;
                importedConfig.awgQuickConfig = value(config.getAwg().getAwgQuickConfig());
            }
            handled = true;
            if (!allSettings) {
                return importedConfig;
            }
        }

        if (
            allSettings ||
            config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_VK ||
            config.hasTurn() ||
            config.hasWg()
        ) {
            if (config.hasTurn()) {
                parseTurn(config.getTurn(), importedConfig);
            }
            if (config.hasWg()) {
                parseWireGuard(config.getWg(), importedConfig);
            }
            handled = true;
        }

        if (!handled) {
            throw new IllegalArgumentException("Поддерживается только type=vk/xray/amneziawg/all");
        }
        return importedConfig;
    }

    private static void parseTurn(WingsvProto.Turn turn, ImportedConfig importedConfig) {
        importedConfig.hasTurnSettings = true;
        importedConfig.endpoint = turn.hasEndpoint() ? formatEndpoint(turn.getEndpoint()) : "";
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
        importedConfig.localEndpoint = turn.hasLocalEndpoint() ? formatEndpoint(turn.getLocalEndpoint()) : "";
        importedConfig.turnHost = value(turn.getHost());
        importedConfig.turnPort = turn.hasPort() ? String.valueOf(turn.getPort()) : "";
    }

    private static void parseWireGuard(WingsvProto.WireGuard wg, ImportedConfig importedConfig) throws Exception {
        importedConfig.hasWireGuardSettings = true;
        importedConfig.wgEndpoint = wg.hasEndpoint() ? formatEndpoint(wg.getEndpoint()) : "";
        if (wg.hasIface()) {
            WingsvProto.Interface iface = wg.getIface();
            importedConfig.wgPrivateKey = !iface.getPrivateKey().isEmpty()
                ? encodeWireGuardKey(iface.getPrivateKey().toByteArray())
                : "";
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
            importedConfig.wgPublicKey = !peer.getPublicKey().isEmpty()
                ? encodeWireGuardKey(peer.getPublicKey().toByteArray())
                : "";
            importedConfig.wgPresharedKey = !peer.getPresharedKey().isEmpty()
                ? encodeWireGuardKey(peer.getPresharedKey().toByteArray())
                : "";
            if (peer.getAllowedIpsCount() > 0) {
                List<String> cidrs = new ArrayList<>(peer.getAllowedIpsCount());
                for (WingsvProto.Cidr cidr : peer.getAllowedIpsList()) {
                    cidrs.add(formatCidr(cidr));
                }
                importedConfig.wgAllowedIps = TextUtils.join(", ", cidrs);
            }
        }
    }

    private static void parseXray(WingsvProto.Config config, ImportedConfig importedConfig) {
        WingsvProto.Xray xray = config.hasXray() ? config.getXray() : WingsvProto.Xray.getDefaultInstance();
        importedConfig.hasXraySettings = config.hasXray();
        importedConfig.xrayMergeOnly = xray.hasMergeOnly() && xray.getMergeOnly();
        importedConfig.xraySettings = xray.hasSettings()
            ? fromProtoXraySettings(xray.getSettings())
            : defaultXraySettings();
        importedConfig.activeXrayProfileId = value(xray.getActiveProfileId());
        importedConfig.xraySubscriptionJson = value(xray.getSubscriptionJson());
        importedConfig.hasXraySubscriptionJson = !TextUtils.isEmpty(importedConfig.xraySubscriptionJson);
        if (xray.getSubscriptionsCount() > 0) {
            for (WingsvProto.Subscription subscription : xray.getSubscriptionsList()) {
                importedConfig.xraySubscriptions.add(fromProtoSubscription(subscription));
            }
        }
        if (xray.getProfilesCount() > 0) {
            importedConfig.hasXrayProfiles = true;
            for (WingsvProto.VlessProfile profile : xray.getProfilesList()) {
                importedConfig.xrayProfiles.add(fromProtoProfile(profile));
            }
        }
        if (xray.hasRouting()) {
            parseXrayRouting(xray.getRouting(), importedConfig);
        }
    }

    private static void parseXrayRouting(WingsvProto.XrayRouting routing, ImportedConfig importedConfig) {
        importedConfig.hasXrayRouting = true;
        importedConfig.xrayRoutingGeoipUrl = value(routing.getGeoipUrl());
        importedConfig.xrayRoutingGeositeUrl = value(routing.getGeositeUrl());
        for (WingsvProto.XrayRoutingRule rule : routing.getRulesList()) {
            if (TextUtils.isEmpty(value(rule.getCode()))) {
                continue;
            }
            importedConfig.xrayRoutingRules.add(
                new XrayRoutingRule(
                    value(rule.getId()),
                    fromProtoRoutingMatchType(rule.getMatchType()),
                    value(rule.getCode()),
                    fromProtoRoutingAction(rule.getAction()),
                    !rule.hasEnabled() || rule.getEnabled()
                )
            );
        }
    }

    private static XrayRoutingRule.MatchType fromProtoRoutingMatchType(WingsvProto.XrayRoutingMatchType matchType) {
        if (matchType == WingsvProto.XrayRoutingMatchType.XRAY_ROUTING_MATCH_GEOSITE) {
            return XrayRoutingRule.MatchType.GEOSITE;
        }
        return XrayRoutingRule.MatchType.GEOIP;
    }

    private static XrayRoutingRule.Action fromProtoRoutingAction(WingsvProto.XrayRoutingAction action) {
        if (action == WingsvProto.XrayRoutingAction.XRAY_ROUTING_ACTION_DIRECT) {
            return XrayRoutingRule.Action.DIRECT;
        }
        if (action == WingsvProto.XrayRoutingAction.XRAY_ROUTING_ACTION_BLOCK) {
            return XrayRoutingRule.Action.BLOCK;
        }
        return XrayRoutingRule.Action.PROXY;
    }

    private static void parseAppRouting(WingsvProto.AppRouting appRouting, ImportedConfig importedConfig) {
        importedConfig.hasAppRouting = true;
        if (appRouting.hasBypass()) {
            importedConfig.appRoutingBypass = appRouting.getBypass();
        }
        importedConfig.appRoutingPackages.clear();
        for (String packageName : appRouting.getPackagesList()) {
            if (!TextUtils.isEmpty(value(packageName))) {
                importedConfig.appRoutingPackages.add(value(packageName));
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
        importedConfig.hasTurnSettings = true;
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
            importedConfig.hasWireGuardSettings = true;
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
        if (settings.hasLocalProxyEnabled()) {
            result.localProxyEnabled = settings.getLocalProxyEnabled();
        }
        if (settings.hasLocalProxyAuthEnabled()) {
            result.localProxyAuthEnabled = settings.getLocalProxyAuthEnabled();
        }
        if (!TextUtils.isEmpty(value(settings.getLocalProxyUsername()))) {
            result.localProxyUsername = value(settings.getLocalProxyUsername());
        }
        if (!TextUtils.isEmpty(value(settings.getLocalProxyPassword()))) {
            result.localProxyPassword = value(settings.getLocalProxyPassword());
        }
        if (settings.hasRestartOnNetworkChange()) {
            result.restartOnNetworkChange = settings.getRestartOnNetworkChange();
        }
        return result;
    }

    private static WingsvProto.XraySettings toProtoXraySettings(XraySettings settings) {
        return toProtoXraySettings(settings, false);
    }

    private static WingsvProto.XraySettings toProtoXraySettings(XraySettings settings, boolean includeDefaults) {
        WingsvProto.XraySettings.Builder builder = WingsvProto.XraySettings.newBuilder();
        if (settings == null) {
            return builder.build();
        }
        if (includeDefaults || settings.allowLan) {
            builder.setAllowLan(settings.allowLan);
        }
        if (includeDefaults || settings.allowInsecure) {
            builder.setAllowInsecure(settings.allowInsecure);
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
        if (includeDefaults || !settings.ipv6) {
            builder.setIpv6(settings.ipv6);
        }
        if (includeDefaults || !settings.sniffingEnabled) {
            builder.setSniffingEnabled(settings.sniffingEnabled);
        }
        if (includeDefaults || settings.proxyQuicEnabled) {
            builder.setProxyQuicEnabled(settings.proxyQuicEnabled);
        }
        if (includeDefaults || settings.localProxyEnabled) {
            builder.setLocalProxyEnabled(settings.localProxyEnabled);
        }
        if (includeDefaults || !settings.localProxyAuthEnabled) {
            builder.setLocalProxyAuthEnabled(settings.localProxyAuthEnabled);
        }
        if (includeDefaults || !TextUtils.isEmpty(value(settings.localProxyUsername))) {
            builder.setLocalProxyUsername(value(settings.localProxyUsername));
        }
        if (includeDefaults || !TextUtils.isEmpty(value(settings.localProxyPassword))) {
            builder.setLocalProxyPassword(value(settings.localProxyPassword));
        }
        if (includeDefaults || settings.restartOnNetworkChange) {
            builder.setRestartOnNetworkChange(settings.restartOnNetworkChange);
        }
        return builder.build();
    }

    private static XraySubscription fromProtoSubscription(WingsvProto.Subscription subscription) {
        return new XraySubscription(
            value(subscription.getId()),
            value(subscription.getTitle()),
            value(subscription.getUrl()),
            value(subscription.getFormatHint()),
            resolveProtoSubscriptionRefreshIntervalMinutes(subscription),
            !subscription.hasAutoUpdate() || subscription.getAutoUpdate(),
            subscription.hasLastUpdatedAt() ? subscription.getLastUpdatedAt() : 0L,
            0L,
            0L,
            0L,
            0L
        );
    }

    private static int resolveProtoSubscriptionRefreshIntervalMinutes(WingsvProto.Subscription subscription) {
        if (subscription.hasRefreshIntervalMinutes()) {
            return subscription.getRefreshIntervalMinutes();
        }
        if (subscription.hasRefreshIntervalHours()) {
            return subscription.getRefreshIntervalHours() * 60;
        }
        return 0;
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
        public boolean hasAllSettings;
        public boolean hasTurnSettings;
        public boolean hasWireGuardSettings;
        public boolean hasAmneziaSettings;
        public boolean hasXraySettings;
        public boolean hasXrayProfiles;
        public boolean hasXraySubscriptionJson;
        public boolean hasXrayRouting;
        public boolean hasAppRouting;
        public String endpoint;
        public String link;
        public Integer threads;
        public Boolean useUdp;
        public Boolean noObfuscation;
        public String turnSessionMode;
        public String localEndpoint;
        public String turnHost;
        public String turnPort;
        public String wgEndpoint;
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
        public final List<XrayRoutingRule> xrayRoutingRules = new ArrayList<>();
        public String xrayRoutingGeoipUrl;
        public String xrayRoutingGeositeUrl;
        public Boolean appRoutingBypass;
        public final List<String> appRoutingPackages = new ArrayList<>();
    }
}
