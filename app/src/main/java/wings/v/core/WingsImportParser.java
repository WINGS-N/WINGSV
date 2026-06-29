package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
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
import wings.v.R;
import wings.v.WingsApplication;
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
        WB_STREAM,
        APP_ROUTING_BYPASS,
        XRAY_ROUTING,
        XPOSED,
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

    /**
     * Builds a {@code Config} proto that captures every user-facing setting,
     * for the Guardian StateReport snapshot. Same payload as
     * {@link #buildAllSettingsLink} but returns the proto directly (no zlib /
     * base64 wrap).
     */
    public static WingsvProto.Config buildAllSettingsProto(Context context) throws Exception {
        requireContext(context);
        return buildProtoConfig(context, AppPrefs.getSettings(context), ExportScope.ALL);
    }

    /**
     * Verbose snapshot used for the Guardian panel. Differs from
     * {@link #buildAllSettingsProto}:
     * <ul>
     *   <li>includes Xray profiles imported from subscriptions
     *       ({@code includeSubscriptionProfiles=true}); the panel needs to
     *       show every profile the device knows about, not just hand-edited
     *       ones;</li>
     *   <li>passes {@code includeDefaults=true} to every sub-builder, so
     *       sections like Xposed appear even when all fields are at default
     *       values. Without this, the admin form would be empty for users
     *       who haven't deviated from defaults.</li>
     * </ul>
     */
    public static WingsvProto.Config buildGuardianSnapshotProto(Context context) throws Exception {
        requireContext(context);
        BackendType backendType = XrayStore.getBackendType(context);
        WingsvProto.Config.Builder builder = WingsvProto.Config.newBuilder()
            .setVer(CURRENT_VERSION)
            .setBackend(backendType.toProto())
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_ALL)
            .setAppRouting(buildAppRouting(context));

        WingsvProto.Turn turn = buildTurnWithProfiles(context, scopedSettings(context, ExportScope.VK_TURN), true);
        if (!turn.equals(WingsvProto.Turn.getDefaultInstance())) {
            builder.setTurn(turn);
        }
        WingsvProto.WireGuard wg = buildWireGuardWithProfiles(
            context,
            scopedSettings(context, ExportScope.WIREGUARD),
            true
        );
        if (!wg.equals(WingsvProto.WireGuard.getDefaultInstance())) {
            builder.setWg(wg);
        }
        WingsvProto.AmneziaWG awg = buildAmneziaWithProfiles(
            context,
            scopedSettings(context, ExportScope.AMNEZIAWG),
            true
        );
        if (!awg.equals(WingsvProto.AmneziaWG.getDefaultInstance())) {
            builder.setAwg(awg);
        }
        WingsvProto.Xray xray = buildXray(
            context,
            scopedSettings(context, ExportScope.XRAY),
            true /* includeProfiles */,
            true /* includeSubscriptionProfiles — panel needs to see ALL of them */,
            true /* includeRouting */,
            true /* includeDefaults */
        );
        if (!xray.equals(WingsvProto.Xray.getDefaultInstance())) {
            builder.setXray(xray);
        }
        WingsvProto.WbStream wb = buildWbStream(context, true);
        if (!wb.equals(WingsvProto.WbStream.getDefaultInstance())) {
            builder.setWbStream(wb);
        }
        // includeDefaults=true on remaining sections so admin sees the full
        // canvas even if user hasn't deviated from defaults.
        builder.setXposed(buildXposed(context, true));
        builder.setRoot(buildRootSettings(context, true));
        builder.setAppPreferences(buildAppPreferences(context, true));
        WingsvProto.SubscriptionHwid hwid = buildSubscriptionHwid(context, true);
        if (!hwid.equals(WingsvProto.SubscriptionHwid.getDefaultInstance())) {
            builder.setSubscriptionHwid(hwid);
        }
        builder.setSharing(buildSharing(context, true));
        builder.setByeDpi(buildByeDpi(context, true));
        return builder.build();
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

    public static String buildWbStreamSettingsLink(Context context) throws Exception {
        requireContext(context);
        WingsvProto.Config config = buildProtoConfig(
            context,
            scopedSettings(context, ExportScope.WB_STREAM),
            ExportScope.WB_STREAM
        );
        return encodeConfig(config);
    }

    public static String buildXposedSettingsLink(Context context) throws Exception {
        requireContext(context);
        WingsvProto.Config config = buildProtoConfig(context, null, ExportScope.XPOSED);
        return encodeConfig(config);
    }

    public static String buildAppRoutingBypassLink(Context context) throws Exception {
        requireContext(context);
        WingsvProto.Config config = buildProtoConfig(context, null, ExportScope.APP_ROUTING_BYPASS);
        return encodeConfig(config);
    }

    public static String buildXrayRoutingLink(Context context) throws Exception {
        requireContext(context);
        WingsvProto.Config config = buildProtoConfig(context, null, ExportScope.XRAY_ROUTING);
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

    /**
     * Single-profile share link for a saved WireGuard profile. Mirrors the Xray
     * single-profile link: builds a Config carrying just the WireGuard message
     * (plus a title field) and encodes it proto+deflate+base64. On import the
     * existing VK / WireGuard parse path reconstructs an equivalent profile and
     * AppPrefs.applyImportedConfig adds it to the WireGuard profile list.
     */
    public static String buildWireGuardProfileLink(WireGuardProfile profile) throws Exception {
        if (profile == null || profile.isEmpty()) {
            throw new IllegalArgumentException("No WireGuard profile to export");
        }
        ProxySettings settings = wireGuardProfileToSettings(profile);
        WingsvProto.WireGuard.Builder wg = buildWireGuard(settings, false).toBuilder();
        if (!TextUtils.isEmpty(value(profile.title))) {
            wg.setTitle(value(profile.title));
        }
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(CURRENT_VERSION)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_WIREGUARD)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_VK)
            .setWg(wg.build())
            .build();
        return encodeConfig(config);
    }

    /**
     * Single-profile share link for a saved AmneziaWG profile. Carries the
     * awg-quick config plus a title. On import the AmneziaWG parse path
     * reconstructs an equivalent profile.
     */
    public static String buildAmneziaProfileLink(AmneziaProfile profile) throws Exception {
        if (profile == null || profile.isEmpty()) {
            throw new IllegalArgumentException("No AmneziaWG profile to export");
        }
        WingsvProto.AmneziaWG.Builder awg = WingsvProto.AmneziaWG.newBuilder().setAwgQuickConfig(
            value(profile.quickConfig)
        );
        if (!TextUtils.isEmpty(value(profile.title))) {
            awg.setTitle(value(profile.title));
        }
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(CURRENT_VERSION)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG_TL)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_AMNEZIAWG)
            .setAwg(awg.build())
            .build();
        return encodeConfig(config);
    }

    /**
     * Single-profile share link for a saved VK TURN profile. The link MUST carry
     * the referenced transport (the WG or AWG sub-config + endpoint) so the
     * importer can fully reconstruct it on a device where the transport id does
     * not exist. The Config therefore carries Turn (with title + tunnel_mode)
     * AND the embedded transport message (wg OR awg). Resolution of the transport
     * reference happens here against the matching transport store.
     */
    public static String buildTurnProfileLink(Context context, VkTurnProfile profile) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("Context is required");
        }
        if (profile == null || TextUtils.isEmpty(value(profile.vkTurnEndpoint))) {
            throw new IllegalArgumentException("No VK TURN profile to export");
        }
        ProxySettings settings = vkTurnProfileToSettings(profile);
        WingsvProto.Turn.Builder turn = buildTurn(settings, false).toBuilder();
        // tunnel_mode tells the importer whether the transport below is WG or AWG.
        turn.setTunnelMode(
            profile.usesAmneziaTransport()
                ? WingsvProto.TunnelMode.TUNNEL_MODE_AMNEZIAWG
                : WingsvProto.TunnelMode.TUNNEL_MODE_WIREGUARD
        );
        if (!TextUtils.isEmpty(value(profile.title))) {
            turn.setTitle(value(profile.title));
        }

        WingsvProto.Config.Builder config = WingsvProto.Config.newBuilder()
            .setVer(CURRENT_VERSION)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_VK_TURN)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_VK_TURN_PROFILE)
            .setTurn(turn.build());

        if (profile.usesAmneziaTransport()) {
            AmneziaProfile transport = AmneziaProfileStore.getProfileById(context, profile.transportProfileId);
            if (transport == null || transport.isEmpty()) {
                throw new IllegalArgumentException("VK TURN transport (AmneziaWG) not found");
            }
            WingsvProto.AmneziaWG.Builder awg = WingsvProto.AmneziaWG.newBuilder().setAwgQuickConfig(
                value(transport.quickConfig)
            );
            if (!TextUtils.isEmpty(value(transport.title))) {
                awg.setTitle(value(transport.title));
            }
            config.setAwg(awg.build());
        } else {
            WireGuardProfile transport = WireGuardProfileStore.getProfileById(context, profile.transportProfileId);
            if (transport == null || transport.isEmpty()) {
                throw new IllegalArgumentException("VK TURN transport (WireGuard) not found");
            }
            ProxySettings transportSettings = wireGuardProfileToSettings(transport);
            WingsvProto.WireGuard.Builder wg = buildWireGuard(transportSettings, false).toBuilder();
            if (!TextUtils.isEmpty(value(transport.title))) {
                wg.setTitle(value(transport.title));
            }
            config.setWg(wg.build());
        }
        return encodeConfig(config.build());
    }

    private static ProxySettings wireGuardProfileToSettings(WireGuardProfile profile) {
        ProxySettings settings = new ProxySettings();
        settings.backendType = BackendType.WIREGUARD;
        settings.endpoint = value(profile.endpoint);
        settings.wgPrivateKey = value(profile.privateKey);
        settings.wgAddresses = value(profile.addresses);
        settings.wgDns = value(profile.dns);
        settings.wgMtu = profile.mtu;
        settings.wgPublicKey = value(profile.publicKey);
        settings.wgPresharedKey = value(profile.presharedKey);
        settings.wgAllowedIps = value(profile.allowedIps);
        return settings;
    }

    private static ProxySettings vkTurnProfileToSettings(VkTurnProfile profile) {
        ProxySettings settings = new ProxySettings();
        settings.backendType = profile.usesAmneziaTransport() ? BackendType.AMNEZIAWG : BackendType.VK_TURN_WIREGUARD;
        settings.endpoint = value(profile.vkTurnEndpoint);
        settings.threads = profile.threads;
        settings.credsGroupSize = profile.credsGroupSize;
        settings.useUdp = profile.useUdp;
        settings.noObfuscation = profile.noObfuscation;
        settings.manualCaptcha = profile.manualCaptcha;
        settings.captchaAutoSolver = value(profile.captchaAutoSolver);
        settings.vkAuthMode = value(profile.vkAuthMode);
        settings.turnSessionMode = value(profile.turnSessionMode);
        settings.vkTurnUserDns = value(profile.userDns);
        settings.vkTurnRuntimeMode = ProxyRuntimeMode.fromPrefValue(profile.runtimeMode);
        settings.vkTurnRestartOnNetworkChange = profile.restartOnNetworkChange;
        settings.vkTurnWrapMode = value(profile.wrapMode);
        settings.vkTurnWrapCipher = value(profile.wrapCipher);
        settings.vkTurnWrapKeyHex = value(profile.wrapKeyHex);
        settings.vkTurnWrapSendKey = profile.wrapSendKey;
        settings.localEndpoint = value(profile.localEndpoint);
        settings.turnHost = value(profile.turnHost);
        settings.turnPort = value(profile.turnPort);
        return settings;
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
            throw new IllegalArgumentException(
                WingsApplication.getStringSafe(R.string.import_parser_wingsv_link_not_found)
            );
        }

        byte[] decodedPayload = decodePayload(link);
        if (decodedPayload.length == 0) {
            throw new IllegalArgumentException(
                WingsApplication.getStringSafe(R.string.import_parser_wingsv_payload_empty)
            );
        }

        if (decodedPayload[0] == FORMAT_PROTOBUF_DEFLATE) {
            byte[] protobufPayload = inflate(slice(decodedPayload, 1, decodedPayload.length));
            return parseProtoConfig(WingsvProto.Config.parseFrom(protobufPayload));
        }
        throw new IllegalArgumentException(
            WingsApplication.getStringSafe(R.string.import_parser_wingsv_link_unsupported_format)
        );
    }

    public static boolean isSubscriptionOnlyXrayImport(ImportedConfig importedConfig) {
        return (
            importedConfig != null &&
            importedConfig.backendType != null &&
            importedConfig.backendType.usesXrayCore() &&
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

    /**
     * Scans a subscription body for wingsv:// single-profile links and decodes
     * each into a concrete backend profile object (or objects, for a VK TURN
     * profile which carries an embedded WG / AWG transport), WITHOUT writing to any
     * store and WITHOUT touching global settings or the active profile. The body
     * may be plain text holding the links or a single base64 blob of the whole
     * list (common for 3x-ui), decoded the same way XraySubscriptionParser does
     * before scanning. Full-settings bundles (CONFIG_TYPE_ALL) are skipped: only
     * single-profile links are dispatched from subscriptions. A decode failure for
     * one link is swallowed so it does not break the others; the caller tags the
     * returned profiles with the source subscription and routes them to the stores.
     */
    public static List<ImportedBackendProfile> extractBackendProfilesFromSubscriptionBody(
        Context context,
        String body
    ) {
        ArrayList<ImportedBackendProfile> result = new ArrayList<>();
        if (TextUtils.isEmpty(body)) {
            return result;
        }
        for (String link : collectWingsvLinks(body)) {
            try {
                ImportedConfig config = parseFromText(link);
                ImportedBackendProfile entry = toBackendProfile(context, config);
                if (entry != null) {
                    result.add(entry);
                }
            } catch (Exception ignored) {
                // A single malformed wingsv:// link must not break the rest.
            }
        }
        return result;
    }

    private static List<String> collectWingsvLinks(String body) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        collectWingsvLinks(body, links, true);
        return new ArrayList<>(links);
    }

    private static void collectWingsvLinks(String text, LinkedHashSet<String> links, boolean allowBase64Fallback) {
        String normalized = text == null ? "" : text.trim();
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        Matcher matcher = LINK_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String match = matcher.group();
            if (!TextUtils.isEmpty(match)) {
                links.add(match.trim());
            }
        }
        if (!links.isEmpty() || !allowBase64Fallback) {
            return;
        }
        try {
            byte[] decoded = Base64.decode(normalized, Base64.DEFAULT);
            String decodedText = new String(decoded, StandardCharsets.UTF_8);
            if (!TextUtils.equals(decodedText.trim(), normalized)) {
                collectWingsvLinks(decodedText, links, false);
            }
        } catch (Exception ignored) {}
    }

    // Maps a decoded single-profile ImportedConfig to a concrete backend profile,
    // mirroring the dispatch in AppPrefs.applyImportedBackendProfile but building
    // the model objects directly from the decoded fields instead of round-tripping
    // through the flat keys (so nothing global / active is mutated). Returns null
    // for full bundles or unsupported / empty payloads.
    private static ImportedBackendProfile toBackendProfile(Context context, ImportedConfig config) {
        if (config == null || config.hasAllSettings || config.backendType == null) {
            return null;
        }
        BackendType backendType = config.backendType;
        if (
            (backendType == BackendType.VK_TURN_WIREGUARD || backendType == BackendType.AMNEZIAWG) &&
            config.hasTurnSettings
        ) {
            boolean awg = backendType == BackendType.AMNEZIAWG;
            VkTurnProfile vkTurn = buildVkTurnProfile(context, config, awg);
            if (awg) {
                AmneziaProfile transport = buildAmneziaProfile(config);
                if (transport == null || transport.isEmpty()) {
                    return null;
                }
                return ImportedBackendProfile.vkTurn(vkTurn, null, transport);
            }
            WireGuardProfile transport = buildWireGuardProfile(config, true);
            if (transport == null || transport.isEmpty()) {
                return null;
            }
            return ImportedBackendProfile.vkTurn(vkTurn, transport, null);
        }
        if (backendType == BackendType.WIREGUARD && config.hasWireGuardSettings) {
            WireGuardProfile wireGuard = buildWireGuardProfile(config, false);
            if (wireGuard == null || wireGuard.isEmpty()) {
                return null;
            }
            return ImportedBackendProfile.wireGuard(wireGuard);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN && config.hasAmneziaSettings) {
            AmneziaProfile amnezia = buildAmneziaProfile(config);
            if (amnezia == null || amnezia.isEmpty()) {
                return null;
            }
            return ImportedBackendProfile.amnezia(amnezia);
        }
        return null;
    }

    private static WireGuardProfile buildWireGuardProfile(ImportedConfig config, boolean transport) {
        String endpoint = value(config.wgEndpoint);
        if (TextUtils.isEmpty(endpoint) && !transport) {
            endpoint = value(config.endpoint);
        }
        String dns = TextUtils.isEmpty(value(config.wgDns)) ? DEFAULT_WG_DNS : value(config.wgDns);
        int mtu = config.wgMtu != null && config.wgMtu > 0 ? config.wgMtu : DEFAULT_WG_MTU;
        String allowedIps = TextUtils.isEmpty(value(config.wgAllowedIps))
            ? DEFAULT_ALLOWED_IPS
            : value(config.wgAllowedIps);
        return new WireGuardProfile(
            null,
            value(config.importedWireGuardTitle),
            value(config.wgPrivateKey),
            value(config.wgAddresses),
            dns,
            mtu,
            value(config.wgPublicKey),
            value(config.wgPresharedKey),
            allowedIps,
            endpoint
        );
    }

    private static AmneziaProfile buildAmneziaProfile(ImportedConfig config) {
        String quick = value(config.awgQuickConfig);
        if (TextUtils.isEmpty(quick.trim())) {
            return null;
        }
        return new AmneziaProfile(null, value(config.importedAmneziaTitle), quick);
    }

    private static VkTurnProfile buildVkTurnProfile(Context context, ImportedConfig config, boolean awg) {
        String transportKind = awg ? VkTurnProfile.TRANSPORT_KIND_AWG : VkTurnProfile.TRANSPORT_KIND_WG;
        int threads = config.threads != null && config.threads > 0 ? config.threads : 24;
        int creds = config.credsGroupSize != null && config.credsGroupSize > 0 ? config.credsGroupSize : 12;
        boolean useUdp = config.useUdp == null || config.useUdp;
        boolean noObfuscation = config.noObfuscation != null && config.noObfuscation;
        boolean manualCaptcha = config.manualCaptcha != null && config.manualCaptcha;
        String captchaAutoSolver = TextUtils.isEmpty(value(config.captchaAutoSolver))
            ? AppPrefs.CAPTCHA_AUTO_SOLVER_DEFAULT
            : value(config.captchaAutoSolver);
        String turnSessionMode = TextUtils.isEmpty(value(config.turnSessionMode))
            ? "mainline"
            : value(config.turnSessionMode);
        String runtimeMode =
            config.vkTurnRuntimeMode != null ? config.vkTurnRuntimeMode.prefValue : ProxyRuntimeMode.VPN.prefValue;
        boolean restart = config.vkTurnRestartOnNetworkChange == null || config.vkTurnRestartOnNetworkChange;
        String wrapMode = AppPrefs.normalizeWrapMode(
            config.vkTurnWrapMode == null ? "preferred" : config.vkTurnWrapMode
        );
        String wrapCipher = AppPrefs.normalizeWrapCipher(
            config.vkTurnWrapCipher == null ? "srtp-aes-gcm" : config.vkTurnWrapCipher
        );
        boolean wrapSendKey = config.vkTurnWrapSendKey == null || config.vkTurnWrapSendKey;
        String localEndpoint = TextUtils.isEmpty(value(config.localEndpoint))
            ? DEFAULT_LOCAL_ENDPOINT
            : value(config.localEndpoint);
        return new VkTurnProfile(
            null,
            value(config.importedVkTurnTitle),
            transportKind,
            "",
            value(config.endpoint),
            threads,
            creds,
            useUdp,
            noObfuscation,
            manualCaptcha,
            captchaAutoSolver,
            AppPrefs.VK_AUTH_MODE_ANONYMOUS,
            turnSessionMode,
            AppPrefs.getDnsMode(context),
            value(config.vkTurnUserDns),
            runtimeMode,
            restart,
            wrapMode,
            wrapCipher,
            value(config.vkTurnWrapKeyHex),
            wrapSendKey,
            localEndpoint,
            value(config.turnHost),
            value(config.turnPort)
        );
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
            BackendType currentBackend = XrayStore.getBackendType(context);
            settings.backendType =
                currentBackend != null && currentBackend.usesXrayCore() ? currentBackend : BackendType.XRAY;
            settings.activeXrayProfile = XrayStore.getActiveProfile(context);
            settings.xraySettings = XrayStore.getXraySettings(context);
        } else if (scope == ExportScope.WB_STREAM) {
            settings.backendType = BackendType.WB_STREAM;
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

            WingsvProto.Turn turn = buildTurnWithProfiles(context, scopedSettings(context, ExportScope.VK_TURN), true);
            if (!turn.equals(WingsvProto.Turn.getDefaultInstance())) {
                builder.setTurn(turn);
            }
            WingsvProto.WireGuard wg = buildWireGuardWithProfiles(
                context,
                scopedSettings(context, ExportScope.WIREGUARD),
                true
            );
            if (!wg.equals(WingsvProto.WireGuard.getDefaultInstance())) {
                builder.setWg(wg);
            }
            WingsvProto.AmneziaWG awg = buildAmneziaWithProfiles(
                context,
                scopedSettings(context, ExportScope.AMNEZIAWG),
                true
            );
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
            WingsvProto.WbStream wb = buildWbStream(context, false);
            if (!wb.equals(WingsvProto.WbStream.getDefaultInstance())) {
                builder.setWbStream(wb);
            }
            WingsvProto.Xposed xposed = buildXposed(context, false);
            if (!xposed.equals(WingsvProto.Xposed.getDefaultInstance())) {
                builder.setXposed(xposed);
            }
            WingsvProto.RootSettings root = buildRootSettings(context, false);
            if (!root.equals(WingsvProto.RootSettings.getDefaultInstance())) {
                builder.setRoot(root);
            }
            WingsvProto.AppPreferences appPrefs = buildAppPreferences(context, false);
            if (!appPrefs.equals(WingsvProto.AppPreferences.getDefaultInstance())) {
                builder.setAppPreferences(appPrefs);
            }
            WingsvProto.SubscriptionHwid hwid = buildSubscriptionHwid(context, false);
            if (!hwid.equals(WingsvProto.SubscriptionHwid.getDefaultInstance())) {
                builder.setSubscriptionHwid(hwid);
            }
            WingsvProto.Sharing sharing = buildSharing(context, false);
            if (!sharing.equals(WingsvProto.Sharing.getDefaultInstance())) {
                builder.setSharing(sharing);
            }
            WingsvProto.ByeDpi byeDpi = buildByeDpi(context, false);
            if (!byeDpi.equals(WingsvProto.ByeDpi.getDefaultInstance())) {
                builder.setByeDpi(byeDpi);
            }
            return builder.build();
        }
        if (scope == ExportScope.APP_ROUTING_BYPASS) {
            requireContext(context);
            return WingsvProto.Config.newBuilder()
                .setVer(CURRENT_VERSION)
                .setType(WingsvProto.ConfigType.CONFIG_TYPE_APP_ROUTING)
                .setAppRouting(buildAppRouting(context))
                .build();
        }
        if (scope == ExportScope.XRAY_ROUTING) {
            requireContext(context);
            WingsvProto.Xray xray = WingsvProto.Xray.newBuilder().setRouting(toProtoXrayRouting(context)).build();
            return WingsvProto.Config.newBuilder()
                .setVer(CURRENT_VERSION)
                .setBackend(BackendType.XRAY.toProto())
                .setType(WingsvProto.ConfigType.CONFIG_TYPE_XRAY_ROUTING)
                .setXray(xray)
                .build();
        }
        if (scope == ExportScope.XPOSED) {
            requireContext(context);
            return WingsvProto.Config.newBuilder()
                .setVer(CURRENT_VERSION)
                .setType(WingsvProto.ConfigType.CONFIG_TYPE_XPOSED)
                .setXposed(buildXposed(context, false))
                .build();
        }

        BackendType backendType =
            settings != null && settings.backendType != null ? settings.backendType : BackendType.VK_TURN_WIREGUARD;
        WingsvProto.Config.Builder builder = WingsvProto.Config.newBuilder()
            .setVer(CURRENT_VERSION)
            .setBackend(backendType.toProto())
            .setType(resolveConfigType(scope, backendType));

        if (settings != null && (scope == ExportScope.XRAY || (backendType != null && backendType.usesXrayCore()))) {
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
            boolean xrayUsesTurnProxy =
                settings.xraySettings != null &&
                settings.xraySettings.transportMode != null &&
                settings.xraySettings.transportMode.usesTurnProxy();
            if (xrayUsesTurnProxy) {
                WingsvProto.Turn turn = buildTurn(settings, scope != ExportScope.ACTIVE);
                if (!turn.equals(WingsvProto.Turn.getDefaultInstance())) {
                    builder.setTurn(turn);
                }
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
        if (settings != null && (scope == ExportScope.WB_STREAM || backendType == BackendType.WB_STREAM)) {
            WingsvProto.WbStream wb = buildWbStream(context, scope != ExportScope.ACTIVE);
            if (!wb.equals(WingsvProto.WbStream.getDefaultInstance())) {
                builder.setWbStream(wb);
            }
            if (wb.getExchangeViaVkTurn()) {
                WingsvProto.Turn turn = buildTurn(settings, scope != ExportScope.ACTIVE);
                if (!turn.equals(WingsvProto.Turn.getDefaultInstance())) {
                    builder.setTurn(turn);
                }
            }
            WingsvProto.WireGuard wg = buildWireGuard(settings, scope != ExportScope.ACTIVE);
            if (!wg.equals(WingsvProto.WireGuard.getDefaultInstance())) {
                builder.setWg(wg);
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
        if (scope == ExportScope.XRAY || (backendType != null && backendType.usesXrayCore())) {
            return WingsvProto.ConfigType.CONFIG_TYPE_XRAY;
        }
        if (scope == ExportScope.AMNEZIAWG || (backendType != null && backendType.usesAmneziaSettings())) {
            return WingsvProto.ConfigType.CONFIG_TYPE_AMNEZIAWG;
        }
        if (scope == ExportScope.WB_STREAM || backendType == BackendType.WB_STREAM) {
            return WingsvProto.ConfigType.CONFIG_TYPE_WB_STREAM;
        }
        return WingsvProto.ConfigType.CONFIG_TYPE_VK;
    }

    private static WingsvProto.WbStream buildWbStream(Context context, boolean includeDefaults) {
        WingsvProto.WbStream.Builder builder = WingsvProto.WbStream.newBuilder();
        String roomId = context != null ? AppPrefs.getWbStreamRoomId(context) : "";
        String displayName = context != null ? AppPrefs.getWbStreamDisplayName(context) : "";
        boolean exchangeViaVkTurn = context != null && AppPrefs.isWbStreamExchangeViaVkTurn(context);
        boolean e2eEnabled = context != null && AppPrefs.isWbStreamE2eEnabled(context);
        String e2eSecret = context != null ? AppPrefs.getWbStreamE2eSecret(context) : "";
        if (includeDefaults || !TextUtils.isEmpty(roomId)) {
            builder.setRoomId(value(roomId));
        }
        if (includeDefaults || !TextUtils.isEmpty(displayName)) {
            builder.setDisplayName(value(displayName));
        }
        if (exchangeViaVkTurn) {
            builder.setExchangeViaVkTurn(true);
        }
        if (e2eEnabled) {
            builder.setE2EEnabled(true);
        }
        if (e2eEnabled && !TextUtils.isEmpty(e2eSecret)) {
            try {
                byte[] decoded = android.util.Base64.decode(e2eSecret, android.util.Base64.NO_WRAP);
                if (decoded.length > 0) {
                    builder.setE2ESecret(com.google.protobuf.ByteString.copyFrom(decoded));
                }
            } catch (IllegalArgumentException ignored) {}
        }
        int roomCount =
            context != null ? AppPrefs.getWbStreamRoomCount(context) : AppPrefs.DEFAULT_WB_STREAM_ROOM_COUNT;
        if (includeDefaults || roomCount != AppPrefs.DEFAULT_WB_STREAM_ROOM_COUNT) {
            builder.setRoomCount(roomCount);
        }
        TunnelMode wbMode = context != null ? AppPrefs.getWbStreamTunnelMode(context) : TunnelMode.WIREGUARD;
        if (includeDefaults || wbMode != TunnelMode.WIREGUARD) {
            builder.setTunnelMode(wbMode.toProto());
        }
        return builder.build();
    }

    private static WingsvProto.AmneziaWG buildAmnezia(ProxySettings settings, boolean includeDefaults) {
        WingsvProto.AmneziaWG.Builder builder = WingsvProto.AmneziaWG.newBuilder();
        if (settings != null && (includeDefaults || !TextUtils.isEmpty(value(settings.awgQuickConfig)))) {
            builder.setAwgQuickConfig(value(settings.awgQuickConfig));
        }
        return builder.build();
    }

    private static WingsvProto.Xposed buildXposed(Context context, boolean includeDefaults) {
        WingsvProto.Xposed.Builder builder = WingsvProto.Xposed.newBuilder();
        if (context == null) {
            return builder.build();
        }
        SharedPreferences prefs = XposedModulePrefs.prefs(context);
        boolean enabled = prefs.getBoolean(XposedModulePrefs.KEY_ENABLED, XposedModulePrefs.DEFAULT_ENABLED);
        if (includeDefaults || enabled != XposedModulePrefs.DEFAULT_ENABLED) {
            builder.setEnabled(enabled);
        }
        boolean allApps = prefs.getBoolean(XposedModulePrefs.KEY_ALL_APPS, XposedModulePrefs.DEFAULT_ALL_APPS);
        if (includeDefaults || allApps != XposedModulePrefs.DEFAULT_ALL_APPS) {
            builder.setAllApps(allApps);
        }
        boolean nativeHook = prefs.getBoolean(
            XposedModulePrefs.KEY_NATIVE_HOOK_ENABLED,
            XposedModulePrefs.DEFAULT_NATIVE_HOOK_ENABLED
        );
        if (includeDefaults || nativeHook != XposedModulePrefs.DEFAULT_NATIVE_HOOK_ENABLED) {
            builder.setNativeHookEnabled(nativeHook);
        }
        boolean hideVpnApps = prefs.getBoolean(
            XposedModulePrefs.KEY_HIDE_VPN_APPS,
            XposedModulePrefs.DEFAULT_HIDE_VPN_APPS
        );
        if (includeDefaults || hideVpnApps != XposedModulePrefs.DEFAULT_HIDE_VPN_APPS) {
            builder.setHideVpnApps(hideVpnApps);
        }
        boolean hideFromDumpsys = prefs.getBoolean(
            XposedModulePrefs.KEY_HIDE_FROM_DUMPSYS,
            XposedModulePrefs.DEFAULT_HIDE_FROM_DUMPSYS
        );
        if (includeDefaults || hideFromDumpsys != XposedModulePrefs.DEFAULT_HIDE_FROM_DUMPSYS) {
            builder.setHideFromDumpsys(hideFromDumpsys);
        }
        String procfsHookMode = XposedModulePrefs.normalizeProcfsHookMode(
            prefs.getString(XposedModulePrefs.KEY_PROCFS_HOOK_MODE, XposedModulePrefs.DEFAULT_PROCFS_HOOK_MODE)
        );
        if (includeDefaults || !XposedModulePrefs.DEFAULT_PROCFS_HOOK_MODE.equals(procfsHookMode)) {
            builder.setProcfsHookMode(toProtoProcfsHookMode(procfsHookMode));
        }
        String icmpSpoofingMode = XposedModulePrefs.normalizeIcmpSpoofingMode(
            prefs.getString(XposedModulePrefs.KEY_ICMP_SPOOFING_MODE, XposedModulePrefs.DEFAULT_ICMP_SPOOFING_MODE)
        );
        if (includeDefaults || !XposedModulePrefs.DEFAULT_ICMP_SPOOFING_MODE.equals(icmpSpoofingMode)) {
            builder.setIcmpSpoofingMode(toProtoIcmpSpoofingMode(icmpSpoofingMode));
        }
        for (String pkg : XposedModulePrefs.getPackageSet(context, XposedModulePrefs.KEY_TARGET_PACKAGES)) {
            if (!TextUtils.isEmpty(pkg)) {
                builder.addTargetPackages(pkg);
            }
        }
        Set<String> hiddenVpnPackages = XposedModulePrefs.getPackageSet(
            context,
            XposedModulePrefs.KEY_HIDDEN_VPN_PACKAGES
        );
        Set<String> defaultHidden = XposedModulePrefs.getDefaultHiddenVpnPackages();
        if (includeDefaults || !defaultHidden.equals(hiddenVpnPackages)) {
            for (String pkg : hiddenVpnPackages) {
                if (!TextUtils.isEmpty(pkg)) {
                    builder.addHiddenVpnPackages(pkg);
                }
            }
        }
        return builder.build();
    }

    private static WingsvProto.XposedProcfsHookMode toProtoProcfsHookMode(String mode) {
        if (XposedModulePrefs.PROCFS_HOOK_MODE_FILTER.equals(mode)) {
            return WingsvProto.XposedProcfsHookMode.XPOSED_PROCFS_HOOK_MODE_FILTER;
        }
        if (XposedModulePrefs.PROCFS_HOOK_MODE_NO_ACCESS.equals(mode)) {
            return WingsvProto.XposedProcfsHookMode.XPOSED_PROCFS_HOOK_MODE_NO_ACCESS;
        }
        if (XposedModulePrefs.PROCFS_HOOK_MODE_FILE_NOT_FOUND.equals(mode)) {
            return WingsvProto.XposedProcfsHookMode.XPOSED_PROCFS_HOOK_MODE_FILE_NOT_FOUND;
        }
        return WingsvProto.XposedProcfsHookMode.XPOSED_PROCFS_HOOK_MODE_DISABLED;
    }

    private static String fromProtoProcfsHookMode(WingsvProto.XposedProcfsHookMode mode) {
        if (mode == WingsvProto.XposedProcfsHookMode.XPOSED_PROCFS_HOOK_MODE_FILTER) {
            return XposedModulePrefs.PROCFS_HOOK_MODE_FILTER;
        }
        if (mode == WingsvProto.XposedProcfsHookMode.XPOSED_PROCFS_HOOK_MODE_NO_ACCESS) {
            return XposedModulePrefs.PROCFS_HOOK_MODE_NO_ACCESS;
        }
        if (mode == WingsvProto.XposedProcfsHookMode.XPOSED_PROCFS_HOOK_MODE_FILE_NOT_FOUND) {
            return XposedModulePrefs.PROCFS_HOOK_MODE_FILE_NOT_FOUND;
        }
        return XposedModulePrefs.PROCFS_HOOK_MODE_DISABLED;
    }

    private static WingsvProto.XposedIcmpSpoofingMode toProtoIcmpSpoofingMode(String mode) {
        if (XposedModulePrefs.ICMP_SPOOFING_MODE_PING_NOT_FOUND.equals(mode)) {
            return WingsvProto.XposedIcmpSpoofingMode.XPOSED_ICMP_SPOOFING_MODE_PING_NOT_FOUND;
        }
        if (XposedModulePrefs.ICMP_SPOOFING_MODE_EMPTY_RESPONSE.equals(mode)) {
            return WingsvProto.XposedIcmpSpoofingMode.XPOSED_ICMP_SPOOFING_MODE_EMPTY_RESPONSE;
        }
        return WingsvProto.XposedIcmpSpoofingMode.XPOSED_ICMP_SPOOFING_MODE_DISABLED;
    }

    private static String fromProtoIcmpSpoofingMode(WingsvProto.XposedIcmpSpoofingMode mode) {
        if (mode == WingsvProto.XposedIcmpSpoofingMode.XPOSED_ICMP_SPOOFING_MODE_PING_NOT_FOUND) {
            return XposedModulePrefs.ICMP_SPOOFING_MODE_PING_NOT_FOUND;
        }
        if (mode == WingsvProto.XposedIcmpSpoofingMode.XPOSED_ICMP_SPOOFING_MODE_EMPTY_RESPONSE) {
            return XposedModulePrefs.ICMP_SPOOFING_MODE_EMPTY_RESPONSE;
        }
        return XposedModulePrefs.ICMP_SPOOFING_MODE_DISABLED;
    }

    private static void parseXposed(WingsvProto.Xposed xposed, ImportedConfig importedConfig) {
        importedConfig.hasXposedSettings = true;
        if (xposed.hasEnabled()) {
            importedConfig.xposedEnabled = xposed.getEnabled();
        }
        if (xposed.hasAllApps()) {
            importedConfig.xposedAllApps = xposed.getAllApps();
        }
        if (xposed.hasNativeHookEnabled()) {
            importedConfig.xposedNativeHookEnabled = xposed.getNativeHookEnabled();
        }
        // inline_hooks_enabled (proto field 4) is now reserved: shadowhook
        // backend was removed and the toggle no longer maps to runtime state.
        if (xposed.hasHideVpnApps()) {
            importedConfig.xposedHideVpnApps = xposed.getHideVpnApps();
        }
        if (xposed.hasHideFromDumpsys()) {
            importedConfig.xposedHideFromDumpsys = xposed.getHideFromDumpsys();
        }
        if (xposed.getProcfsHookMode() != WingsvProto.XposedProcfsHookMode.XPOSED_PROCFS_HOOK_MODE_UNSPECIFIED) {
            importedConfig.xposedProcfsHookMode = fromProtoProcfsHookMode(xposed.getProcfsHookMode());
        }
        if (xposed.getIcmpSpoofingMode() != WingsvProto.XposedIcmpSpoofingMode.XPOSED_ICMP_SPOOFING_MODE_UNSPECIFIED) {
            importedConfig.xposedIcmpSpoofingMode = fromProtoIcmpSpoofingMode(xposed.getIcmpSpoofingMode());
        }
        for (String pkg : xposed.getTargetPackagesList()) {
            String trimmed = value(pkg);
            if (!TextUtils.isEmpty(trimmed)) {
                importedConfig.xposedTargetPackages.add(trimmed);
            }
        }
        for (String pkg : xposed.getHiddenVpnPackagesList()) {
            String trimmed = value(pkg);
            if (!TextUtils.isEmpty(trimmed)) {
                importedConfig.xposedHiddenVpnPackages.add(trimmed);
            }
        }
    }

    private static WingsvProto.RootSettings buildRootSettings(Context context, boolean includeDefaults) {
        WingsvProto.RootSettings.Builder builder = WingsvProto.RootSettings.newBuilder();
        if (context == null) {
            return builder.build();
        }
        SharedPreferences prefs = AppPrefs.defaultSharedPreferences(context);
        boolean rootMode = AppPrefs.isRootModeEnabled(context);
        if (includeDefaults || rootMode) {
            builder.setEnabled(rootMode);
        }
        // Kernel WG default mirrors KEY_ROOT_MODE when not explicitly set; only emit
        // when the user has overridden it.
        if (prefs.contains(AppPrefs.KEY_KERNEL_WIREGUARD)) {
            boolean kernelWg = AppPrefs.isKernelWireGuardEnabled(context);
            if (includeDefaults || kernelWg != rootMode) {
                builder.setKernelWireguard(kernelWg);
            }
        } else if (includeDefaults) {
            builder.setKernelWireguard(rootMode);
        }
        boolean tproxy = AppPrefs.isXrayTproxyModeEnabled(context);
        if (includeDefaults || tproxy) {
            builder.setXrayTproxyMode(tproxy);
        }
        String wgIfaceTemplate = AppPrefs.getRootWireGuardInterfaceNameTemplate(context);
        String defaultTemplate = AppPrefs.normalizeRootWireGuardInterfaceNameTemplate("");
        if (!TextUtils.isEmpty(wgIfaceTemplate) && (includeDefaults || !defaultTemplate.equals(wgIfaceTemplate))) {
            builder.setWgInterfaceName(wgIfaceTemplate);
        }
        return builder.build();
    }

    private static void parseRootSettings(WingsvProto.RootSettings root, ImportedConfig importedConfig) {
        importedConfig.hasRootSettings = true;
        if (root.hasEnabled()) {
            importedConfig.rootModeEnabled = root.getEnabled();
        }
        if (root.hasKernelWireguard()) {
            importedConfig.kernelWireguardEnabled = root.getKernelWireguard();
        }
        if (root.hasXrayTproxyMode()) {
            importedConfig.xrayTproxyModeEnabled = root.getXrayTproxyMode();
        }
        // split_tunnel_lockdown (proto field) is ignored: lockdown is now
        // unconditionally on whenever root mode is in effect.
        String iface = value(root.getWgInterfaceName());
        if (!TextUtils.isEmpty(iface)) {
            importedConfig.rootWireguardInterfaceName = iface;
        }
    }

    private static WingsvProto.AppPreferences buildAppPreferences(Context context, boolean includeDefaults) {
        WingsvProto.AppPreferences.Builder builder = WingsvProto.AppPreferences.newBuilder();
        if (context == null) {
            return builder.build();
        }
        String themeMode = AppPrefs.getThemeMode(context);
        if (includeDefaults || !AppPrefs.THEME_MODE_SYSTEM.equals(themeMode)) {
            builder.setThemeMode(toProtoThemeMode(themeMode));
        }
        boolean autoStartOnBoot = AppPrefs.isAutoStartOnBootEnabled(context);
        if (includeDefaults || autoStartOnBoot) {
            builder.setAutoStartOnBoot(autoStartOnBoot);
        }
        String dnsMode = AppPrefs.getDnsMode(context);
        if (includeDefaults || !AppPrefs.DNS_MODE_AUTO.equals(dnsMode)) {
            builder.setDnsMode(toProtoDnsMode(dnsMode));
        }
        return builder.build();
    }

    private static void parseAppPreferences(WingsvProto.AppPreferences ap, ImportedConfig importedConfig) {
        importedConfig.hasAppPreferences = true;
        if (ap.getThemeMode() != WingsvProto.ThemeMode.THEME_MODE_UNSPECIFIED) {
            importedConfig.themeMode = fromProtoThemeMode(ap.getThemeMode());
        }
        if (ap.hasAutoStartOnBoot()) {
            importedConfig.autoStartOnBoot = ap.getAutoStartOnBoot();
        }
        if (ap.getDnsMode() != WingsvProto.DnsMode.DNS_MODE_UNSPECIFIED) {
            importedConfig.dnsMode = fromProtoDnsMode(ap.getDnsMode());
        }
    }

    private static void parseGuardian(WingsvProto.Guardian g, ImportedConfig importedConfig) {
        importedConfig.hasGuardian = true;
        importedConfig.guardianWsUrl = g.getWsUrl() == null ? "" : g.getWsUrl();
        importedConfig.guardianClientId = g.getClientId() == null ? "" : g.getClientId();
        importedConfig.guardianClientToken =
            g.getClientToken() == null ? new byte[0] : g.getClientToken().toByteArray();
        importedConfig.guardianClientName = g.getClientName() == null ? "" : g.getClientName();
        importedConfig.guardianSyncMode = fromProtoSyncMode(g.getSyncMode());
        importedConfig.guardianPeriodicIntervalMinutes = g.getPeriodicIntervalMinutes();
        importedConfig.guardianAdminUsername = g.getAdminUsername() == null ? "" : g.getAdminUsername();
        importedConfig.guardianAdminId = g.getAdminId();
        importedConfig.guardianAdminAvatarVersion = g.getAdminAvatarVersion();
    }

    private static String fromProtoSyncMode(WingsvProto.GuardianSyncMode mode) {
        if (mode == WingsvProto.GuardianSyncMode.GUARDIAN_SYNC_MODE_PERIODIC) {
            return AppPrefs.GUARDIAN_SYNC_MODE_PERIODIC;
        }
        if (mode == WingsvProto.GuardianSyncMode.GUARDIAN_SYNC_MODE_FOREGROUND_ONLY) {
            return AppPrefs.GUARDIAN_SYNC_MODE_FOREGROUND_ONLY;
        }
        return AppPrefs.GUARDIAN_SYNC_MODE_ALWAYS;
    }

    private static WingsvProto.DnsMode toProtoDnsMode(String mode) {
        if (AppPrefs.DNS_MODE_UDP.equals(mode)) {
            return WingsvProto.DnsMode.DNS_MODE_UDP;
        }
        if (AppPrefs.DNS_MODE_DOH.equals(mode)) {
            return WingsvProto.DnsMode.DNS_MODE_DOH;
        }
        return WingsvProto.DnsMode.DNS_MODE_AUTO;
    }

    private static String fromProtoDnsMode(WingsvProto.DnsMode mode) {
        if (mode == WingsvProto.DnsMode.DNS_MODE_UDP) {
            return AppPrefs.DNS_MODE_UDP;
        }
        if (mode == WingsvProto.DnsMode.DNS_MODE_DOH) {
            return AppPrefs.DNS_MODE_DOH;
        }
        return AppPrefs.DNS_MODE_AUTO;
    }

    private static WingsvProto.ThemeMode toProtoThemeMode(String mode) {
        if (AppPrefs.THEME_MODE_LIGHT.equals(mode)) {
            return WingsvProto.ThemeMode.THEME_MODE_LIGHT;
        }
        if (AppPrefs.THEME_MODE_DARK.equals(mode)) {
            return WingsvProto.ThemeMode.THEME_MODE_DARK;
        }
        return WingsvProto.ThemeMode.THEME_MODE_SYSTEM;
    }

    private static String fromProtoThemeMode(WingsvProto.ThemeMode mode) {
        if (mode == WingsvProto.ThemeMode.THEME_MODE_LIGHT) {
            return AppPrefs.THEME_MODE_LIGHT;
        }
        if (mode == WingsvProto.ThemeMode.THEME_MODE_DARK) {
            return AppPrefs.THEME_MODE_DARK;
        }
        return AppPrefs.THEME_MODE_SYSTEM;
    }

    private static WingsvProto.SubscriptionHwid buildSubscriptionHwid(Context context, boolean includeDefaults) {
        WingsvProto.SubscriptionHwid.Builder builder = WingsvProto.SubscriptionHwid.newBuilder();
        if (context == null) {
            return builder.build();
        }
        SubscriptionHwidStore.SettingsModel settings = SubscriptionHwidStore.getSettings(context);
        if (includeDefaults || !settings.enabled) {
            builder.setEnabled(settings.enabled);
        }
        if (includeDefaults || settings.manualValues) {
            builder.setManualEnabled(settings.manualValues);
        }
        if (!TextUtils.isEmpty(settings.hwid)) {
            builder.setValue(settings.hwid);
        }
        if (!TextUtils.isEmpty(settings.deviceOs)) {
            builder.setDeviceOs(settings.deviceOs);
        }
        if (!TextUtils.isEmpty(settings.verOs)) {
            builder.setVerOs(settings.verOs);
        }
        if (!TextUtils.isEmpty(settings.deviceModel)) {
            builder.setDeviceModel(settings.deviceModel);
        }
        return builder.build();
    }

    private static void parseSubscriptionHwid(WingsvProto.SubscriptionHwid hwid, ImportedConfig importedConfig) {
        importedConfig.hasSubscriptionHwid = true;
        if (hwid.hasEnabled()) {
            importedConfig.subscriptionHwidEnabled = hwid.getEnabled();
        }
        if (hwid.hasManualEnabled()) {
            importedConfig.subscriptionHwidManualEnabled = hwid.getManualEnabled();
        }
        importedConfig.subscriptionHwidValue = value(hwid.getValue());
        importedConfig.subscriptionHwidDeviceOs = value(hwid.getDeviceOs());
        importedConfig.subscriptionHwidVerOs = value(hwid.getVerOs());
        importedConfig.subscriptionHwidDeviceModel = value(hwid.getDeviceModel());
    }

    private static WingsvProto.Sharing buildSharing(Context context, boolean includeDefaults) {
        WingsvProto.Sharing.Builder builder = WingsvProto.Sharing.newBuilder();
        if (context == null) {
            return builder.build();
        }
        boolean autoStart = AppPrefs.isSharingAutoStartOnBootEnabled(context);
        if (includeDefaults || autoStart) {
            builder.setAutoStartOnBoot(autoStart);
        }
        Set<TetherType> lastActiveTypes = AppPrefs.getSharingLastActiveTypes(context);
        if (includeDefaults || !lastActiveTypes.isEmpty()) {
            for (TetherType type : lastActiveTypes) {
                builder.addLastActiveTypes(type.commandName);
            }
        }
        String upstream = AppPrefs.getSharingUpstreamInterface(context);
        if (!TextUtils.isEmpty(upstream)) {
            builder.setUpstreamInterface(upstream);
        }
        String fallbackUpstream = AppPrefs.getSharingFallbackUpstreamInterface(context);
        if (!TextUtils.isEmpty(fallbackUpstream)) {
            builder.setFallbackUpstreamInterface(fallbackUpstream);
        }
        String masquerade = AppPrefs.getSharingMasqueradeMode(context);
        if (includeDefaults || !AppPrefs.SHARING_MASQUERADE_SIMPLE.equals(masquerade)) {
            builder.setMasqueradeMode(toProtoSharingMasqueradeMode(masquerade));
        }
        boolean disableIpv6 = AppPrefs.isSharingDisableIpv6Enabled(context);
        if (includeDefaults || !disableIpv6) {
            builder.setDisableIpv6(disableIpv6);
        }
        boolean dhcpWorkaround = AppPrefs.isSharingDhcpWorkaroundEnabled(context);
        if (includeDefaults || dhcpWorkaround) {
            builder.setDhcpWorkaround(dhcpWorkaround);
        }
        String wifiLock = AppPrefs.getSharingWifiLockMode(context);
        if (includeDefaults || !AppPrefs.SHARING_WIFI_LOCK_SYSTEM.equals(wifiLock)) {
            builder.setWifiLock(toProtoSharingWifiLock(wifiLock));
        }
        boolean repeaterSafe = AppPrefs.isSharingRepeaterSafeModeEnabled(context);
        if (includeDefaults || !repeaterSafe) {
            builder.setRepeaterSafeMode(repeaterSafe);
        }
        boolean tempHotspotSystem = AppPrefs.isSharingTempHotspotUseSystemEnabled(context);
        if (includeDefaults || tempHotspotSystem) {
            builder.setTempHotspotUseSystem(tempHotspotSystem);
        }
        String ipMonitor = AppPrefs.getSharingIpMonitorMode(context);
        if (includeDefaults || !AppPrefs.SHARING_IP_MONITOR_NETLINK.equals(ipMonitor)) {
            builder.setIpMonitorMode(toProtoSharingIpMonitorMode(ipMonitor));
        }
        return builder.build();
    }

    private static void parseSharing(WingsvProto.Sharing sharing, ImportedConfig importedConfig) {
        importedConfig.hasSharingSettings = true;
        if (sharing.hasAutoStartOnBoot()) {
            importedConfig.sharingAutoStartOnBoot = sharing.getAutoStartOnBoot();
        }
        for (String type : sharing.getLastActiveTypesList()) {
            String trimmed = value(type);
            if (!TextUtils.isEmpty(trimmed)) {
                importedConfig.sharingLastActiveTypes.add(trimmed);
            }
        }
        importedConfig.sharingUpstreamInterface = value(sharing.getUpstreamInterface());
        importedConfig.sharingFallbackUpstreamInterface = value(sharing.getFallbackUpstreamInterface());
        if (sharing.getMasqueradeMode() != WingsvProto.SharingMasqueradeMode.SHARING_MASQUERADE_MODE_UNSPECIFIED) {
            importedConfig.sharingMasqueradeMode = fromProtoSharingMasqueradeMode(sharing.getMasqueradeMode());
        }
        if (sharing.hasDisableIpv6()) {
            importedConfig.sharingDisableIpv6 = sharing.getDisableIpv6();
        }
        if (sharing.hasDhcpWorkaround()) {
            importedConfig.sharingDhcpWorkaround = sharing.getDhcpWorkaround();
        }
        if (sharing.getWifiLock() != WingsvProto.SharingWifiLock.SHARING_WIFI_LOCK_UNSPECIFIED) {
            importedConfig.sharingWifiLockMode = fromProtoSharingWifiLock(sharing.getWifiLock());
        }
        if (sharing.hasRepeaterSafeMode()) {
            importedConfig.sharingRepeaterSafeMode = sharing.getRepeaterSafeMode();
        }
        if (sharing.hasTempHotspotUseSystem()) {
            importedConfig.sharingTempHotspotUseSystem = sharing.getTempHotspotUseSystem();
        }
        if (sharing.getIpMonitorMode() != WingsvProto.SharingIpMonitorMode.SHARING_IP_MONITOR_MODE_UNSPECIFIED) {
            importedConfig.sharingIpMonitorMode = fromProtoSharingIpMonitorMode(sharing.getIpMonitorMode());
        }
    }

    private static WingsvProto.SharingMasqueradeMode toProtoSharingMasqueradeMode(String mode) {
        if (AppPrefs.SHARING_MASQUERADE_NONE.equals(mode)) {
            return WingsvProto.SharingMasqueradeMode.SHARING_MASQUERADE_MODE_NONE;
        }
        if (AppPrefs.SHARING_MASQUERADE_NETD.equals(mode)) {
            return WingsvProto.SharingMasqueradeMode.SHARING_MASQUERADE_MODE_NETD;
        }
        return WingsvProto.SharingMasqueradeMode.SHARING_MASQUERADE_MODE_SIMPLE;
    }

    private static String fromProtoSharingMasqueradeMode(WingsvProto.SharingMasqueradeMode mode) {
        if (mode == WingsvProto.SharingMasqueradeMode.SHARING_MASQUERADE_MODE_NONE) {
            return AppPrefs.SHARING_MASQUERADE_NONE;
        }
        if (mode == WingsvProto.SharingMasqueradeMode.SHARING_MASQUERADE_MODE_NETD) {
            return AppPrefs.SHARING_MASQUERADE_NETD;
        }
        return AppPrefs.SHARING_MASQUERADE_SIMPLE;
    }

    private static WingsvProto.SharingWifiLock toProtoSharingWifiLock(String mode) {
        if (AppPrefs.SHARING_WIFI_LOCK_FULL.equals(mode)) {
            return WingsvProto.SharingWifiLock.SHARING_WIFI_LOCK_FULL;
        }
        if (AppPrefs.SHARING_WIFI_LOCK_HIGH_PERF.equals(mode)) {
            return WingsvProto.SharingWifiLock.SHARING_WIFI_LOCK_HIGH_PERF;
        }
        if (AppPrefs.SHARING_WIFI_LOCK_LOW_LATENCY.equals(mode)) {
            return WingsvProto.SharingWifiLock.SHARING_WIFI_LOCK_LOW_LATENCY;
        }
        return WingsvProto.SharingWifiLock.SHARING_WIFI_LOCK_SYSTEM;
    }

    private static String fromProtoSharingWifiLock(WingsvProto.SharingWifiLock mode) {
        if (mode == WingsvProto.SharingWifiLock.SHARING_WIFI_LOCK_FULL) {
            return AppPrefs.SHARING_WIFI_LOCK_FULL;
        }
        if (mode == WingsvProto.SharingWifiLock.SHARING_WIFI_LOCK_HIGH_PERF) {
            return AppPrefs.SHARING_WIFI_LOCK_HIGH_PERF;
        }
        if (mode == WingsvProto.SharingWifiLock.SHARING_WIFI_LOCK_LOW_LATENCY) {
            return AppPrefs.SHARING_WIFI_LOCK_LOW_LATENCY;
        }
        return AppPrefs.SHARING_WIFI_LOCK_SYSTEM;
    }

    private static WingsvProto.SharingIpMonitorMode toProtoSharingIpMonitorMode(String mode) {
        if (AppPrefs.SHARING_IP_MONITOR_NETLINK_ROOT.equals(mode)) {
            return WingsvProto.SharingIpMonitorMode.SHARING_IP_MONITOR_MODE_NETLINK_ROOT;
        }
        if (AppPrefs.SHARING_IP_MONITOR_POLL.equals(mode)) {
            return WingsvProto.SharingIpMonitorMode.SHARING_IP_MONITOR_MODE_POLL;
        }
        if (AppPrefs.SHARING_IP_MONITOR_POLL_ROOT.equals(mode)) {
            return WingsvProto.SharingIpMonitorMode.SHARING_IP_MONITOR_MODE_POLL_ROOT;
        }
        return WingsvProto.SharingIpMonitorMode.SHARING_IP_MONITOR_MODE_NETLINK;
    }

    private static String fromProtoSharingIpMonitorMode(WingsvProto.SharingIpMonitorMode mode) {
        if (mode == WingsvProto.SharingIpMonitorMode.SHARING_IP_MONITOR_MODE_NETLINK_ROOT) {
            return AppPrefs.SHARING_IP_MONITOR_NETLINK_ROOT;
        }
        if (mode == WingsvProto.SharingIpMonitorMode.SHARING_IP_MONITOR_MODE_POLL) {
            return AppPrefs.SHARING_IP_MONITOR_POLL;
        }
        if (mode == WingsvProto.SharingIpMonitorMode.SHARING_IP_MONITOR_MODE_POLL_ROOT) {
            return AppPrefs.SHARING_IP_MONITOR_POLL_ROOT;
        }
        return AppPrefs.SHARING_IP_MONITOR_NETLINK;
    }

    private static WingsvProto.ByeDpi buildByeDpi(Context context, boolean includeDefaults) {
        WingsvProto.ByeDpi.Builder builder = WingsvProto.ByeDpi.newBuilder();
        if (context == null) {
            return builder.build();
        }
        ByeDpiSettings s = ByeDpiStore.getSettings(context);
        if (s == null) {
            return builder.build();
        }
        if (includeDefaults || s.launchOnXrayStart) {
            builder.setAutoStartWithXray(s.launchOnXrayStart);
        }
        if (includeDefaults || s.useCommandLineSettings) {
            builder.setUseCommandSettings(s.useCommandLineSettings);
        }
        if (!TextUtils.isEmpty(s.proxyIp) && (includeDefaults || !"127.0.0.1".equals(s.proxyIp))) {
            builder.setProxyIp(s.proxyIp);
        }
        if (s.proxyPort > 0 && (includeDefaults || s.proxyPort != 1080)) {
            builder.setProxyPort(s.proxyPort);
        }
        if (includeDefaults || !s.proxyAuthEnabled) {
            builder.setProxyAuthEnabled(s.proxyAuthEnabled);
        }
        if (!TextUtils.isEmpty(s.proxyUsername)) {
            builder.setProxyUsername(s.proxyUsername);
        }
        if (!TextUtils.isEmpty(s.proxyPassword)) {
            builder.setProxyPassword(s.proxyPassword);
        }
        if (s.maxConnections > 0 && (includeDefaults || s.maxConnections != 512)) {
            builder.setMaxConnections(s.maxConnections);
        }
        if (s.bufferSize > 0 && (includeDefaults || s.bufferSize != 16384)) {
            builder.setBufferSize(s.bufferSize);
        }
        if (includeDefaults || s.noDomain) {
            builder.setNoDomain(s.noDomain);
        }
        if (includeDefaults || s.tcpFastOpen) {
            builder.setTcpFastOpen(s.tcpFastOpen);
        }
        if (s.hostsMode != null && (includeDefaults || s.hostsMode != ByeDpiSettings.HostsMode.DISABLE)) {
            builder.setHostsMode(toProtoByeDpiHostsMode(s.hostsMode));
        }
        if (!TextUtils.isEmpty(s.hostsBlacklist)) {
            builder.setHostsBlacklist(s.hostsBlacklist);
        }
        if (!TextUtils.isEmpty(s.hostsWhitelist)) {
            builder.setHostsWhitelist(s.hostsWhitelist);
        }
        if (s.defaultTtl > 0 || includeDefaults) {
            builder.setDefaultTtl(s.defaultTtl);
        }
        if (s.desyncMethod != null && (includeDefaults || s.desyncMethod != ByeDpiSettings.DesyncMethod.OOB)) {
            builder.setDesyncMethod(toProtoByeDpiDesyncMethod(s.desyncMethod));
        }
        if (includeDefaults || s.splitPosition != 1) {
            builder.setSplitPosition(s.splitPosition);
        }
        if (includeDefaults || s.splitAtHost) {
            builder.setSplitAtHost(s.splitAtHost);
        }
        if (includeDefaults || s.dropSack) {
            builder.setDropSack(s.dropSack);
        }
        if (includeDefaults || s.fakeTtl != 8) {
            builder.setFakeTtl(s.fakeTtl);
        }
        if (includeDefaults || s.fakeOffset != 0) {
            builder.setFakeOffset(s.fakeOffset);
        }
        if (!TextUtils.isEmpty(s.fakeSni) && (includeDefaults || !"www.iana.org".equals(s.fakeSni))) {
            builder.setFakeSni(s.fakeSni);
        }
        if (!TextUtils.isEmpty(s.oobData) && (includeDefaults || !"a".equals(s.oobData))) {
            builder.setOobData(s.oobData);
        }
        if (includeDefaults || !s.desyncHttp) {
            builder.setDesyncHttp(s.desyncHttp);
        }
        if (includeDefaults || !s.desyncHttps) {
            builder.setDesyncHttps(s.desyncHttps);
        }
        if (includeDefaults || !s.desyncUdp) {
            builder.setDesyncUdp(s.desyncUdp);
        }
        if (includeDefaults || s.hostMixedCase) {
            builder.setHostMixedCase(s.hostMixedCase);
        }
        if (includeDefaults || s.domainMixedCase) {
            builder.setDomainMixedCase(s.domainMixedCase);
        }
        if (includeDefaults || s.hostRemoveSpaces) {
            builder.setHostRemoveSpaces(s.hostRemoveSpaces);
        }
        if (includeDefaults || !s.tlsRecordSplit) {
            builder.setTlsrecEnabled(s.tlsRecordSplit);
        }
        if (includeDefaults || s.tlsRecordSplitPosition != 1) {
            builder.setTlsrecPosition(s.tlsRecordSplitPosition);
        }
        if (includeDefaults || !s.tlsRecordSplitAtSni) {
            builder.setTlsrecAtSni(s.tlsRecordSplitAtSni);
        }
        if (includeDefaults || s.udpFakeCount != 1) {
            builder.setUdpFakeCount(s.udpFakeCount);
        }
        if (
            !TextUtils.isEmpty(s.rawCommandArgs) &&
            (includeDefaults || !ByeDpiSettings.DEFAULT_COMMAND_ARGS.equals(s.rawCommandArgs))
        ) {
            builder.setCmdArgs(s.rawCommandArgs);
        }
        if (includeDefaults || s.proxyTestDelaySeconds != 1) {
            builder.setProxytestDelay(s.proxyTestDelaySeconds);
        }
        if (includeDefaults || s.proxyTestRequests != 1) {
            builder.setProxytestRequests(s.proxyTestRequests);
        }
        if (includeDefaults || s.proxyTestConcurrencyLimit != 20) {
            builder.setProxytestLimit(s.proxyTestConcurrencyLimit);
        }
        if (includeDefaults || s.proxyTestTimeoutSeconds != 5) {
            builder.setProxytestTimeout(s.proxyTestTimeoutSeconds);
        }
        if (!TextUtils.isEmpty(s.proxyTestSni) && (includeDefaults || !"max.ru".equals(s.proxyTestSni))) {
            builder.setProxytestSni(s.proxyTestSni);
        }
        if (includeDefaults || s.proxyTestUseCustomStrategies) {
            builder.setProxytestUseCustomStrategies(s.proxyTestUseCustomStrategies);
        }
        if (!TextUtils.isEmpty(s.proxyTestCustomStrategies)) {
            builder.setProxytestCustomStrategies(s.proxyTestCustomStrategies);
        }
        return builder.build();
    }

    private static void parseByeDpi(WingsvProto.ByeDpi b, ImportedConfig importedConfig) {
        importedConfig.hasByeDpiSettings = true;
        ByeDpiSettings s = new ByeDpiSettings();
        s.launchOnXrayStart = b.hasAutoStartWithXray() ? b.getAutoStartWithXray() : false;
        s.useCommandLineSettings = b.hasUseCommandSettings() ? b.getUseCommandSettings() : false;
        s.proxyIp = !TextUtils.isEmpty(value(b.getProxyIp())) ? value(b.getProxyIp()) : "127.0.0.1";
        s.proxyPort = b.hasProxyPort() ? b.getProxyPort() : 1080;
        s.proxyAuthEnabled = b.hasProxyAuthEnabled() ? b.getProxyAuthEnabled() : true;
        s.proxyUsername = value(b.getProxyUsername());
        s.proxyPassword = value(b.getProxyPassword());
        s.maxConnections = b.hasMaxConnections() ? b.getMaxConnections() : 512;
        s.bufferSize = b.hasBufferSize() ? b.getBufferSize() : 16384;
        s.noDomain = b.hasNoDomain() && b.getNoDomain();
        s.tcpFastOpen = b.hasTcpFastOpen() && b.getTcpFastOpen();
        s.hostsMode =
            b.getHostsMode() != WingsvProto.ByeDpiHostsMode.BYE_DPI_HOSTS_MODE_UNSPECIFIED
                ? fromProtoByeDpiHostsMode(b.getHostsMode())
                : ByeDpiSettings.HostsMode.DISABLE;
        s.hostsBlacklist = value(b.getHostsBlacklist());
        s.hostsWhitelist = value(b.getHostsWhitelist());
        s.defaultTtl = b.hasDefaultTtl() ? b.getDefaultTtl() : 0;
        s.desyncMethod =
            b.getDesyncMethod() != WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_UNSPECIFIED
                ? fromProtoByeDpiDesyncMethod(b.getDesyncMethod())
                : ByeDpiSettings.DesyncMethod.OOB;
        s.splitPosition = b.hasSplitPosition() ? b.getSplitPosition() : 1;
        s.splitAtHost = b.hasSplitAtHost() && b.getSplitAtHost();
        s.dropSack = b.hasDropSack() && b.getDropSack();
        s.fakeTtl = b.hasFakeTtl() ? b.getFakeTtl() : 8;
        s.fakeOffset = b.hasFakeOffset() ? b.getFakeOffset() : 0;
        s.fakeSni = !TextUtils.isEmpty(value(b.getFakeSni())) ? value(b.getFakeSni()) : "www.iana.org";
        s.oobData = !TextUtils.isEmpty(value(b.getOobData())) ? value(b.getOobData()) : "a";
        s.desyncHttp = !b.hasDesyncHttp() || b.getDesyncHttp();
        s.desyncHttps = !b.hasDesyncHttps() || b.getDesyncHttps();
        s.desyncUdp = !b.hasDesyncUdp() || b.getDesyncUdp();
        s.hostMixedCase = b.hasHostMixedCase() && b.getHostMixedCase();
        s.domainMixedCase = b.hasDomainMixedCase() && b.getDomainMixedCase();
        s.hostRemoveSpaces = b.hasHostRemoveSpaces() && b.getHostRemoveSpaces();
        s.tlsRecordSplit = !b.hasTlsrecEnabled() || b.getTlsrecEnabled();
        s.tlsRecordSplitPosition = b.hasTlsrecPosition() ? b.getTlsrecPosition() : 1;
        s.tlsRecordSplitAtSni = !b.hasTlsrecAtSni() || b.getTlsrecAtSni();
        s.udpFakeCount = b.hasUdpFakeCount() ? b.getUdpFakeCount() : 1;
        s.rawCommandArgs = !TextUtils.isEmpty(value(b.getCmdArgs()))
            ? value(b.getCmdArgs())
            : ByeDpiSettings.DEFAULT_COMMAND_ARGS;
        s.proxyTestDelaySeconds = b.hasProxytestDelay() ? b.getProxytestDelay() : 1;
        s.proxyTestRequests = b.hasProxytestRequests() ? b.getProxytestRequests() : 1;
        s.proxyTestConcurrencyLimit = b.hasProxytestLimit() ? b.getProxytestLimit() : 20;
        s.proxyTestTimeoutSeconds = b.hasProxytestTimeout() ? b.getProxytestTimeout() : 5;
        s.proxyTestSni = !TextUtils.isEmpty(value(b.getProxytestSni())) ? value(b.getProxytestSni()) : "max.ru";
        s.proxyTestUseCustomStrategies = b.hasProxytestUseCustomStrategies() && b.getProxytestUseCustomStrategies();
        s.proxyTestCustomStrategies = value(b.getProxytestCustomStrategies());
        importedConfig.byeDpiSettings = s;
    }

    private static WingsvProto.ByeDpiHostsMode toProtoByeDpiHostsMode(ByeDpiSettings.HostsMode mode) {
        if (mode == ByeDpiSettings.HostsMode.BLACKLIST) {
            return WingsvProto.ByeDpiHostsMode.BYE_DPI_HOSTS_MODE_BLACKLIST;
        }
        if (mode == ByeDpiSettings.HostsMode.WHITELIST) {
            return WingsvProto.ByeDpiHostsMode.BYE_DPI_HOSTS_MODE_WHITELIST;
        }
        return WingsvProto.ByeDpiHostsMode.BYE_DPI_HOSTS_MODE_DISABLE;
    }

    private static ByeDpiSettings.HostsMode fromProtoByeDpiHostsMode(WingsvProto.ByeDpiHostsMode mode) {
        if (mode == WingsvProto.ByeDpiHostsMode.BYE_DPI_HOSTS_MODE_BLACKLIST) {
            return ByeDpiSettings.HostsMode.BLACKLIST;
        }
        if (mode == WingsvProto.ByeDpiHostsMode.BYE_DPI_HOSTS_MODE_WHITELIST) {
            return ByeDpiSettings.HostsMode.WHITELIST;
        }
        return ByeDpiSettings.HostsMode.DISABLE;
    }

    private static WingsvProto.ByeDpiDesyncMethod toProtoByeDpiDesyncMethod(ByeDpiSettings.DesyncMethod mode) {
        if (mode == ByeDpiSettings.DesyncMethod.NONE) {
            return WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_NONE;
        }
        if (mode == ByeDpiSettings.DesyncMethod.SPLIT) {
            return WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_SPLIT;
        }
        if (mode == ByeDpiSettings.DesyncMethod.DISORDER) {
            return WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_DISORDER;
        }
        if (mode == ByeDpiSettings.DesyncMethod.FAKE) {
            return WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_FAKE;
        }
        if (mode == ByeDpiSettings.DesyncMethod.DISOOB) {
            return WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_DISOOB;
        }
        return WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_OOB;
    }

    private static ByeDpiSettings.DesyncMethod fromProtoByeDpiDesyncMethod(WingsvProto.ByeDpiDesyncMethod mode) {
        if (mode == WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_NONE) {
            return ByeDpiSettings.DesyncMethod.NONE;
        }
        if (mode == WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_SPLIT) {
            return ByeDpiSettings.DesyncMethod.SPLIT;
        }
        if (mode == WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_DISORDER) {
            return ByeDpiSettings.DesyncMethod.DISORDER;
        }
        if (mode == WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_FAKE) {
            return ByeDpiSettings.DesyncMethod.FAKE;
        }
        if (mode == WingsvProto.ByeDpiDesyncMethod.BYE_DPI_DESYNC_METHOD_DISOOB) {
            return ByeDpiSettings.DesyncMethod.DISOOB;
        }
        return ByeDpiSettings.DesyncMethod.OOB;
    }

    private static WingsvProto.AppRouting buildAppRouting(Context context) {
        AppRoutingMode mode = AppPrefs.getAppRoutingMode(context);
        WingsvProto.AppRouting.Builder builder = WingsvProto.AppRouting.newBuilder()
            .setMode(toProtoAppRoutingMode(mode))
            // legacy bypass field for backward-compatible import on older clients
            .setBypass(!mode.isWhitelistFamily());
        Set<String> bypassPackages = AppPrefs.getAppRoutingPackages(context, AppRoutingMode.BYPASS);
        for (String packageName : bypassPackages) {
            if (!TextUtils.isEmpty(value(packageName))) {
                builder.addBypassPackages(value(packageName));
            }
        }
        Set<String> whitelistPackages = AppPrefs.getAppRoutingPackages(context, AppRoutingMode.WHITELIST);
        for (String packageName : whitelistPackages) {
            if (!TextUtils.isEmpty(value(packageName))) {
                builder.addWhitelistPackages(value(packageName));
            }
        }
        Set<String> activePackages = AppPrefs.getAppRoutingPackages(context, mode);
        for (String packageName : activePackages) {
            if (!TextUtils.isEmpty(value(packageName))) {
                builder.addPackages(value(packageName));
            }
        }
        return builder.build();
    }

    private static WingsvProto.AppRoutingMode toProtoAppRoutingMode(AppRoutingMode mode) {
        if (mode == AppRoutingMode.OFF) {
            return WingsvProto.AppRoutingMode.APP_ROUTING_MODE_OFF;
        }
        if (mode == AppRoutingMode.WHITELIST) {
            return WingsvProto.AppRoutingMode.APP_ROUTING_MODE_WHITELIST;
        }
        if (mode == AppRoutingMode.XWHITELIST) {
            return WingsvProto.AppRoutingMode.APP_ROUTING_MODE_XWHITELIST;
        }
        if (mode == AppRoutingMode.XBYPASS) {
            return WingsvProto.AppRoutingMode.APP_ROUTING_MODE_XBYPASS;
        }
        return WingsvProto.AppRoutingMode.APP_ROUTING_MODE_BYPASS;
    }

    private static AppRoutingMode fromProtoAppRoutingMode(WingsvProto.AppRoutingMode mode) {
        if (mode == WingsvProto.AppRoutingMode.APP_ROUTING_MODE_OFF) {
            return AppRoutingMode.OFF;
        }
        if (mode == WingsvProto.AppRoutingMode.APP_ROUTING_MODE_WHITELIST) {
            return AppRoutingMode.WHITELIST;
        }
        if (mode == WingsvProto.AppRoutingMode.APP_ROUTING_MODE_XWHITELIST) {
            return AppRoutingMode.XWHITELIST;
        }
        if (mode == WingsvProto.AppRoutingMode.APP_ROUTING_MODE_XBYPASS) {
            return AppRoutingMode.XBYPASS;
        }
        return AppRoutingMode.BYPASS;
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
        if (matchType == XrayRoutingRule.MatchType.DOMAIN) {
            return WingsvProto.XrayRoutingMatchType.XRAY_ROUTING_MATCH_DOMAIN;
        }
        if (matchType == XrayRoutingRule.MatchType.IP) {
            return WingsvProto.XrayRoutingMatchType.XRAY_ROUTING_MATCH_IP;
        }
        if (matchType == XrayRoutingRule.MatchType.PORT) {
            return WingsvProto.XrayRoutingMatchType.XRAY_ROUTING_MATCH_PORT;
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

    private static WingsvProto.Turn buildTurn(ProxySettings settings, boolean includeDefaults) throws Exception {
        WingsvProto.Turn.Builder builder = WingsvProto.Turn.newBuilder();
        setEndpoint(builder, value(settings.endpoint), true);
        if (includeDefaults || !TextUtils.isEmpty(value(settings.vkLink))) {
            builder.setLink(value(settings.vkLink));
        }
        if (settings.vkLinks != null) {
            for (String entry : settings.vkLinks) {
                if (!TextUtils.isEmpty(entry)) {
                    builder.addLinks(entry);
                }
            }
        }
        if (!TextUtils.isEmpty(value(settings.vkLinkSecondary))) {
            builder.setLinkSecondary(value(settings.vkLinkSecondary));
        }
        if (settings.credsGroupSize > 0 && (includeDefaults || settings.credsGroupSize != 12)) {
            builder.setCredsGroupSize(settings.credsGroupSize);
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
        if (includeDefaults || settings.manualCaptcha) {
            builder.setManualCaptcha(settings.manualCaptcha);
        }
        String captchaSolver = value(settings.captchaAutoSolver);
        if (!TextUtils.isEmpty(captchaSolver) && (includeDefaults || !"v2".equals(captchaSolver))) {
            builder.setCaptchaAutoSolver(captchaSolver);
        }
        if (includeDefaults || !settings.vkTurnRestartOnNetworkChange) {
            builder.setRestartOnNetworkChange(settings.vkTurnRestartOnNetworkChange);
        }
        if (
            settings.vkTurnRuntimeMode != null &&
            (includeDefaults || settings.vkTurnRuntimeMode != ProxyRuntimeMode.VPN)
        ) {
            builder.setRuntimeMode(toProtoRuntimeMode(settings.vkTurnRuntimeMode));
        }
        for (String entry : splitUserDnsEntries(settings.vkTurnUserDns)) {
            builder.addUserDns(entry);
        }
        TunnelMode vkTurnMode =
            settings.backendType == BackendType.AMNEZIAWG ? TunnelMode.AMNEZIAWG : TunnelMode.WIREGUARD;
        if (includeDefaults || vkTurnMode != TunnelMode.WIREGUARD) {
            builder.setTunnelMode(vkTurnMode.toProto());
        }
        String wrapMode = AppPrefs.normalizeWrapMode(settings.vkTurnWrapMode);
        if (includeDefaults || !"preferred".equals(wrapMode)) {
            builder.setWrapMode(wrapModeFromPref(wrapMode));
        }
        String wrapCipher = AppPrefs.normalizeWrapCipher(settings.vkTurnWrapCipher);
        if (includeDefaults || !"srtp-aes-gcm".equals(wrapCipher)) {
            builder.addWrapCiphers(wrapCipherFromPref(wrapCipher));
        }
        byte[] wrapKey = hexToBytes(settings.vkTurnWrapKeyHex);
        if (wrapKey.length > 0) {
            builder.setWrapKey(com.google.protobuf.ByteString.copyFrom(wrapKey));
        }
        // Only serialize when it differs from default (in-band ON).
        if (includeDefaults || !settings.vkTurnWrapSendKey) {
            builder.setWrapKeyDelivery(
                settings.vkTurnWrapSendKey
                    ? WingsvProto.WrapKeyDelivery.WRAP_KEY_DELIVERY_IN_BAND
                    : WingsvProto.WrapKeyDelivery.WRAP_KEY_DELIVERY_OFF
            );
        }
        return builder.build();
    }

    /** Раскладывает многострочный/CSV ввод user-dns на отдельные записи —
     *  тот же формат, что принимает vk-turn-proxy через -user-dns. */
    private static java.util.List<String> splitUserDnsEntries(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        String[] tokens = raw.split("[,;\\r\\n]+");
        for (String token : tokens) {
            String trimmed = token == null ? "" : token.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static WingsvProto.ProxyRuntimeMode toProtoRuntimeMode(ProxyRuntimeMode mode) {
        if (mode == ProxyRuntimeMode.PROXY) {
            return WingsvProto.ProxyRuntimeMode.PROXY_RUNTIME_MODE_PROXY;
        }
        return WingsvProto.ProxyRuntimeMode.PROXY_RUNTIME_MODE_VPN;
    }

    private static ProxyRuntimeMode fromProtoRuntimeMode(WingsvProto.ProxyRuntimeMode mode) {
        if (mode == WingsvProto.ProxyRuntimeMode.PROXY_RUNTIME_MODE_PROXY) {
            return ProxyRuntimeMode.PROXY;
        }
        return ProxyRuntimeMode.VPN;
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

    public static ImportedConfig parseProtoConfig(WingsvProto.Config config) throws Exception {
        if (config.getVer() <= 0) {
            throw new IllegalArgumentException(
                WingsApplication.getStringSafe(R.string.import_parser_version_missing_or_invalid)
            );
        }

        ImportedConfig importedConfig = new ImportedConfig();
        if (config.getBackend() != WingsvProto.BackendType.BACKEND_TYPE_UNSPECIFIED) {
            BackendType resolvedBackend = BackendType.fromProto(config.getBackend());
            // New proto encodes VK TURN as BACKEND_TYPE_VK_TURN with the WG/AWG choice
            // carried by Turn.tunnel_mode; recover the AWG variant so it is not lost.
            // Legacy BACKEND_TYPE_AMNEZIAWG already resolves to AMNEZIAWG directly.
            if (
                resolvedBackend == BackendType.VK_TURN_WIREGUARD &&
                config.hasTurn() &&
                config.getTurn().getTunnelMode() == WingsvProto.TunnelMode.TUNNEL_MODE_AMNEZIAWG
            ) {
                resolvedBackend = BackendType.AMNEZIAWG;
            }
            importedConfig.backendType = resolvedBackend;
        } else {
            importedConfig.backendType = null;
            importedConfig.updateBackendType = false;
        }
        parseBackendProfileLists(config, importedConfig);
        boolean allSettings = config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_ALL;
        importedConfig.hasAllSettings = allSettings;
        boolean handled = allSettings;

        if (allSettings && config.hasAppRouting()) {
            parseAppRouting(config.getAppRouting(), importedConfig);
        }
        if (config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_APP_ROUTING) {
            importedConfig.updateBackendType = false;
            if (config.hasAppRouting()) {
                parseAppRouting(config.getAppRouting(), importedConfig);
            }
            return importedConfig;
        }
        if (config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_XRAY_ROUTING) {
            importedConfig.updateBackendType = false;
            importedConfig.backendType = BackendType.XRAY;
            if (config.hasXray() && config.getXray().hasRouting()) {
                parseXrayRouting(config.getXray().getRouting(), importedConfig);
            }
            return importedConfig;
        }

        // Single VK TURN profile share link: Turn (with title + tunnel_mode) plus
        // the embedded transport (wg OR awg). The transport choice follows
        // tunnel_mode so importTransportProfileId picks the matching store.
        if (config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_VK_TURN_PROFILE) {
            boolean awgTransport =
                config.hasTurn() && config.getTurn().getTunnelMode() == WingsvProto.TunnelMode.TUNNEL_MODE_AMNEZIAWG;
            importedConfig.backendType = awgTransport ? BackendType.AMNEZIAWG : BackendType.VK_TURN_WIREGUARD;
            if (config.hasTurn()) {
                parseTurn(config.getTurn(), importedConfig);
            }
            if (config.hasAwg()) {
                importedConfig.hasAmneziaSettings = true;
                importedConfig.awgQuickConfig = value(config.getAwg().getAwgQuickConfig());
                if (!TextUtils.isEmpty(value(config.getAwg().getTitle()))) {
                    importedConfig.importedAmneziaTitle = value(config.getAwg().getTitle());
                }
            }
            if (config.hasWg()) {
                parseWireGuard(config.getWg(), importedConfig);
            }
            return importedConfig;
        }

        if (
            allSettings ||
            config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_XRAY ||
            (importedConfig.backendType != null && importedConfig.backendType.usesXrayCore()) ||
            config.hasXray()
        ) {
            if (!allSettings && (importedConfig.backendType == null || !importedConfig.backendType.usesXrayCore())) {
                importedConfig.backendType = BackendType.XRAY;
            }
            parseXray(config, importedConfig);
            if (config.hasTurn()) {
                parseTurn(config.getTurn(), importedConfig);
            }
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
                if (!TextUtils.isEmpty(value(config.getAwg().getTitle()))) {
                    importedConfig.importedAmneziaTitle = value(config.getAwg().getTitle());
                }
            }
            handled = true;
            if (!allSettings) {
                return importedConfig;
            }
        }

        if (
            allSettings ||
            config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_WB_STREAM ||
            importedConfig.backendType == BackendType.WB_STREAM ||
            importedConfig.backendType == BackendType.WB_STREAM_AMNEZIAWG ||
            config.hasWbStream()
        ) {
            if (config.hasWbStream()) {
                parseWbStream(config.getWbStream(), importedConfig);
            }
            if (!allSettings) {
                importedConfig.backendType =
                    importedConfig.wbStreamTunnelMode == wings.v.core.TunnelMode.AMNEZIAWG
                        ? BackendType.WB_STREAM_AMNEZIAWG
                        : BackendType.WB_STREAM;
            }
            if (config.hasTurn()) {
                parseTurn(config.getTurn(), importedConfig);
            }
            if (config.hasWg()) {
                parseWireGuard(config.getWg(), importedConfig);
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

        if (allSettings || config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_XPOSED || config.hasXposed()) {
            if (config.hasXposed()) {
                parseXposed(config.getXposed(), importedConfig);
            }
            if (!allSettings && config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_XPOSED) {
                importedConfig.updateBackendType = false;
            }
            handled = true;
        }

        if (allSettings || config.hasRoot()) {
            if (config.hasRoot()) {
                parseRootSettings(config.getRoot(), importedConfig);
            }
            handled = true;
        }
        if (allSettings || config.hasAppPreferences()) {
            if (config.hasAppPreferences()) {
                parseAppPreferences(config.getAppPreferences(), importedConfig);
            }
            handled = true;
        }
        if (allSettings || config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_GUARDIAN || config.hasGuardian()) {
            if (config.hasGuardian()) {
                parseGuardian(config.getGuardian(), importedConfig);
            }
            if (!allSettings && config.getType() == WingsvProto.ConfigType.CONFIG_TYPE_GUARDIAN) {
                importedConfig.updateBackendType = false;
            }
            handled = true;
        }
        if (allSettings || config.hasSubscriptionHwid()) {
            if (config.hasSubscriptionHwid()) {
                parseSubscriptionHwid(config.getSubscriptionHwid(), importedConfig);
            }
            handled = true;
        }
        if (allSettings || config.hasSharing()) {
            if (config.hasSharing()) {
                parseSharing(config.getSharing(), importedConfig);
            }
            handled = true;
        }
        if (allSettings || config.hasByeDpi()) {
            if (config.hasByeDpi()) {
                parseByeDpi(config.getByeDpi(), importedConfig);
            }
            handled = true;
        }

        if (!handled) {
            throw new IllegalArgumentException(WingsApplication.getStringSafe(R.string.import_parser_unsupported_type));
        }
        return importedConfig;
    }

    private static void parseWbStream(WingsvProto.WbStream wb, ImportedConfig importedConfig) {
        importedConfig.hasWbStreamSettings = true;
        importedConfig.wbStreamRoomId = value(wb.getRoomId());
        importedConfig.wbStreamDisplayName = value(wb.getDisplayName());
        importedConfig.wbStreamExchangeViaVkTurn = wb.getExchangeViaVkTurn();
        importedConfig.wbStreamE2eEnabled = wb.getE2EEnabled();
        if (!wb.getE2ESecret().isEmpty()) {
            importedConfig.wbStreamE2eSecret = android.util.Base64.encodeToString(
                wb.getE2ESecret().toByteArray(),
                android.util.Base64.NO_WRAP
            );
        }
        if (wb.hasRoomCount()) {
            importedConfig.wbStreamRoomCount = wb.getRoomCount();
        }
        importedConfig.wbStreamTunnelMode = wings.v.core.TunnelMode.fromProto(wb.getTunnelMode());
    }

    private static void parseTurn(WingsvProto.Turn turn, ImportedConfig importedConfig) {
        importedConfig.hasTurnSettings = true;
        if (!TextUtils.isEmpty(value(turn.getTitle()))) {
            importedConfig.importedVkTurnTitle = value(turn.getTitle());
        }
        importedConfig.endpoint = turn.hasEndpoint() ? formatEndpoint(turn.getEndpoint()) : "";
        importedConfig.link = value(turn.getLink());
        importedConfig.links = new java.util.ArrayList<>();
        for (String entry : turn.getLinksList()) {
            String trimmed = value(entry);
            if (!TextUtils.isEmpty(trimmed)) {
                importedConfig.links.add(trimmed);
            }
        }
        if (importedConfig.links.isEmpty() && !TextUtils.isEmpty(importedConfig.link)) {
            importedConfig.links.add(importedConfig.link);
        }
        importedConfig.linkSecondary = value(turn.getLinkSecondary());
        if (turn.hasCredsGroupSize()) {
            importedConfig.credsGroupSize = turn.getCredsGroupSize();
        }
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
        if (turn.hasManualCaptcha()) {
            importedConfig.manualCaptcha = turn.getManualCaptcha();
        }
        String solver = value(turn.getCaptchaAutoSolver());
        if (!TextUtils.isEmpty(solver)) {
            importedConfig.captchaAutoSolver = solver;
        }
        if (turn.hasRestartOnNetworkChange()) {
            importedConfig.vkTurnRestartOnNetworkChange = turn.getRestartOnNetworkChange();
        }
        if (turn.getRuntimeMode() != WingsvProto.ProxyRuntimeMode.PROXY_RUNTIME_MODE_UNSPECIFIED) {
            importedConfig.vkTurnRuntimeMode = fromProtoRuntimeMode(turn.getRuntimeMode());
        }
        importedConfig.vkTurnTunnelMode = wings.v.core.TunnelMode.fromProto(turn.getTunnelMode());
        if (turn.getUserDnsCount() > 0) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < turn.getUserDnsCount(); i++) {
                String entry = turn.getUserDns(i);
                if (TextUtils.isEmpty(entry)) {
                    continue;
                }
                if (joined.length() > 0) {
                    joined.append('\n');
                }
                joined.append(entry.trim());
            }
            importedConfig.vkTurnUserDns = joined.toString();
        }
        if (turn.getWrapMode() != WingsvProto.WrapMode.WRAP_MODE_UNSPECIFIED) {
            importedConfig.vkTurnWrapMode = wrapModeToPref(turn.getWrapMode());
        }
        if (turn.getWrapCiphersCount() > 0) {
            importedConfig.vkTurnWrapCipher = wrapCipherToPref(turn.getWrapCiphers(0));
        }
        if (!turn.getWrapKey().isEmpty()) {
            importedConfig.vkTurnWrapKeyHex = bytesToHex(turn.getWrapKey().toByteArray());
        }
        if (turn.getWrapKeyDelivery() != WingsvProto.WrapKeyDelivery.WRAP_KEY_DELIVERY_UNSPECIFIED) {
            importedConfig.vkTurnWrapSendKey =
                turn.getWrapKeyDelivery() == WingsvProto.WrapKeyDelivery.WRAP_KEY_DELIVERY_IN_BAND;
        }
    }

    private static String wrapModeToPref(WingsvProto.WrapMode mode) {
        switch (mode) {
            case WRAP_MODE_OFF:
                return "off";
            case WRAP_MODE_REQUIRED:
                return "required";
            case WRAP_MODE_PREFERRED:
            default:
                return "preferred";
        }
    }

    private static String wrapCipherToPref(WingsvProto.WrapCipher cipher) {
        if (cipher == WingsvProto.WrapCipher.WRAP_CIPHER_SRTP_CHACHA20_POLY1305) {
            return "srtp-chacha20-poly1305";
        }
        return "srtp-aes-gcm";
    }

    private static WingsvProto.WrapMode wrapModeFromPref(String value) {
        if (value == null) {
            return WingsvProto.WrapMode.WRAP_MODE_UNSPECIFIED;
        }
        switch (value) {
            case "off":
                return WingsvProto.WrapMode.WRAP_MODE_OFF;
            case "required":
                return WingsvProto.WrapMode.WRAP_MODE_REQUIRED;
            case "preferred":
                return WingsvProto.WrapMode.WRAP_MODE_PREFERRED;
            default:
                return WingsvProto.WrapMode.WRAP_MODE_UNSPECIFIED;
        }
    }

    private static WingsvProto.WrapCipher wrapCipherFromPref(String value) {
        if ("srtp-chacha20-poly1305".equals(value)) {
            return WingsvProto.WrapCipher.WRAP_CIPHER_SRTP_CHACHA20_POLY1305;
        }
        return WingsvProto.WrapCipher.WRAP_CIPHER_SRTP_AES_256_GCM;
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        String clean = hex.trim();
        if ((clean.length() & 1) != 0) {
            return new byte[0];
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return new byte[0];
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static void parseWireGuard(WingsvProto.WireGuard wg, ImportedConfig importedConfig) throws Exception {
        importedConfig.hasWireGuardSettings = true;
        if (!TextUtils.isEmpty(value(wg.getTitle()))) {
            importedConfig.importedWireGuardTitle = value(wg.getTitle());
        }
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
        if (matchType == WingsvProto.XrayRoutingMatchType.XRAY_ROUTING_MATCH_DOMAIN) {
            return XrayRoutingRule.MatchType.DOMAIN;
        }
        if (matchType == WingsvProto.XrayRoutingMatchType.XRAY_ROUTING_MATCH_IP) {
            return XrayRoutingRule.MatchType.IP;
        }
        if (matchType == WingsvProto.XrayRoutingMatchType.XRAY_ROUTING_MATCH_PORT) {
            return XrayRoutingRule.MatchType.PORT;
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

        AppRoutingMode mode = null;
        if (appRouting.getMode() != WingsvProto.AppRoutingMode.APP_ROUTING_MODE_UNSPECIFIED) {
            mode = fromProtoAppRoutingMode(appRouting.getMode());
        } else if (appRouting.hasBypass()) {
            mode = appRouting.getBypass() ? AppRoutingMode.XBYPASS : AppRoutingMode.WHITELIST;
        }
        importedConfig.appRoutingMode = mode;

        List<String> bypassPackages = new ArrayList<>();
        for (String packageName : appRouting.getBypassPackagesList()) {
            if (!TextUtils.isEmpty(value(packageName))) {
                bypassPackages.add(value(packageName));
            }
        }
        List<String> whitelistPackages = new ArrayList<>();
        for (String packageName : appRouting.getWhitelistPackagesList()) {
            if (!TextUtils.isEmpty(value(packageName))) {
                whitelistPackages.add(value(packageName));
            }
        }

        boolean hasNewLists = !bypassPackages.isEmpty() || !whitelistPackages.isEmpty();
        if (hasNewLists) {
            importedConfig.appRoutingBypassPackages = bypassPackages;
            importedConfig.appRoutingWhitelistPackages = whitelistPackages;
            return;
        }

        // Legacy single-list payload: route packages into the inferred mode's bucket.
        List<String> legacyPackages = new ArrayList<>();
        for (String packageName : appRouting.getPackagesList()) {
            if (!TextUtils.isEmpty(value(packageName))) {
                legacyPackages.add(value(packageName));
            }
        }
        if (legacyPackages.isEmpty()) {
            return;
        }
        if (mode.isWhitelistFamily()) {
            importedConfig.appRoutingWhitelistPackages = legacyPackages;
        } else {
            importedConfig.appRoutingBypassPackages = legacyPackages;
        }
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
            throw new IllegalArgumentException(
                WingsApplication.getStringSafe(R.string.import_parser_payload_unpack_failed)
            );
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

    // ---- Per-backend profile libraries (WG / AWG / VK TURN), mirroring Xray. The
    // full-settings export attaches the whole library so the panel sees every
    // profile the device knows about; the active profile is also projected onto
    // the flat fields of the same message for legacy clients. ----

    private static WingsvProto.WireGuardProfile toProtoWgProfile(WireGuardProfile profile) throws Exception {
        WingsvProto.WireGuard wgConfig = buildWireGuard(wireGuardProfileToSettings(profile), false);
        WingsvProto.WireGuardProfile.Builder builder = WingsvProto.WireGuardProfile.newBuilder();
        builder.setId(value(profile.id));
        if (!TextUtils.isEmpty(value(profile.title))) {
            builder.setTitle(value(profile.title));
        }
        if (wgConfig.hasIface()) {
            builder.setIface(wgConfig.getIface());
        }
        if (wgConfig.hasPeer()) {
            builder.setPeer(wgConfig.getPeer());
        }
        if (wgConfig.hasEndpoint()) {
            builder.setEndpoint(wgConfig.getEndpoint());
        }
        if (!TextUtils.isEmpty(value(profile.subscriptionId))) {
            builder.setSubscriptionId(value(profile.subscriptionId));
        }
        if (!TextUtils.isEmpty(value(profile.subscriptionTitle))) {
            builder.setSubscriptionTitle(value(profile.subscriptionTitle));
        }
        return builder.build();
    }

    private static WingsvProto.AmneziaProfile toProtoAwgProfile(AmneziaProfile profile) {
        WingsvProto.AmneziaProfile.Builder builder = WingsvProto.AmneziaProfile.newBuilder();
        builder.setId(value(profile.id));
        if (!TextUtils.isEmpty(value(profile.title))) {
            builder.setTitle(value(profile.title));
        }
        builder.setAwgQuickConfig(value(profile.quickConfig));
        if (!TextUtils.isEmpty(value(profile.subscriptionId))) {
            builder.setSubscriptionId(value(profile.subscriptionId));
        }
        if (!TextUtils.isEmpty(value(profile.subscriptionTitle))) {
            builder.setSubscriptionTitle(value(profile.subscriptionTitle));
        }
        return builder.build();
    }

    private static WingsvProto.TurnProfile toProtoTurnProfile(VkTurnProfile profile) throws Exception {
        WingsvProto.TurnProfile.Builder builder = WingsvProto.TurnProfile.newBuilder();
        builder.setId(value(profile.id));
        if (!TextUtils.isEmpty(value(profile.title))) {
            builder.setTitle(value(profile.title));
        }
        if (!TextUtils.isEmpty(value(profile.transportKind))) {
            builder.setTransportKind(value(profile.transportKind));
        }
        if (!TextUtils.isEmpty(value(profile.transportProfileId))) {
            builder.setTransportProfileId(value(profile.transportProfileId));
        }
        if (!TextUtils.isEmpty(value(profile.vkTurnEndpoint))) {
            builder.setVkTurnEndpoint(value(profile.vkTurnEndpoint));
        }
        // The inner Turn captures the per-profile proxy settings; it is never
        // populated with its own profiles. The referenced transport (WG/AWG) is
        // carried in the same Config's wg.profiles / awg.profiles.
        builder.setConfig(buildTurn(vkTurnProfileToSettings(profile), true));
        if (!TextUtils.isEmpty(value(profile.subscriptionId))) {
            builder.setSubscriptionId(value(profile.subscriptionId));
        }
        if (!TextUtils.isEmpty(value(profile.subscriptionTitle))) {
            builder.setSubscriptionTitle(value(profile.subscriptionTitle));
        }
        return builder.build();
    }

    private static WingsvProto.WireGuard buildWireGuardWithProfiles(
        Context context,
        ProxySettings settings,
        boolean includeDefaults
    ) throws Exception {
        WingsvProto.WireGuard.Builder builder = buildWireGuard(settings, includeDefaults).toBuilder();
        if (context == null) {
            return builder.build();
        }
        LinkedHashMap<String, WireGuardProfile> profiles = new LinkedHashMap<>();
        for (WireGuardProfile profile : WireGuardProfileStore.getProfiles(context)) {
            if (profile != null && !profile.isEmpty()) {
                profiles.put(profile.stableDedupKey(), profile);
            }
        }
        String activeId = value(WireGuardProfileStore.getActiveProfileId(context));
        for (WireGuardProfile profile : profiles.values()) {
            if (!TextUtils.isEmpty(activeId) && activeId.equals(value(profile.id))) {
                builder.setActiveProfileId(activeId);
            }
            builder.addProfiles(toProtoWgProfile(profile));
        }
        return builder.build();
    }

    private static WingsvProto.AmneziaWG buildAmneziaWithProfiles(
        Context context,
        ProxySettings settings,
        boolean includeDefaults
    ) {
        WingsvProto.AmneziaWG.Builder builder = buildAmnezia(settings, includeDefaults).toBuilder();
        if (context == null) {
            return builder.build();
        }
        LinkedHashMap<String, AmneziaProfile> profiles = new LinkedHashMap<>();
        for (AmneziaProfile profile : AmneziaProfileStore.getProfiles(context)) {
            if (profile != null && !profile.isEmpty()) {
                profiles.put(profile.stableDedupKey(), profile);
            }
        }
        String activeId = value(AmneziaProfileStore.getActiveProfileId(context));
        for (AmneziaProfile profile : profiles.values()) {
            if (!TextUtils.isEmpty(activeId) && activeId.equals(value(profile.id))) {
                builder.setActiveProfileId(activeId);
            }
            builder.addProfiles(toProtoAwgProfile(profile));
        }
        return builder.build();
    }

    private static WingsvProto.Turn buildTurnWithProfiles(
        Context context,
        ProxySettings settings,
        boolean includeDefaults
    ) throws Exception {
        WingsvProto.Turn.Builder builder = buildTurn(settings, includeDefaults).toBuilder();
        if (context == null) {
            return builder.build();
        }
        LinkedHashMap<String, VkTurnProfile> profiles = new LinkedHashMap<>();
        for (VkTurnProfile profile : VkTurnProfileStore.getProfiles(context)) {
            if (profile != null && !TextUtils.isEmpty(value(profile.vkTurnEndpoint))) {
                profiles.put(profile.stableDedupKey(), profile);
            }
        }
        String activeId = value(VkTurnProfileStore.getActiveProfileId(context));
        for (VkTurnProfile profile : profiles.values()) {
            if (!TextUtils.isEmpty(activeId) && activeId.equals(value(profile.id))) {
                builder.setActiveProfileId(activeId);
            }
            builder.addProfiles(toProtoTurnProfile(profile));
        }
        return builder.build();
    }

    private static void parseBackendProfileLists(WingsvProto.Config config, ImportedConfig importedConfig)
        throws Exception {
        if (config.hasWg() && config.getWg().getProfilesCount() > 0) {
            importedConfig.hasWgProfiles = true;
            importedConfig.activeWgProfileId = value(config.getWg().getActiveProfileId());
            for (WingsvProto.WireGuardProfile profile : config.getWg().getProfilesList()) {
                importedConfig.wgProfiles.add(fromProtoWgProfile(profile));
            }
        }
        if (config.hasAwg() && config.getAwg().getProfilesCount() > 0) {
            importedConfig.hasAwgProfiles = true;
            importedConfig.activeAwgProfileId = value(config.getAwg().getActiveProfileId());
            for (WingsvProto.AmneziaProfile profile : config.getAwg().getProfilesList()) {
                importedConfig.awgProfiles.add(fromProtoAwgProfile(profile));
            }
        }
        if (config.hasTurn() && config.getTurn().getProfilesCount() > 0) {
            importedConfig.hasTurnProfiles = true;
            importedConfig.activeTurnProfileId = value(config.getTurn().getActiveProfileId());
            for (WingsvProto.TurnProfile profile : config.getTurn().getProfilesList()) {
                importedConfig.turnProfiles.add(fromProtoTurnProfile(profile));
            }
        }
    }

    private static WireGuardProfile fromProtoWgProfile(WingsvProto.WireGuardProfile profile) throws Exception {
        // Reuse parseWireGuard's iface/peer/endpoint extraction by wrapping the
        // profile's sub-messages into a throwaway WireGuard message.
        WingsvProto.WireGuard.Builder wg = WingsvProto.WireGuard.newBuilder();
        if (profile.hasIface()) {
            wg.setIface(profile.getIface());
        }
        if (profile.hasPeer()) {
            wg.setPeer(profile.getPeer());
        }
        if (profile.hasEndpoint()) {
            wg.setEndpoint(profile.getEndpoint());
        }
        ImportedConfig temp = new ImportedConfig();
        parseWireGuard(wg.build(), temp);
        return new WireGuardProfile(
            value(profile.getId()),
            value(profile.getTitle()),
            value(temp.wgPrivateKey),
            value(temp.wgAddresses),
            value(temp.wgDns),
            temp.wgMtu != null ? temp.wgMtu : 0,
            value(temp.wgPublicKey),
            value(temp.wgPresharedKey),
            value(temp.wgAllowedIps),
            value(temp.wgEndpoint),
            value(profile.getSubscriptionId()),
            value(profile.getSubscriptionTitle())
        );
    }

    private static AmneziaProfile fromProtoAwgProfile(WingsvProto.AmneziaProfile profile) {
        return new AmneziaProfile(
            value(profile.getId()),
            value(profile.getTitle()),
            value(profile.getAwgQuickConfig()),
            value(profile.getSubscriptionId()),
            value(profile.getSubscriptionTitle())
        );
    }

    private static VkTurnProfile fromProtoTurnProfile(WingsvProto.TurnProfile profile) {
        // Reuse parseTurn to decode the embedded proxy settings, then layer the
        // VK identity (transport reference + endpoint) on top. vkAuthMode and
        // dnsMode are not carried by the Turn proto, so they default to empty.
        ImportedConfig temp = new ImportedConfig();
        parseTurn(profile.getConfig(), temp);
        String endpoint = !TextUtils.isEmpty(value(profile.getVkTurnEndpoint()))
            ? value(profile.getVkTurnEndpoint())
            : value(temp.endpoint);
        return new VkTurnProfile(
            value(profile.getId()),
            value(profile.getTitle()),
            value(profile.getTransportKind()),
            value(profile.getTransportProfileId()),
            endpoint,
            temp.threads != null ? temp.threads : 0,
            temp.credsGroupSize != null ? temp.credsGroupSize : 0,
            temp.useUdp != null && temp.useUdp,
            temp.noObfuscation != null && temp.noObfuscation,
            temp.manualCaptcha != null && temp.manualCaptcha,
            value(temp.captchaAutoSolver),
            "",
            value(temp.turnSessionMode),
            "",
            value(temp.vkTurnUserDns),
            temp.vkTurnRuntimeMode != null ? temp.vkTurnRuntimeMode.prefValue : "",
            temp.vkTurnRestartOnNetworkChange != null && temp.vkTurnRestartOnNetworkChange,
            value(temp.vkTurnWrapMode),
            value(temp.vkTurnWrapCipher),
            value(temp.vkTurnWrapKeyHex),
            temp.vkTurnWrapSendKey != null && temp.vkTurnWrapSendKey,
            value(temp.localEndpoint),
            value(temp.turnHost),
            value(temp.turnPort),
            value(profile.getSubscriptionId()),
            value(profile.getSubscriptionTitle())
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
        if (!TextUtils.isEmpty(value(settings.getLocalProxyListenAddress()))) {
            result.localProxyListenAddress = value(settings.getLocalProxyListenAddress());
        }
        if (settings.hasHttpProxyEnabled()) {
            result.httpProxyEnabled = settings.getHttpProxyEnabled();
        }
        if (settings.hasHttpProxyAuthEnabled()) {
            result.httpProxyAuthEnabled = settings.getHttpProxyAuthEnabled();
        }
        if (!TextUtils.isEmpty(value(settings.getHttpProxyUsername()))) {
            result.httpProxyUsername = value(settings.getHttpProxyUsername());
        }
        if (!TextUtils.isEmpty(value(settings.getHttpProxyPassword()))) {
            result.httpProxyPassword = value(settings.getHttpProxyPassword());
        }
        if (settings.hasHttpProxyPort()) {
            result.httpProxyPort = settings.getHttpProxyPort();
        }
        if (!TextUtils.isEmpty(value(settings.getHttpProxyListenAddress()))) {
            result.httpProxyListenAddress = value(settings.getHttpProxyListenAddress());
        }
        if (settings.hasRestartOnNetworkChange()) {
            result.restartOnNetworkChange = settings.getRestartOnNetworkChange();
        }
        if (settings.getTransportMode() != WingsvProto.XrayTransportMode.XRAY_TRANSPORT_MODE_UNSPECIFIED) {
            result.transportMode = fromProtoXrayTransportMode(settings.getTransportMode());
        }
        if (settings.getRuntimeMode() != WingsvProto.ProxyRuntimeMode.PROXY_RUNTIME_MODE_UNSPECIFIED) {
            result.runtimeMode = fromProtoRuntimeMode(settings.getRuntimeMode());
        }
        if (settings.getWakeProbeMode() == WingsvProto.WakeProbeMode.WAKE_PROBE_MODE_HTTP_PROBE) {
            result.wakeProbeMode = XraySettings.WakeProbeMode.HTTP_PROBE;
        } else if (settings.getWakeProbeMode() == WingsvProto.WakeProbeMode.WAKE_PROBE_MODE_PROCESS) {
            result.wakeProbeMode = XraySettings.WakeProbeMode.PROCESS;
        }
        return result;
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
        if (includeDefaults || !TextUtils.isEmpty(value(settings.localProxyListenAddress))) {
            builder.setLocalProxyListenAddress(value(settings.localProxyListenAddress));
        }
        if (includeDefaults || settings.httpProxyEnabled) {
            builder.setHttpProxyEnabled(settings.httpProxyEnabled);
        }
        if (includeDefaults || !settings.httpProxyAuthEnabled) {
            builder.setHttpProxyAuthEnabled(settings.httpProxyAuthEnabled);
        }
        if (includeDefaults || !TextUtils.isEmpty(value(settings.httpProxyUsername))) {
            builder.setHttpProxyUsername(value(settings.httpProxyUsername));
        }
        if (includeDefaults || !TextUtils.isEmpty(value(settings.httpProxyPassword))) {
            builder.setHttpProxyPassword(value(settings.httpProxyPassword));
        }
        if (settings.httpProxyPort > 0) {
            builder.setHttpProxyPort(settings.httpProxyPort);
        }
        if (includeDefaults || !TextUtils.isEmpty(value(settings.httpProxyListenAddress))) {
            builder.setHttpProxyListenAddress(value(settings.httpProxyListenAddress));
        }
        if (includeDefaults || settings.restartOnNetworkChange) {
            builder.setRestartOnNetworkChange(settings.restartOnNetworkChange);
        }
        if (includeDefaults || settings.transportMode != XrayTransportMode.DIRECT) {
            builder.setTransportMode(toProtoXrayTransportMode(settings.transportMode));
        }
        if (settings.runtimeMode != null && (includeDefaults || settings.runtimeMode != ProxyRuntimeMode.VPN)) {
            builder.setRuntimeMode(toProtoRuntimeMode(settings.runtimeMode));
        }
        if (
            includeDefaults ||
            (settings.wakeProbeMode != null && !XraySettings.WakeProbeMode.PROCESS.equals(settings.wakeProbeMode))
        ) {
            builder.setWakeProbeMode(
                XraySettings.WakeProbeMode.HTTP_PROBE.equals(settings.wakeProbeMode)
                    ? WingsvProto.WakeProbeMode.WAKE_PROBE_MODE_HTTP_PROBE
                    : WingsvProto.WakeProbeMode.WAKE_PROBE_MODE_PROCESS
            );
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
        settings.localProxyListenAddress = "127.0.0.1";
        settings.httpProxyEnabled = false;
        settings.httpProxyAuthEnabled = true;
        settings.httpProxyPort = 10809;
        settings.httpProxyListenAddress = "127.0.0.1";
        settings.remoteDns = "https://common.dot.dns.yandex.net/dns-query";
        settings.directDns = "https://common.dot.dns.yandex.net/dns-query";
        settings.ipv6 = true;
        settings.sniffingEnabled = true;
        settings.proxyQuicEnabled = false;
        settings.restartOnNetworkChange = false;
        settings.transportMode = XrayTransportMode.DIRECT;
        settings.wakeProbeMode = XraySettings.WakeProbeMode.PROCESS;
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
        if ("mux".equals(normalized) || "mu".equals(normalized)) {
            return WingsvProto.TurnSessionMode.TURN_SESSION_MODE_MUX;
        }
        return WingsvProto.TurnSessionMode.TURN_SESSION_MODE_AUTO;
    }

    private static String fromProtoSessionMode(WingsvProto.TurnSessionMode value) {
        if (value == WingsvProto.TurnSessionMode.TURN_SESSION_MODE_MAINLINE) {
            return "mainline";
        }
        if (value == WingsvProto.TurnSessionMode.TURN_SESSION_MODE_MUX) {
            return "mu";
        }
        return DEFAULT_SESSION_MODE;
    }

    private static WingsvProto.XrayTransportMode toProtoXrayTransportMode(XrayTransportMode transportMode) {
        if (transportMode == XrayTransportMode.VK_TURN_TCP) {
            return WingsvProto.XrayTransportMode.XRAY_TRANSPORT_MODE_VK_TURN_TCP;
        }
        return WingsvProto.XrayTransportMode.XRAY_TRANSPORT_MODE_DIRECT;
    }

    private static XrayTransportMode fromProtoXrayTransportMode(WingsvProto.XrayTransportMode transportMode) {
        if (transportMode == WingsvProto.XrayTransportMode.XRAY_TRANSPORT_MODE_VK_TURN_TCP) {
            return XrayTransportMode.VK_TURN_TCP;
        }
        return XrayTransportMode.DIRECT;
    }

    /**
     * One backend profile decoded from a subscription wingsv:// link. For
     * WIREGUARD / AMNEZIAWG it carries the standalone transport profile. For
     * VK_TURN it carries the VkTurnProfile plus its embedded transport (exactly
     * one of wireGuardProfile / amneziaProfile is non-null); the VkTurnProfile's
     * transportProfileId is empty and must be resolved by the caller after the
     * transport has been synced into its own store.
     */
    public static final class ImportedBackendProfile {

        public enum Kind {
            VK_TURN,
            WIREGUARD,
            AMNEZIAWG,
        }

        public final Kind kind;
        public final VkTurnProfile vkTurnProfile;
        public final WireGuardProfile wireGuardProfile;
        public final AmneziaProfile amneziaProfile;

        private ImportedBackendProfile(
            Kind kind,
            VkTurnProfile vkTurnProfile,
            WireGuardProfile wireGuardProfile,
            AmneziaProfile amneziaProfile
        ) {
            this.kind = kind;
            this.vkTurnProfile = vkTurnProfile;
            this.wireGuardProfile = wireGuardProfile;
            this.amneziaProfile = amneziaProfile;
        }

        static ImportedBackendProfile vkTurn(
            VkTurnProfile vkTurnProfile,
            WireGuardProfile wireGuardTransport,
            AmneziaProfile amneziaTransport
        ) {
            return new ImportedBackendProfile(Kind.VK_TURN, vkTurnProfile, wireGuardTransport, amneziaTransport);
        }

        static ImportedBackendProfile wireGuard(WireGuardProfile wireGuardProfile) {
            return new ImportedBackendProfile(Kind.WIREGUARD, null, wireGuardProfile, null);
        }

        static ImportedBackendProfile amnezia(AmneziaProfile amneziaProfile) {
            return new ImportedBackendProfile(Kind.AMNEZIAWG, null, null, amneziaProfile);
        }

        public boolean usesAmneziaTransport() {
            return kind == Kind.VK_TURN && amneziaProfile != null;
        }
    }

    public static final class ImportedConfig {

        public BackendType backendType = BackendType.VK_TURN_WIREGUARD;
        public boolean updateBackendType = true;
        public boolean hasAllSettings;
        public boolean hasTurnSettings;
        public boolean hasWireGuardSettings;
        public boolean hasAmneziaSettings;
        public boolean hasXraySettings;
        public boolean hasXrayProfiles;
        public boolean hasXraySubscriptionJson;
        public boolean hasXrayRouting;
        public boolean hasAppRouting;
        public boolean hasWbStreamSettings;
        public String wbStreamRoomId;
        public String wbStreamDisplayName;
        public boolean wbStreamExchangeViaVkTurn;
        public boolean wbStreamE2eEnabled;
        public String wbStreamE2eSecret;
        public Integer wbStreamRoomCount;
        public wings.v.core.TunnelMode wbStreamTunnelMode;
        public wings.v.core.TunnelMode vkTurnTunnelMode;
        public String endpoint;
        public String link;
        public java.util.List<String> links = new java.util.ArrayList<>();
        public String linkSecondary;
        public Integer credsGroupSize;
        public Integer threads;
        public Boolean useUdp;
        public Boolean noObfuscation;
        public Boolean manualCaptcha;
        public String captchaAutoSolver;
        public Boolean vkTurnRestartOnNetworkChange;
        public ProxyRuntimeMode vkTurnRuntimeMode;
        public String vkTurnUserDns;
        public String vkTurnWrapMode;
        public String vkTurnWrapCipher;
        public String vkTurnWrapKeyHex;
        public Boolean vkTurnWrapSendKey;
        public ProxyRuntimeMode xrayRuntimeMode;
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
        // Per-backend profile libraries carried by a full config (panel desired_config).
        // When present the importer REPLACES the matching store's profile list, mirroring
        // how applyImportedXraySettings replaces the Xray profile list.
        public boolean hasWgProfiles;
        public final List<WireGuardProfile> wgProfiles = new ArrayList<>();
        public String activeWgProfileId;
        public boolean hasAwgProfiles;
        public final List<AmneziaProfile> awgProfiles = new ArrayList<>();
        public String activeAwgProfileId;
        public boolean hasTurnProfiles;
        public final List<VkTurnProfile> turnProfiles = new ArrayList<>();
        public String activeTurnProfileId;
        // Profile titles carried by single-profile share links (empty otherwise).
        // applyImportedConfig prefers these over a synthesized title when adding
        // the imported config as a backend profile.
        public String importedWireGuardTitle;
        public String importedAmneziaTitle;
        public String importedVkTurnTitle;
        public String activeXrayProfileId;
        public final List<XrayProfile> xrayProfiles = new ArrayList<>();
        public final List<XraySubscription> xraySubscriptions = new ArrayList<>();
        public XraySettings xraySettings = defaultXraySettings();
        public String xraySubscriptionJson;
        public boolean xrayMergeOnly;
        public final List<XrayRoutingRule> xrayRoutingRules = new ArrayList<>();
        public String xrayRoutingGeoipUrl;
        public String xrayRoutingGeositeUrl;
        public AppRoutingMode appRoutingMode;
        public List<String> appRoutingBypassPackages;
        public List<String> appRoutingWhitelistPackages;
        public boolean hasXposedSettings;
        public Boolean xposedEnabled;
        public Boolean xposedAllApps;
        public Boolean xposedNativeHookEnabled;
        public Boolean xposedHideVpnApps;
        public Boolean xposedHideFromDumpsys;
        public String xposedProcfsHookMode;
        public String xposedIcmpSpoofingMode;
        public final List<String> xposedTargetPackages = new ArrayList<>();
        public final List<String> xposedHiddenVpnPackages = new ArrayList<>();
        public boolean hasRootSettings;
        public Boolean rootModeEnabled;
        public Boolean kernelWireguardEnabled;
        public Boolean xrayTproxyModeEnabled;
        public String rootWireguardInterfaceName;
        public boolean hasAppPreferences;
        public String themeMode;
        public Boolean autoStartOnBoot;
        public String dnsMode;
        public boolean hasGuardian;
        public String guardianWsUrl;
        public String guardianClientId;
        public byte[] guardianClientToken;
        public String guardianClientName;
        public String guardianSyncMode;
        public int guardianPeriodicIntervalMinutes;
        public String guardianAdminUsername;
        public long guardianAdminId;
        public long guardianAdminAvatarVersion;
        public boolean hasSubscriptionHwid;
        public Boolean subscriptionHwidEnabled;
        public Boolean subscriptionHwidManualEnabled;
        public String subscriptionHwidValue;
        public String subscriptionHwidDeviceOs;
        public String subscriptionHwidVerOs;
        public String subscriptionHwidDeviceModel;
        public boolean hasSharingSettings;
        public Boolean sharingAutoStartOnBoot;
        public final List<String> sharingLastActiveTypes = new ArrayList<>();
        public String sharingUpstreamInterface;
        public String sharingFallbackUpstreamInterface;
        public String sharingMasqueradeMode;
        public Boolean sharingDisableIpv6;
        public Boolean sharingDhcpWorkaround;
        public String sharingWifiLockMode;
        public Boolean sharingRepeaterSafeMode;
        public Boolean sharingTempHotspotUseSystem;
        public String sharingIpMonitorMode;
        public boolean hasByeDpiSettings;
        public ByeDpiSettings byeDpiSettings;
    }
}
