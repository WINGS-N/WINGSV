package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import wings.v.qs.QuickSettingsTiles;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.UseConcurrentHashMap",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.LooseCoupling",
    }
)
public final class XrayStore {

    private static final String RUNTIME_PREFS_NAME = "wingsv_xray_runtime";
    private static final int DEFAULT_LOCAL_PROXY_PORT = 10808;
    private static final String DEFAULT_SUBSCRIPTION_URL =
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt";
    private static final String DEFAULT_SUBSCRIPTION_TITLE = "Universal";
    private static final String DEFAULT_REMOTE_DNS = "https://common.dot.dns.yandex.net/dns-query";
    private static final String DEFAULT_DIRECT_DNS = "https://common.dot.dns.yandex.net/dns-query";

    private XrayStore() {}

    public static BackendType getBackendType(Context context) {
        return BackendType.fromPrefValue(
            prefs(context).getString(AppPrefs.KEY_BACKEND_TYPE, BackendType.VK_TURN_WIREGUARD.prefValue)
        );
    }

    public static void setBackendType(Context context, BackendType backendType) {
        Context appContext = context.getApplicationContext();
        prefs(appContext)
            .edit()
            .putString(
                AppPrefs.KEY_BACKEND_TYPE,
                backendType == null ? BackendType.VK_TURN_WIREGUARD.prefValue : backendType.prefValue
            )
            .commit();
        QuickSettingsTiles.requestRefresh(appContext);
    }

    public static XraySettings getXraySettings(Context context) {
        SharedPreferences prefs = prefs(context);
        BackendType backendType = getBackendType(context);
        SocksAuthCredentials.Pair credentials = SocksAuthCredentials.ensure(
            prefs,
            AppPrefs.KEY_XRAY_LOCAL_PROXY_USERNAME,
            AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD
        );
        XraySettings settings = new XraySettings();
        settings.allowLan = prefs.getBoolean(AppPrefs.KEY_XRAY_ALLOW_LAN, false);
        settings.allowInsecure = prefs.getBoolean(AppPrefs.KEY_XRAY_ALLOW_INSECURE, false);
        settings.localProxyEnabled = prefs.getBoolean(AppPrefs.KEY_XRAY_LOCAL_PROXY_ENABLED, false);
        settings.localProxyAuthEnabled = prefs.getBoolean(AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED, true);
        settings.localProxyUsername = credentials.username;
        settings.localProxyPassword = credentials.password;
        settings.localProxyPort = parseInt(
            prefs.getString(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT, String.valueOf(DEFAULT_LOCAL_PROXY_PORT)),
            DEFAULT_LOCAL_PROXY_PORT
        );
        settings.remoteDns = trim(prefs.getString(AppPrefs.KEY_XRAY_REMOTE_DNS, DEFAULT_REMOTE_DNS));
        settings.directDns = trim(prefs.getString(AppPrefs.KEY_XRAY_DIRECT_DNS, DEFAULT_DIRECT_DNS));
        settings.ipv6 = prefs.getBoolean(AppPrefs.KEY_XRAY_IPV6_ENABLED, true);
        settings.sniffingEnabled = prefs.getBoolean(AppPrefs.KEY_XRAY_SNIFFING_ENABLED, true);
        settings.proxyQuicEnabled = prefs.getBoolean(AppPrefs.KEY_XRAY_PROXY_QUIC_ENABLED, false);
        settings.restartOnNetworkChange = prefs.getBoolean(AppPrefs.KEY_XRAY_RESTART_ON_NETWORK_CHANGE, false);
        settings.runtimeMode = ProxyRuntimeMode.fromPrefValue(
            prefs.getString(AppPrefs.KEY_XRAY_RUNTIME_MODE, ProxyRuntimeMode.VPN.prefValue)
        );
        settings.transportMode = XrayTransportMode.fromPrefValue(
            prefs.getString(AppPrefs.KEY_XRAY_TRANSPORT_MODE, XrayTransportMode.DIRECT.prefValue)
        );
        return settings;
    }

    public static void setXraySettings(Context context, XraySettings settings) {
        XraySettings value = settings != null ? settings : new XraySettings();
        prefs(context)
            .edit()
            .putBoolean(AppPrefs.KEY_XRAY_ALLOW_LAN, value.allowLan)
            .putBoolean(AppPrefs.KEY_XRAY_ALLOW_INSECURE, value.allowInsecure)
            .putBoolean(AppPrefs.KEY_XRAY_LOCAL_PROXY_ENABLED, value.localProxyEnabled)
            .putBoolean(AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED, value.localProxyAuthEnabled)
            .putString(AppPrefs.KEY_XRAY_LOCAL_PROXY_USERNAME, trim(value.localProxyUsername))
            .putString(AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD, trim(value.localProxyPassword))
            .putString(
                AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT,
                String.valueOf(value.localProxyPort > 0 ? value.localProxyPort : DEFAULT_LOCAL_PROXY_PORT)
            )
            .putString(
                AppPrefs.KEY_XRAY_REMOTE_DNS,
                TextUtils.isEmpty(trim(value.remoteDns)) ? DEFAULT_REMOTE_DNS : trim(value.remoteDns)
            )
            .putString(
                AppPrefs.KEY_XRAY_DIRECT_DNS,
                TextUtils.isEmpty(trim(value.directDns)) ? DEFAULT_DIRECT_DNS : trim(value.directDns)
            )
            .putBoolean(AppPrefs.KEY_XRAY_IPV6_ENABLED, value.ipv6)
            .putBoolean(AppPrefs.KEY_XRAY_SNIFFING_ENABLED, value.sniffingEnabled)
            .putBoolean(AppPrefs.KEY_XRAY_PROXY_QUIC_ENABLED, value.proxyQuicEnabled)
            .putBoolean(AppPrefs.KEY_XRAY_RESTART_ON_NETWORK_CHANGE, value.restartOnNetworkChange)
            .putString(
                AppPrefs.KEY_XRAY_RUNTIME_MODE,
                value.runtimeMode == null ? ProxyRuntimeMode.VPN.prefValue : value.runtimeMode.prefValue
            )
            .putString(
                AppPrefs.KEY_XRAY_TRANSPORT_MODE,
                value.transportMode == null ? XrayTransportMode.DIRECT.prefValue : value.transportMode.prefValue
            )
            .commit();
    }

    public static List<XraySubscription> getSubscriptions(Context context) {
        return getSubscriptions(context, true);
    }

    public static List<XraySubscription> getSubscriptions(Context context, boolean allowUniversalSeed) {
        SharedPreferences prefs = prefs(context);
        ArrayList<XraySubscription> result = new ArrayList<>();
        JSONArray array = parseArray(prefs.getString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_JSON, "[]"));
        if (array == null) {
            array = new JSONArray();
        }
        for (int index = 0; index < array.length(); index++) {
            XraySubscription subscription = XraySubscription.fromJson(array.optJSONObject(index));
            if (subscription != null && !TextUtils.isEmpty(subscription.url)) {
                result.add(subscription);
            }
        }
        if (allowUniversalSeed && !prefs.getBoolean(AppPrefs.KEY_XRAY_UNIVERSAL_SUBSCRIPTION_MIGRATED, false)) {
            if (!hasSubscriptionWithUrl(result, DEFAULT_SUBSCRIPTION_URL)) {
                result.add(
                    new XraySubscription(
                        null,
                        DEFAULT_SUBSCRIPTION_TITLE,
                        DEFAULT_SUBSCRIPTION_URL,
                        "auto",
                        getRefreshIntervalMinutes(context),
                        true,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L
                    )
                );
                setSubscriptions(context, result);
                prefs.edit().putBoolean(AppPrefs.KEY_XRAY_UNIVERSAL_SUBSCRIPTION_MIGRATED, true).apply();
            } else {
                prefs.edit().putBoolean(AppPrefs.KEY_XRAY_UNIVERSAL_SUBSCRIPTION_MIGRATED, true).apply();
            }
        }
        return result;
    }

    public static void setSubscriptions(Context context, List<XraySubscription> subscriptions) {
        JSONArray array = new JSONArray();
        if (subscriptions != null) {
            for (XraySubscription subscription : subscriptions) {
                if (subscription == null) {
                    continue;
                }
                try {
                    array.put(subscription.toJson());
                } catch (Exception ignored) {}
            }
        }
        prefs(context)
            .edit()
            .putString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_JSON, array.toString())
            .putBoolean(AppPrefs.KEY_XRAY_DEFAULT_SUBSCRIPTION_SEEDED, true)
            .putBoolean(AppPrefs.KEY_XRAY_UNIVERSAL_SUBSCRIPTION_MIGRATED, true)
            .commit();
        XraySubscriptionBackgroundScheduler.refresh(context.getApplicationContext());
    }

    public static void ensureDefaultSubscriptionPresent(Context context) {
        if (context == null) {
            return;
        }
        List<XraySubscription> subscriptions = getSubscriptions(context);
        if (hasSubscriptionWithUrl(subscriptions, DEFAULT_SUBSCRIPTION_URL)) {
            return;
        }
        ArrayList<XraySubscription> updated = new ArrayList<>(subscriptions);
        updated.add(
            new XraySubscription(
                null,
                DEFAULT_SUBSCRIPTION_TITLE,
                DEFAULT_SUBSCRIPTION_URL,
                "auto",
                getRefreshIntervalMinutes(context),
                true,
                0L,
                0L,
                0L,
                0L,
                0L
            )
        );
        setSubscriptions(context, updated);
    }

    public static List<XrayProfile> getProfiles(Context context) {
        ArrayList<XrayProfile> result = new ArrayList<>();
        JSONArray array = parseArray(prefs(context).getString(AppPrefs.KEY_XRAY_PROFILES_JSON, "[]"));
        if (array == null) {
            return result;
        }
        for (int index = 0; index < array.length(); index++) {
            XrayProfile profile = XrayProfile.fromJson(array.optJSONObject(index));
            if (profile != null && !TextUtils.isEmpty(profile.rawLink)) {
                result.add(profile);
            }
        }
        return result;
    }

    public static void setProfiles(Context context, List<XrayProfile> profiles) {
        JSONArray array = new JSONArray();
        Map<String, XrayProfile> deduped = new LinkedHashMap<>();
        if (profiles != null) {
            for (XrayProfile profile : profiles) {
                if (profile == null || TextUtils.isEmpty(profile.rawLink)) {
                    continue;
                }
                deduped.put(getProfileStorageKey(profile), profile);
            }
        }
        for (XrayProfile profile : deduped.values()) {
            try {
                array.put(profile.toJson());
            } catch (Exception ignored) {}
        }
        prefs(context).edit().putString(AppPrefs.KEY_XRAY_PROFILES_JSON, array.toString()).commit();
        pruneProfileTrafficStats(context, collectProfileIds(deduped.values()));
        pruneProfilePingResults(context, collectProfilePingKeys(deduped.values()));
    }

    public static boolean replaceProfile(Context context, XrayProfile profile) {
        if (profile == null || TextUtils.isEmpty(profile.id) || TextUtils.isEmpty(profile.rawLink)) {
            return false;
        }
        ArrayList<XrayProfile> profiles = new ArrayList<>(getProfiles(context));
        for (int index = 0; index < profiles.size(); index++) {
            XrayProfile candidate = profiles.get(index);
            if (candidate != null && TextUtils.equals(candidate.id, profile.id)) {
                profiles.set(index, profile);
                setProfiles(context, profiles);
                return true;
            }
        }
        return false;
    }

    public static String getProfilePingKey(XrayProfile profile) {
        return getProfileStorageKey(profile);
    }

    public static String getProfileStorageKey(XrayProfile profile) {
        if (profile == null) {
            return "";
        }
        String subscriptionId = TextUtils.isEmpty(profile.subscriptionId) ? "__manual__" : trim(profile.subscriptionId);
        return subscriptionId + "|" + profile.stableDedupKey();
    }

    public static Map<String, ProfileTrafficStats> getProfileTrafficStatsMap(Context context) {
        LinkedHashMap<String, ProfileTrafficStats> result = new LinkedHashMap<>();
        String rawJson = runtimePrefs(context).getString(AppPrefs.KEY_XRAY_PROFILE_TRAFFIC_JSON, null);
        if (TextUtils.isEmpty(rawJson)) {
            rawJson = prefs(context).getString(AppPrefs.KEY_XRAY_PROFILE_TRAFFIC_JSON, "{}");
        }
        JSONObject object = parseObject(rawJson);
        if (object == null) {
            return result;
        }
        JSONArray names = object.names();
        if (names == null) {
            return result;
        }
        for (int index = 0; index < names.length(); index++) {
            String profileId = trim(names.optString(index));
            if (TextUtils.isEmpty(profileId)) {
                continue;
            }
            JSONObject entry = object.optJSONObject(profileId);
            if (entry == null) {
                continue;
            }
            result.put(
                profileId,
                new ProfileTrafficStats(Math.max(0L, entry.optLong("rx", 0L)), Math.max(0L, entry.optLong("tx", 0L)))
            );
        }
        return result;
    }

    public static ProfileTrafficStats getProfileTrafficStats(Context context, String profileId) {
        if (TextUtils.isEmpty(trim(profileId))) {
            return ProfileTrafficStats.ZERO;
        }
        ProfileTrafficStats stats = getProfileTrafficStatsMap(context).get(trim(profileId));
        return stats != null ? stats : ProfileTrafficStats.ZERO;
    }

    public static void addProfileTrafficDelta(Context context, String profileId, long rxDelta, long txDelta) {
        String normalizedProfileId = trim(profileId);
        long safeRxDelta = Math.max(0L, rxDelta);
        long safeTxDelta = Math.max(0L, txDelta);
        if (TextUtils.isEmpty(normalizedProfileId) || (safeRxDelta == 0L && safeTxDelta == 0L)) {
            return;
        }
        Map<String, ProfileTrafficStats> current = getProfileTrafficStatsMap(context);
        ProfileTrafficStats previous = current.get(normalizedProfileId);
        long nextRx = safeRxDelta + (previous != null ? previous.rxBytes : 0L);
        long nextTx = safeTxDelta + (previous != null ? previous.txBytes : 0L);
        current.put(normalizedProfileId, new ProfileTrafficStats(nextRx, nextTx));
        writeProfileTrafficStats(context, current);
    }

    public static void resetProfileTrafficStats(Context context, Collection<String> profileIds) {
        if (profileIds == null || profileIds.isEmpty()) {
            return;
        }
        Map<String, ProfileTrafficStats> current = getProfileTrafficStatsMap(context);
        boolean changed = false;
        for (String profileId : profileIds) {
            String normalizedProfileId = trim(profileId);
            if (TextUtils.isEmpty(normalizedProfileId)) {
                continue;
            }
            changed |= current.remove(normalizedProfileId) != null;
        }
        if (changed) {
            writeProfileTrafficStats(context, current);
        }
    }

    public static Map<String, ProfilePingResult> getProfilePingResultsMap(Context context) {
        LinkedHashMap<String, ProfilePingResult> result = new LinkedHashMap<>();
        String rawJson = runtimePrefs(context).getString(AppPrefs.KEY_XRAY_PROFILE_TCPING_JSON, null);
        if (TextUtils.isEmpty(rawJson)) {
            rawJson = prefs(context).getString(AppPrefs.KEY_XRAY_PROFILE_TCPING_JSON, "{}");
        }
        JSONObject object = parseObject(rawJson);
        if (object == null) {
            return result;
        }
        JSONArray names = object.names();
        if (names == null) {
            return result;
        }
        for (int index = 0; index < names.length(); index++) {
            String profilePingKey = trim(names.optString(index));
            if (TextUtils.isEmpty(profilePingKey)) {
                continue;
            }
            JSONObject entry = object.optJSONObject(profilePingKey);
            if (entry == null) {
                continue;
            }
            result.put(
                profilePingKey,
                new ProfilePingResult(entry.optBoolean("success", false), Math.max(0, entry.optInt("latency_ms", 0)))
            );
        }
        return result;
    }

    public static void putProfilePingResult(Context context, String profilePingKey, boolean success, int latencyMs) {
        String normalizedKey = trim(profilePingKey);
        if (TextUtils.isEmpty(normalizedKey)) {
            return;
        }
        Map<String, ProfilePingResult> current = getProfilePingResultsMap(context);
        current.put(normalizedKey, new ProfilePingResult(success, Math.max(0, latencyMs)));
        writeProfilePingResults(context, current);
    }

    public static void removeProfilePingResults(Context context, Collection<String> profilePingKeys) {
        if (profilePingKeys == null || profilePingKeys.isEmpty()) {
            return;
        }
        Map<String, ProfilePingResult> current = getProfilePingResultsMap(context);
        boolean changed = false;
        for (String profilePingKey : profilePingKeys) {
            String normalizedKey = trim(profilePingKey);
            if (TextUtils.isEmpty(normalizedKey)) {
                continue;
            }
            changed |= current.remove(normalizedKey) != null;
        }
        if (changed) {
            writeProfilePingResults(context, current);
        }
    }

    public static String getActiveProfileId(Context context) {
        return trim(prefs(context).getString(AppPrefs.KEY_XRAY_ACTIVE_PROFILE_ID, ""));
    }

    public static void setActiveProfileId(Context context, String profileId) {
        prefs(context).edit().putString(AppPrefs.KEY_XRAY_ACTIVE_PROFILE_ID, trim(profileId)).commit();
    }

    public static XrayProfile getActiveProfile(Context context) {
        List<XrayProfile> profiles = getProfiles(context);
        if (profiles.isEmpty()) {
            return null;
        }
        String activeProfileId = getActiveProfileId(context);
        for (XrayProfile profile : profiles) {
            if (TextUtils.equals(profile.id, activeProfileId)) {
                return profile;
            }
        }
        XrayProfile firstProfile = profiles.get(0);
        setActiveProfileId(context, firstProfile.id);
        return firstProfile;
    }

    public static int getRefreshIntervalMinutes(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.contains(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_MINUTES)) {
            return normalizeRefreshIntervalMinutes(
                parseInt(preferences.getString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_MINUTES, "1440"), 1440)
            );
        }
        int legacyHours = parseInt(preferences.getString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_HOURS, "24"), 24);
        int migratedMinutes = normalizeRefreshIntervalMinutes(legacyHours * 60);
        preferences
            .edit()
            .putString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_MINUTES, String.valueOf(migratedMinutes))
            .apply();
        return migratedMinutes;
    }

    public static boolean isSubscriptionsAutoRefreshEnabled(Context context) {
        return prefs(context).getBoolean(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_AUTO_REFRESH_ENABLED, true);
    }

    public static void setSubscriptionsAutoRefreshEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_AUTO_REFRESH_ENABLED, enabled).apply();
        Context appContext = context.getApplicationContext();
        if (enabled) {
            XraySubscriptionBackgroundScheduler.refresh(appContext);
            return;
        }
        XraySubscriptionBackgroundScheduler.cancel(appContext);
    }

    public static void setRefreshIntervalMinutes(Context context, int minutes) {
        int normalizedMinutes = normalizeRefreshIntervalMinutes(minutes);
        List<XraySubscription> currentSubscriptions = getSubscriptions(context);
        ArrayList<XraySubscription> updatedSubscriptions = new ArrayList<>(currentSubscriptions.size());
        for (XraySubscription subscription : currentSubscriptions) {
            if (subscription == null) {
                continue;
            }
            updatedSubscriptions.add(
                new XraySubscription(
                    subscription.id,
                    subscription.title,
                    subscription.url,
                    subscription.formatHint,
                    normalizedMinutes,
                    subscription.autoUpdate,
                    subscription.lastUpdatedAt,
                    subscription.advertisedUploadBytes,
                    subscription.advertisedDownloadBytes,
                    subscription.advertisedTotalBytes,
                    subscription.advertisedExpireAt
                )
            );
        }
        SharedPreferences.Editor editor = prefs(context)
            .edit()
            .putString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_MINUTES, String.valueOf(normalizedMinutes));
        if (normalizedMinutes % 60 == 0) {
            editor.putString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_HOURS, String.valueOf(normalizedMinutes / 60));
        } else {
            editor.remove(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_HOURS);
        }
        editor.apply();
        if (!updatedSubscriptions.isEmpty()) {
            setSubscriptions(context, updatedSubscriptions);
            return;
        }
        XraySubscriptionBackgroundScheduler.refresh(context.getApplicationContext());
    }

    public static long getLastSubscriptionsRefreshAt(Context context) {
        return prefs(context).getLong(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_LAST_REFRESH_AT, 0L);
    }

    public static void setLastSubscriptionsRefreshAt(Context context, long refreshedAt) {
        prefs(context)
            .edit()
            .putLong(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_LAST_REFRESH_AT, Math.max(refreshedAt, 0L))
            .apply();
    }

    public static String getLastSubscriptionsError(Context context) {
        return trim(prefs(context).getString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_LAST_ERROR, ""));
    }

    public static void setLastSubscriptionsError(Context context, String error) {
        prefs(context).edit().putString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_LAST_ERROR, trim(error)).apply();
    }

    private static int normalizeRefreshIntervalMinutes(int minutes) {
        if (minutes <= 0) {
            return 1440;
        }
        return Math.min(minutes, 1440);
    }

    public static String getImportedSubscriptionJson(Context context) {
        return trim(prefs(context).getString(AppPrefs.KEY_XRAY_IMPORTED_SUBSCRIPTION_JSON, ""));
    }

    public static void setImportedSubscriptionJson(Context context, String rawJson) {
        prefs(context).edit().putString(AppPrefs.KEY_XRAY_IMPORTED_SUBSCRIPTION_JSON, trim(rawJson)).apply();
    }

    public static final class ProfileTrafficStats {

        public static final ProfileTrafficStats ZERO = new ProfileTrafficStats(0L, 0L);

        public final long rxBytes;
        public final long txBytes;

        public ProfileTrafficStats(long rxBytes, long txBytes) {
            this.rxBytes = Math.max(0L, rxBytes);
            this.txBytes = Math.max(0L, txBytes);
        }
    }

    public static final class ProfilePingResult {

        public final boolean success;
        public final int latencyMs;

        public ProfilePingResult(boolean success, int latencyMs) {
            this.success = success;
            this.latencyMs = Math.max(0, latencyMs);
        }
    }

    private static JSONArray parseArray(String rawValue) {
        try {
            return new JSONArray(TextUtils.isEmpty(rawValue) ? "[]" : rawValue);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject parseObject(String rawValue) {
        try {
            return new JSONObject(TextUtils.isEmpty(rawValue) ? "{}" : rawValue);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    private static SharedPreferences runtimePrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static int parseInt(String rawValue, int fallback) {
        try {
            return Integer.parseInt(trim(rawValue));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean hasSubscriptionWithUrl(List<XraySubscription> subscriptions, String url) {
        String expectedKey = TextUtils.isEmpty(url) ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (subscriptions != null) {
            for (XraySubscription subscription : subscriptions) {
                if (subscription == null || TextUtils.isEmpty(subscription.url)) {
                    continue;
                }
                if (TextUtils.equals(subscription.stableDedupKey(), expectedKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void writeProfileTrafficStats(
        Context context,
        Map<String, ProfileTrafficStats> profileTrafficStats
    ) {
        JSONObject object = new JSONObject();
        if (profileTrafficStats != null) {
            for (Map.Entry<String, ProfileTrafficStats> entry : profileTrafficStats.entrySet()) {
                String profileId = trim(entry.getKey());
                if (TextUtils.isEmpty(profileId) || entry.getValue() == null) {
                    continue;
                }
                try {
                    JSONObject item = new JSONObject();
                    item.put("rx", Math.max(0L, entry.getValue().rxBytes));
                    item.put("tx", Math.max(0L, entry.getValue().txBytes));
                    object.put(profileId, item);
                } catch (Exception ignored) {}
            }
        }
        runtimePrefs(context).edit().putString(AppPrefs.KEY_XRAY_PROFILE_TRAFFIC_JSON, object.toString()).apply();
    }

    private static void pruneProfileTrafficStats(Context context, Collection<String> activeProfileIds) {
        LinkedHashSet<String> allowedIds = new LinkedHashSet<>();
        if (activeProfileIds != null) {
            for (String profileId : activeProfileIds) {
                String normalizedProfileId = trim(profileId);
                if (!TextUtils.isEmpty(normalizedProfileId)) {
                    allowedIds.add(normalizedProfileId);
                }
            }
        }
        Map<String, ProfileTrafficStats> current = getProfileTrafficStatsMap(context);
        if (current.isEmpty()) {
            return;
        }
        current.keySet().retainAll(allowedIds);
        writeProfileTrafficStats(context, current);
    }

    private static void writeProfilePingResults(Context context, Map<String, ProfilePingResult> profilePingResults) {
        JSONObject object = new JSONObject();
        if (profilePingResults != null) {
            for (Map.Entry<String, ProfilePingResult> entry : profilePingResults.entrySet()) {
                String profilePingKey = trim(entry.getKey());
                if (TextUtils.isEmpty(profilePingKey) || entry.getValue() == null) {
                    continue;
                }
                try {
                    JSONObject item = new JSONObject();
                    item.put("success", entry.getValue().success);
                    item.put("latency_ms", Math.max(0, entry.getValue().latencyMs));
                    object.put(profilePingKey, item);
                } catch (Exception ignored) {}
            }
        }
        runtimePrefs(context).edit().putString(AppPrefs.KEY_XRAY_PROFILE_TCPING_JSON, object.toString()).apply();
    }

    private static void pruneProfilePingResults(Context context, Collection<String> activeProfilePingKeys) {
        LinkedHashSet<String> allowedKeys = new LinkedHashSet<>();
        if (activeProfilePingKeys != null) {
            for (String profilePingKey : activeProfilePingKeys) {
                String normalizedKey = trim(profilePingKey);
                if (!TextUtils.isEmpty(normalizedKey)) {
                    allowedKeys.add(normalizedKey);
                }
            }
        }
        Map<String, ProfilePingResult> current = getProfilePingResultsMap(context);
        if (current.isEmpty()) {
            return;
        }
        current.keySet().retainAll(allowedKeys);
        writeProfilePingResults(context, current);
    }

    private static Collection<String> collectProfileIds(Collection<XrayProfile> profiles) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (profiles != null) {
            for (XrayProfile profile : profiles) {
                if (profile != null && !TextUtils.isEmpty(trim(profile.id))) {
                    result.add(trim(profile.id));
                }
            }
        }
        return result;
    }

    private static Collection<String> collectProfilePingKeys(Collection<XrayProfile> profiles) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (profiles != null) {
            for (XrayProfile profile : profiles) {
                String profilePingKey = getProfilePingKey(profile);
                if (!TextUtils.isEmpty(profilePingKey)) {
                    result.add(profilePingKey);
                }
            }
        }
        return result;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
