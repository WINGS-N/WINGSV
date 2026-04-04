package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import wings.v.qs.QuickSettingsTiles;

public final class XrayStore {
    private static final int DEFAULT_LOCAL_PROXY_PORT = 10808;
    private static final String DEFAULT_SUBSCRIPTION_URL =
            "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt";
    private static final String DEFAULT_SUBSCRIPTION_TITLE = "Universal";
    private static final String DEFAULT_REMOTE_DNS = "https://common.dot.dns.yandex.net/dns-query";
    private static final String DEFAULT_DIRECT_DNS = "https://common.dot.dns.yandex.net/dns-query";

    private XrayStore() {
    }

    public static BackendType getBackendType(Context context) {
        return BackendType.fromPrefValue(prefs(context).getString(
                AppPrefs.KEY_BACKEND_TYPE,
                BackendType.VK_TURN_WIREGUARD.prefValue
        ));
    }

    public static void setBackendType(Context context, BackendType backendType) {
        Context appContext = context.getApplicationContext();
        prefs(appContext).edit()
                .putString(AppPrefs.KEY_BACKEND_TYPE,
                        backendType == null ? BackendType.VK_TURN_WIREGUARD.prefValue : backendType.prefValue)
                .apply();
        QuickSettingsTiles.requestRefresh(appContext);
    }

    public static XraySettings getXraySettings(Context context) {
        SharedPreferences prefs = prefs(context);
        XraySettings settings = new XraySettings();
        settings.allowLan = prefs.getBoolean(AppPrefs.KEY_XRAY_ALLOW_LAN, false);
        settings.allowInsecure = prefs.getBoolean(AppPrefs.KEY_XRAY_ALLOW_INSECURE, false);
        settings.localProxyPort = parseInt(
                prefs.getString(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT, String.valueOf(DEFAULT_LOCAL_PROXY_PORT)),
                DEFAULT_LOCAL_PROXY_PORT
        );
        settings.remoteDns = trim(prefs.getString(AppPrefs.KEY_XRAY_REMOTE_DNS, DEFAULT_REMOTE_DNS));
        settings.directDns = trim(prefs.getString(AppPrefs.KEY_XRAY_DIRECT_DNS, DEFAULT_DIRECT_DNS));
        settings.ipv6 = prefs.getBoolean(AppPrefs.KEY_XRAY_IPV6_ENABLED, true);
        settings.sniffingEnabled = prefs.getBoolean(AppPrefs.KEY_XRAY_SNIFFING_ENABLED, true);
        return settings;
    }

    public static void setXraySettings(Context context, XraySettings settings) {
        XraySettings value = settings != null ? settings : new XraySettings();
        prefs(context).edit()
                .putBoolean(AppPrefs.KEY_XRAY_ALLOW_LAN, value.allowLan)
                .putBoolean(AppPrefs.KEY_XRAY_ALLOW_INSECURE, value.allowInsecure)
                .putString(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT, String.valueOf(
                        value.localProxyPort > 0 ? value.localProxyPort : DEFAULT_LOCAL_PROXY_PORT
                ))
                .putString(AppPrefs.KEY_XRAY_REMOTE_DNS,
                        TextUtils.isEmpty(trim(value.remoteDns)) ? DEFAULT_REMOTE_DNS : trim(value.remoteDns))
                .putString(AppPrefs.KEY_XRAY_DIRECT_DNS,
                        TextUtils.isEmpty(trim(value.directDns)) ? DEFAULT_DIRECT_DNS : trim(value.directDns))
                .putBoolean(AppPrefs.KEY_XRAY_IPV6_ENABLED, value.ipv6)
                .putBoolean(AppPrefs.KEY_XRAY_SNIFFING_ENABLED, value.sniffingEnabled)
                .apply();
    }

    public static List<XraySubscription> getSubscriptions(Context context) {
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
        if (!prefs.getBoolean(AppPrefs.KEY_XRAY_UNIVERSAL_SUBSCRIPTION_MIGRATED, false)) {
            if (!hasSubscriptionWithUrl(result, DEFAULT_SUBSCRIPTION_URL)) {
                result.add(new XraySubscription(
                        null,
                        DEFAULT_SUBSCRIPTION_TITLE,
                        DEFAULT_SUBSCRIPTION_URL,
                        "auto",
                        getRefreshIntervalHours(context),
                        true,
                        0L
                ));
                setSubscriptions(context, result);
                prefs.edit()
                        .putBoolean(AppPrefs.KEY_XRAY_UNIVERSAL_SUBSCRIPTION_MIGRATED, true)
                        .apply();
            } else {
                prefs.edit()
                        .putBoolean(AppPrefs.KEY_XRAY_UNIVERSAL_SUBSCRIPTION_MIGRATED, true)
                        .apply();
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
                } catch (Exception ignored) {
                }
            }
        }
        prefs(context).edit()
                .putString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_JSON, array.toString())
                .putBoolean(AppPrefs.KEY_XRAY_DEFAULT_SUBSCRIPTION_SEEDED, true)
                .apply();
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
                deduped.put(profile.stableDedupKey(), profile);
            }
        }
        for (XrayProfile profile : deduped.values()) {
            try {
                array.put(profile.toJson());
            } catch (Exception ignored) {
            }
        }
        prefs(context).edit().putString(AppPrefs.KEY_XRAY_PROFILES_JSON, array.toString()).apply();
    }

    public static String getActiveProfileId(Context context) {
        return trim(prefs(context).getString(AppPrefs.KEY_XRAY_ACTIVE_PROFILE_ID, ""));
    }

    public static void setActiveProfileId(Context context, String profileId) {
        prefs(context).edit().putString(AppPrefs.KEY_XRAY_ACTIVE_PROFILE_ID, trim(profileId)).apply();
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

    public static int getRefreshIntervalHours(Context context) {
        return parseInt(
                prefs(context).getString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_HOURS, "24"),
                24
        );
    }

    public static void setRefreshIntervalHours(Context context, int hours) {
        prefs(context).edit()
                .putString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_HOURS, String.valueOf(Math.max(hours, 1)))
                .apply();
    }

    public static long getLastSubscriptionsRefreshAt(Context context) {
        return prefs(context).getLong(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_LAST_REFRESH_AT, 0L);
    }

    public static void setLastSubscriptionsRefreshAt(Context context, long refreshedAt) {
        prefs(context).edit()
                .putLong(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_LAST_REFRESH_AT, Math.max(refreshedAt, 0L))
                .apply();
    }

    public static String getLastSubscriptionsError(Context context) {
        return trim(prefs(context).getString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_LAST_ERROR, ""));
    }

    public static void setLastSubscriptionsError(Context context, String error) {
        prefs(context).edit()
                .putString(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_LAST_ERROR, trim(error))
                .apply();
    }

    public static String getImportedSubscriptionJson(Context context) {
        return trim(prefs(context).getString(AppPrefs.KEY_XRAY_IMPORTED_SUBSCRIPTION_JSON, ""));
    }

    public static void setImportedSubscriptionJson(Context context, String rawJson) {
        prefs(context).edit()
                .putString(AppPrefs.KEY_XRAY_IMPORTED_SUBSCRIPTION_JSON, trim(rawJson))
                .apply();
    }

    private static JSONArray parseArray(String rawValue) {
        try {
            return new JSONArray(TextUtils.isEmpty(rawValue) ? "[]" : rawValue);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    private static int parseInt(String rawValue, int fallback) {
        try {
            return Integer.parseInt(trim(rawValue));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean hasSubscriptionWithUrl(List<XraySubscription> subscriptions, String url) {
        String expectedKey = TextUtils.isEmpty(url) ? "" : url.trim().toLowerCase();
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

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
