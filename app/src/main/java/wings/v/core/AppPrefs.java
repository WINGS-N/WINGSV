package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import androidx.preference.PreferenceManager;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import wings.v.R;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LongVariable",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.LawOfDemeter",
    }
)
public final class AppPrefs {

    private static final String TURN_SESSION_MODE_AUTO = "auto";
    private static final String TURN_SESSION_MODE_MAINLINE = "mainline";
    private static final String TURN_SESSION_MODE_MUX = "mux";
    private static final String DEFAULT_ROOT_WIREGUARD_INTERFACE_NAME = "wingsv-{{hex}}";
    private static final int MAX_INTERFACE_NAME_LENGTH = 15;
    private static final String HEX_PLACEHOLDER = "{{hex}}";
    private static final Pattern INTERFACE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+");

    public static final String KEY_ENDPOINT = "pref_endpoint";
    public static final String KEY_VK_LINK = "pref_vk_link";
    public static final String KEY_THREADS = "pref_threads";
    public static final String KEY_USE_UDP = "pref_use_udp";
    public static final String KEY_NO_OBFUSCATION = "pref_no_obfuscation";
    public static final String KEY_MANUAL_CAPTCHA = "pref_manual_captcha";
    public static final String KEY_TURN_SESSION_MODE = "pref_turn_session_mode";
    public static final String KEY_LOCAL_ENDPOINT = "pref_local_endpoint";
    public static final String KEY_TURN_HOST = "pref_turn_host";
    public static final String KEY_TURN_PORT = "pref_turn_port";
    public static final String KEY_WG_PRIVATE_KEY = "pref_wg_private_key";
    public static final String KEY_WG_ADDRESSES = "pref_wg_addresses";
    public static final String KEY_WG_DNS = "pref_wg_dns";
    public static final String KEY_WG_MTU = "pref_wg_mtu";
    public static final String KEY_WG_PUBLIC_KEY = "pref_wg_public_key";
    public static final String KEY_WG_PRESHARED_KEY = "pref_wg_preshared_key";
    public static final String KEY_WG_ALLOWED_IPS = "pref_wg_allowed_ips";
    public static final String KEY_WG_ENDPOINT = "pref_wg_endpoint";
    public static final String KEY_AWG_QUICK_CONFIG = "pref_awg_quick_config";
    public static final String KEY_BACKEND_TYPE = "pref_backend_type";
    public static final String KEY_OPEN_VK_TURN_SETTINGS = "pref_open_vk_turn_settings";
    public static final String KEY_OPEN_ROOT_INTERFACE_SETTINGS = "pref_open_root_interface_settings";
    public static final String KEY_ROOT_WIREGUARD_INTERFACE_NAME = "pref_root_wg_interface_name";
    public static final String KEY_XRAY_ALLOW_LAN = "pref_xray_allow_lan";
    public static final String KEY_XRAY_ALLOW_INSECURE = "pref_xray_allow_insecure";
    public static final String KEY_XRAY_LOCAL_PROXY_ENABLED = "pref_xray_local_proxy_enabled";
    public static final String KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED = "pref_xray_local_proxy_auth_enabled";
    public static final String KEY_XRAY_LOCAL_PROXY_USERNAME = "pref_xray_local_proxy_username";
    public static final String KEY_XRAY_LOCAL_PROXY_PASSWORD = "pref_xray_local_proxy_password";
    public static final String KEY_XRAY_LOCAL_PROXY_PORT = "pref_xray_local_proxy_port";
    public static final String KEY_XRAY_REMOTE_DNS = "pref_xray_remote_dns";
    public static final String KEY_XRAY_DIRECT_DNS = "pref_xray_direct_dns";
    public static final String KEY_XRAY_IPV6_ENABLED = "pref_xray_ipv6_enabled";
    public static final String KEY_XRAY_SNIFFING_ENABLED = "pref_xray_sniffing_enabled";
    public static final String KEY_XRAY_PROXY_QUIC_ENABLED = "pref_xray_proxy_quic_enabled";
    public static final String KEY_XRAY_RESTART_ON_NETWORK_CHANGE = "pref_xray_restart_on_network_change";
    public static final String KEY_XRAY_ROUTING_GEOIP_URL = "pref_xray_routing_geoip_url";
    public static final String KEY_XRAY_ROUTING_GEOSITE_URL = "pref_xray_routing_geosite_url";
    public static final String KEY_XRAY_ROUTING_RULES_JSON = "pref_xray_routing_rules_json";
    public static final String KEY_XRAY_ROUTING_BOOTSTRAP_ATTEMPTED = "pref_xray_routing_bootstrap_attempted";
    public static final String KEY_XRAY_SUBSCRIPTIONS_JSON = "pref_xray_subscriptions_json";
    public static final String KEY_XRAY_DEFAULT_SUBSCRIPTION_SEEDED = "pref_xray_default_subscription_seeded";
    public static final String KEY_XRAY_UNIVERSAL_SUBSCRIPTION_MIGRATED = "pref_xray_universal_subscription_migrated";
    public static final String KEY_XRAY_PROFILES_JSON = "pref_xray_profiles_json";
    public static final String KEY_XRAY_PROFILE_TRAFFIC_JSON = "pref_xray_profile_traffic_json";
    public static final String KEY_XRAY_PROFILE_TCPING_JSON = "pref_xray_profile_tcping_json";
    public static final String KEY_XRAY_ACTIVE_PROFILE_ID = "pref_xray_active_profile_id";
    public static final String KEY_XRAY_SUBSCRIPTIONS_REFRESH_HOURS = "pref_xray_subscriptions_refresh_hours";
    public static final String KEY_XRAY_SUBSCRIPTIONS_REFRESH_MINUTES = "pref_xray_subscriptions_refresh_minutes";
    public static final String KEY_XRAY_SUBSCRIPTIONS_LAST_REFRESH_AT = "pref_xray_subscriptions_last_refresh_at";
    public static final String KEY_XRAY_SUBSCRIPTIONS_LAST_ERROR = "pref_xray_subscriptions_last_error";
    public static final String KEY_XRAY_IMPORTED_SUBSCRIPTION_JSON = "pref_xray_imported_subscription_json";
    public static final String KEY_THEME_MODE = "pref_theme_mode";
    public static final String KEY_SUBSCRIPTION_HWID_ENABLED = "pref_subscription_hwid_enabled";
    public static final String KEY_SUBSCRIPTION_HWID_MANUAL_ENABLED = "pref_subscription_hwid_manual_enabled";
    public static final String KEY_SUBSCRIPTION_HWID_VALUE = "pref_subscription_hwid_value";
    public static final String KEY_SUBSCRIPTION_HWID_DEVICE_OS = "pref_subscription_hwid_device_os";
    public static final String KEY_SUBSCRIPTION_HWID_VER_OS = "pref_subscription_hwid_ver_os";
    public static final String KEY_SUBSCRIPTION_HWID_DEVICE_MODEL = "pref_subscription_hwid_device_model";
    public static final String KEY_APP_ROUTING_BYPASS = "pref_app_routing_bypass";
    public static final String KEY_APP_ROUTING_PACKAGES = "pref_app_routing_packages";
    public static final String KEY_ROOT_MODE = "pref_root_mode";
    public static final String KEY_KERNEL_WIREGUARD = "pref_kernel_wireguard";
    public static final String KEY_ROOT_ACCESS_GRANTED = "pref_root_access_granted";
    public static final String KEY_ROOT_ACCESS_CHECKED_AT = "pref_root_access_checked_at";
    public static final String KEY_ROOT_RUNTIME_ACTIVE = "pref_root_runtime_active";
    public static final String KEY_ROOT_RUNTIME_TUNNEL = "pref_root_runtime_tunnel";
    public static final String KEY_ROOT_RUNTIME_PROXY_PID = "pref_root_runtime_proxy_pid";
    public static final String KEY_RUNTIME_UPSTREAM_INTERFACE = "service.upstream";
    public static final String KEY_RUNTIME_UPSTREAM_ROOT_DNS = "service.upstream.rootDns";
    public static final String KEY_AUTO_START_ON_BOOT = "pref_auto_start_on_boot";
    public static final String KEY_SHARING_AUTO_START_ON_BOOT = "pref_sharing_auto_start_on_boot";
    public static final String KEY_SHARING_LAST_ACTIVE_TYPES = "pref_sharing_last_active_types";
    public static final String KEY_SHARING_UPSTREAM_INTERFACE = "pref_sharing_upstream_interface";
    public static final String KEY_SHARING_FALLBACK_UPSTREAM_INTERFACE = "pref_sharing_fallback_upstream_interface";
    public static final String KEY_SHARING_MASQUERADE_MODE = "pref_sharing_masquerade_mode";
    public static final String KEY_SHARING_DISABLE_IPV6 = "pref_sharing_disable_ipv6";
    public static final String KEY_SHARING_DHCP_WORKAROUND = "pref_sharing_dhcp_workaround";
    public static final String KEY_SHARING_WIFI_LOCK = "pref_sharing_wifi_lock";
    public static final String KEY_SHARING_REPEATER_SAFE_MODE = "pref_sharing_repeater_safe_mode";
    public static final String KEY_SHARING_TEMP_HOTSPOT_USE_SYSTEM = "pref_sharing_temp_hotspot_use_system";
    public static final String KEY_SHARING_IP_MONITOR_MODE = "pref_sharing_ip_monitor_mode";
    public static final String KEY_ONBOARDING_SEEN = "pref_onboarding_seen";
    public static final String KEY_BATTERY_OPTIMIZATION_ACKNOWLEDGED = "pref_battery_optimization_acknowledged";
    public static final String KEY_FIRST_LAUNCH_EXPERIENCE_SEEN = "pref_first_launch_experience_seen";
    public static final String KEY_FIRST_LAUNCH_EXPERIENCE_RESET_300 = "pref_first_launch_experience_reset_300";
    public static final String KEY_EXTERNAL_ACTION_TRANSIENT_LAUNCH = "pref_external_action_transient_launch";
    public static final String KEY_PENDING_PROFILES_FILTER_ID = "pref_pending_profiles_filter_id";
    public static final String KEY_UPDATES_LAST_NOTIFIED_TAG = "pref_updates_last_notified_tag";
    public static final String SHARING_MASQUERADE_NONE = "none";
    public static final String SHARING_MASQUERADE_SIMPLE = "simple";
    public static final String SHARING_MASQUERADE_NETD = "netd";
    public static final String SHARING_WIFI_LOCK_SYSTEM = "system";
    public static final String SHARING_WIFI_LOCK_FULL = "full";
    public static final String SHARING_WIFI_LOCK_HIGH_PERF = "high_perf";
    public static final String SHARING_WIFI_LOCK_LOW_LATENCY = "low_latency";
    public static final String SHARING_IP_MONITOR_NETLINK = "netlink";
    public static final String SHARING_IP_MONITOR_NETLINK_ROOT = "netlink_root";
    public static final String SHARING_IP_MONITOR_POLL = "poll";
    public static final String SHARING_IP_MONITOR_POLL_ROOT = "poll_root";
    public static final String THEME_MODE_SYSTEM = "system";
    public static final String THEME_MODE_DARK = "dark";
    public static final String THEME_MODE_LIGHT = "light";

    private AppPrefs() {}

    public static void ensureDefaults(Context context) {
        PreferenceManager.setDefaultValues(context, R.xml.proxy_preferences, false);
        PreferenceManager.setDefaultValues(context, R.xml.vk_turn_preferences, false);
        PreferenceManager.setDefaultValues(context, R.xml.root_interface_preferences, false);
        PreferenceManager.setDefaultValues(context, R.xml.xray_preferences, false);
        PreferenceManager.setDefaultValues(context, R.xml.amnezia_preferences, false);
        PreferenceManager.setDefaultValues(context, R.xml.active_probing_preferences, false);
        PreferenceManager.setDefaultValues(context, R.xml.byedpi_preferences, false);
        PreferenceManager.setDefaultValues(context, R.xml.subscription_hwid_preferences, false);
        XposedModulePrefs.ensureDefaults(context);
        migrateFirstLaunchExperienceReset300(context);
        XrayRoutingStore.ensureGeoFilesBootstrap(context);
    }

    private static void migrateFirstLaunchExperienceReset300(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.getBoolean(KEY_FIRST_LAUNCH_EXPERIENCE_RESET_300, false)) {
            return;
        }
        preferences
            .edit()
            .putBoolean(KEY_ONBOARDING_SEEN, false)
            .putBoolean(KEY_FIRST_LAUNCH_EXPERIENCE_SEEN, false)
            .putBoolean(KEY_FIRST_LAUNCH_EXPERIENCE_RESET_300, true)
            .apply();
    }

    public static boolean isOnboardingSeen(Context context) {
        return prefs(context).getBoolean(KEY_ONBOARDING_SEEN, false);
    }

    public static boolean isFirstLaunchExperienceSeen(Context context) {
        return prefs(context).getBoolean(KEY_FIRST_LAUNCH_EXPERIENCE_SEEN, false);
    }

    public static boolean isExternalActionTransientLaunchPending(Context context) {
        return prefs(context).getBoolean(KEY_EXTERNAL_ACTION_TRANSIENT_LAUNCH, false);
    }

    public static void setExternalActionTransientLaunchPending(Context context, boolean pending) {
        prefs(context).edit().putBoolean(KEY_EXTERNAL_ACTION_TRANSIENT_LAUNCH, pending).apply();
    }

    public static void setPendingProfilesFilterId(Context context, String filterId) {
        prefs(context).edit().putString(KEY_PENDING_PROFILES_FILTER_ID, trim(filterId)).apply();
    }

    public static String consumePendingProfilesFilterId(Context context) {
        SharedPreferences preferences = prefs(context);
        String filterId = trim(preferences.getString(KEY_PENDING_PROFILES_FILTER_ID, ""));
        if (!TextUtils.isEmpty(filterId)) {
            preferences.edit().remove(KEY_PENDING_PROFILES_FILTER_ID).apply();
        }
        return filterId;
    }

    public static String getLastUpdateNotifiedTag(Context context) {
        return trim(prefs(context).getString(KEY_UPDATES_LAST_NOTIFIED_TAG, ""));
    }

    public static void setLastUpdateNotifiedTag(Context context, String tagName) {
        prefs(context).edit().putString(KEY_UPDATES_LAST_NOTIFIED_TAG, trim(tagName)).apply();
    }

    public static boolean isRootModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ROOT_MODE, false);
    }

    public static void setRootModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ROOT_MODE, enabled).apply();
    }

    public static boolean isKernelWireGuardEnabled(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.contains(KEY_KERNEL_WIREGUARD)) {
            return preferences.getBoolean(KEY_KERNEL_WIREGUARD, false);
        }
        return preferences.getBoolean(KEY_ROOT_MODE, false);
    }

    public static void setKernelWireGuardEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KERNEL_WIREGUARD, enabled).apply();
    }

    public static String getThemeMode(Context context) {
        return normalizeThemeMode(prefs(context).getString(KEY_THEME_MODE, THEME_MODE_SYSTEM));
    }

    public static void setThemeMode(Context context, String value) {
        prefs(context).edit().putString(KEY_THEME_MODE, normalizeThemeMode(value)).apply();
    }

    public static String getRootWireGuardInterfaceNameTemplate(Context context) {
        SharedPreferences preferences = prefs(context);
        String value = preferences.getString(KEY_ROOT_WIREGUARD_INTERFACE_NAME, DEFAULT_ROOT_WIREGUARD_INTERFACE_NAME);
        return normalizeInterfaceName(value, DEFAULT_ROOT_WIREGUARD_INTERFACE_NAME, true);
    }

    public static String normalizeRootWireGuardInterfaceNameTemplate(String value) {
        return normalizeInterfaceName(value, DEFAULT_ROOT_WIREGUARD_INTERFACE_NAME, true);
    }

    public static boolean isValidRootWireGuardInterfaceNameTemplate(String value) {
        return isValidInterfaceName(value, true);
    }

    public static String resolveRootWireGuardInterfaceName(Context context, long suffix) {
        return resolveInterfaceNameTemplate(getRootWireGuardInterfaceNameTemplate(context), suffix);
    }

    public static boolean matchesManagedRootWireGuardInterfaceName(Context context, String interfaceName) {
        String normalizedName = trim(interfaceName);
        if (TextUtils.isEmpty(normalizedName)) {
            return false;
        }
        if (matchesLegacyManagedRootWireGuardInterfaceName(normalizedName)) {
            return true;
        }
        String template = getRootWireGuardInterfaceNameTemplate(context);
        if (!template.contains(HEX_PLACEHOLDER)) {
            return TextUtils.equals(template, normalizedName);
        }
        int placeholderIndex = template.indexOf(HEX_PLACEHOLDER);
        String prefix = template.substring(0, placeholderIndex);
        String suffix = template.substring(placeholderIndex + HEX_PLACEHOLDER.length());
        if (!normalizedName.startsWith(prefix) || !normalizedName.endsWith(suffix)) {
            return false;
        }
        String hexPart = normalizedName.substring(prefix.length(), normalizedName.length() - suffix.length());
        if (TextUtils.isEmpty(hexPart)) {
            return false;
        }
        for (int index = 0; index < hexPart.length(); index++) {
            char symbol = Character.toLowerCase(hexPart.charAt(index));
            if ((symbol < '0' || symbol > '9') && (symbol < 'a' || symbol > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeThemeMode(String value) {
        String normalizedValue = trim(value);
        if (THEME_MODE_DARK.equals(normalizedValue)) {
            return THEME_MODE_DARK;
        }
        if (THEME_MODE_LIGHT.equals(normalizedValue)) {
            return THEME_MODE_LIGHT;
        }
        return THEME_MODE_SYSTEM;
    }

    public static boolean isRootAccessGranted(Context context) {
        return prefs(context).getBoolean(KEY_ROOT_ACCESS_GRANTED, false);
    }

    public static void setRootAccessGranted(Context context, boolean granted) {
        prefs(context)
            .edit()
            .putBoolean(KEY_ROOT_ACCESS_GRANTED, granted)
            .putLong(KEY_ROOT_ACCESS_CHECKED_AT, System.currentTimeMillis())
            .apply();
    }

    public static long getRootAccessCheckedAt(Context context) {
        return prefs(context).getLong(KEY_ROOT_ACCESS_CHECKED_AT, 0L);
    }

    public static boolean hasRootRuntimeState(Context context) {
        return (
            prefs(context).getBoolean(KEY_ROOT_RUNTIME_ACTIVE, false) &&
            !TextUtils.isEmpty(getRootRuntimeTunnelName(context))
        );
    }

    public static String getRootRuntimeTunnelName(Context context) {
        return trim(prefs(context).getString(KEY_ROOT_RUNTIME_TUNNEL, ""));
    }

    public static long getRootRuntimeProxyPid(Context context) {
        return prefs(context).getLong(KEY_ROOT_RUNTIME_PROXY_PID, 0L);
    }

    public static void setRootRuntimeState(Context context, String tunnelName, long proxyPid) {
        String normalizedTunnelName = trim(tunnelName);
        boolean active = !TextUtils.isEmpty(normalizedTunnelName);
        prefs(context)
            .edit()
            .putBoolean(KEY_ROOT_RUNTIME_ACTIVE, active)
            .putString(KEY_ROOT_RUNTIME_TUNNEL, normalizedTunnelName)
            .putLong(KEY_ROOT_RUNTIME_PROXY_PID, Math.max(proxyPid, 0L))
            .apply();
    }

    public static void clearRootRuntimeState(Context context) {
        prefs(context)
            .edit()
            .remove(KEY_ROOT_RUNTIME_ACTIVE)
            .remove(KEY_ROOT_RUNTIME_TUNNEL)
            .remove(KEY_ROOT_RUNTIME_PROXY_PID)
            .apply();
    }

    public static String getRuntimeUpstreamInterface(Context context) {
        return trim(prefs(context).getString(KEY_RUNTIME_UPSTREAM_INTERFACE, ""));
    }

    public static String getRuntimeUpstreamRootDns(Context context) {
        return trim(prefs(context).getString(KEY_RUNTIME_UPSTREAM_ROOT_DNS, ""));
    }

    public static String getRootRuntimeRecoveryTunnelHint(Context context) {
        String runtimeTunnel = getRootRuntimeTunnelName(context);
        if (!TextUtils.isEmpty(runtimeTunnel)) {
            return runtimeTunnel;
        }
        return getRuntimeUpstreamInterface(context);
    }

    public static boolean hasRootRuntimeHint(Context context) {
        return !TextUtils.isEmpty(getRootRuntimeRecoveryTunnelHint(context));
    }

    public static void clearRuntimeUpstreamState(Context context) {
        prefs(context).edit().remove(KEY_RUNTIME_UPSTREAM_INTERFACE).remove(KEY_RUNTIME_UPSTREAM_ROOT_DNS).apply();
    }

    private static String normalizeInterfaceName(String value, String defaultValue, boolean allowHexPlaceholder) {
        String normalized = trim(value);
        if (TextUtils.isEmpty(normalized)) {
            return defaultValue;
        }
        return isValidInterfaceName(normalized, allowHexPlaceholder) ? normalized : defaultValue;
    }

    private static boolean isValidInterfaceName(String value, boolean allowHexPlaceholder) {
        String normalized = trim(value);
        if (TextUtils.isEmpty(normalized)) {
            return false;
        }
        String candidate = normalized;
        if (allowHexPlaceholder) {
            int firstPlaceholderIndex = candidate.indexOf(HEX_PLACEHOLDER);
            int lastPlaceholderIndex = candidate.lastIndexOf(HEX_PLACEHOLDER);
            if (firstPlaceholderIndex != lastPlaceholderIndex) {
                return false;
            }
            if (firstPlaceholderIndex >= 0) {
                candidate = candidate.replace(HEX_PLACEHOLDER, "ffffff");
            }
            if (
                candidate.contains("<") || candidate.contains(">") || candidate.contains("{") || candidate.contains("}")
            ) {
                return false;
            }
        } else if (
            candidate.contains("<") || candidate.contains(">") || candidate.contains("{") || candidate.contains("}")
        ) {
            return false;
        }
        if (candidate.length() > MAX_INTERFACE_NAME_LENGTH) {
            return false;
        }
        return INTERFACE_NAME_PATTERN.matcher(candidate).matches();
    }

    private static String resolveInterfaceNameTemplate(String template, long suffix) {
        String normalizedTemplate = normalizeInterfaceName(template, DEFAULT_ROOT_WIREGUARD_INTERFACE_NAME, true);
        if (!normalizedTemplate.contains(HEX_PLACEHOLDER)) {
            return normalizedTemplate;
        }
        String hexSuffix = Long.toHexString(Math.max(0L, suffix) & 0xFFFFFFL).toLowerCase(Locale.ROOT);
        return normalizedTemplate.replace(HEX_PLACEHOLDER, hexSuffix);
    }

    private static boolean matchesLegacyManagedRootWireGuardInterfaceName(String interfaceName) {
        if (TextUtils.isEmpty(interfaceName) || !interfaceName.startsWith("wingsv")) {
            return false;
        }
        String suffix = interfaceName.startsWith("wingsv-")
            ? interfaceName.substring("wingsv-".length())
            : interfaceName.substring("wingsv".length());
        if (TextUtils.isEmpty(suffix)) {
            return false;
        }
        for (int index = 0; index < suffix.length(); index++) {
            char symbol = Character.toLowerCase(suffix.charAt(index));
            if ((symbol < '0' || symbol > '9') && (symbol < 'a' || symbol > 'f')) {
                return false;
            }
        }
        return true;
    }

    public static boolean isAutoStartOnBootEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, false);
    }

    public static void setAutoStartOnBootEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply();
    }

    public static boolean isSharingAutoStartOnBootEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SHARING_AUTO_START_ON_BOOT, false);
    }

    public static void setSharingAutoStartOnBootEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHARING_AUTO_START_ON_BOOT, enabled).apply();
    }

    public static Set<TetherType> getSharingLastActiveTypes(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_SHARING_LAST_ACTIVE_TYPES, null);
        Set<TetherType> result = new LinkedHashSet<>();
        if (stored == null || stored.isEmpty()) {
            return result;
        }
        for (String rawValue : stored) {
            if (TextUtils.isEmpty(rawValue)) {
                continue;
            }
            try {
                result.add(TetherType.fromCommandName(rawValue.trim()));
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    public static void setSharingLastActiveTypes(Context context, Set<TetherType> types) {
        Set<String> values = new LinkedHashSet<>();
        if (types != null) {
            for (TetherType type : types) {
                if (type != null) {
                    values.add(type.commandName);
                }
            }
        }
        prefs(context).edit().putStringSet(KEY_SHARING_LAST_ACTIVE_TYPES, values).apply();
    }

    public static String getSharingUpstreamInterface(Context context) {
        return trim(prefs(context).getString(KEY_SHARING_UPSTREAM_INTERFACE, ""));
    }

    public static void setSharingUpstreamInterface(Context context, String value) {
        prefs(context).edit().putString(KEY_SHARING_UPSTREAM_INTERFACE, trim(value)).apply();
    }

    public static String getSharingFallbackUpstreamInterface(Context context) {
        return trim(prefs(context).getString(KEY_SHARING_FALLBACK_UPSTREAM_INTERFACE, ""));
    }

    public static void setSharingFallbackUpstreamInterface(Context context, String value) {
        prefs(context).edit().putString(KEY_SHARING_FALLBACK_UPSTREAM_INTERFACE, trim(value)).apply();
    }

    public static String getSharingMasqueradeMode(Context context) {
        return trim(prefs(context).getString(KEY_SHARING_MASQUERADE_MODE, SHARING_MASQUERADE_SIMPLE));
    }

    public static void setSharingMasqueradeMode(Context context, String value) {
        prefs(context)
            .edit()
            .putString(
                KEY_SHARING_MASQUERADE_MODE,
                TextUtils.isEmpty(trim(value)) ? SHARING_MASQUERADE_SIMPLE : trim(value)
            )
            .apply();
    }

    public static boolean isSharingDisableIpv6Enabled(Context context) {
        return prefs(context).getBoolean(KEY_SHARING_DISABLE_IPV6, true);
    }

    public static void setSharingDisableIpv6Enabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHARING_DISABLE_IPV6, enabled).apply();
    }

    public static boolean isSharingDhcpWorkaroundEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SHARING_DHCP_WORKAROUND, false);
    }

    public static void setSharingDhcpWorkaroundEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHARING_DHCP_WORKAROUND, enabled).apply();
    }

    public static String getSharingWifiLockMode(Context context) {
        return trim(prefs(context).getString(KEY_SHARING_WIFI_LOCK, SHARING_WIFI_LOCK_SYSTEM));
    }

    public static void setSharingWifiLockMode(Context context, String value) {
        prefs(context)
            .edit()
            .putString(KEY_SHARING_WIFI_LOCK, TextUtils.isEmpty(trim(value)) ? SHARING_WIFI_LOCK_SYSTEM : trim(value))
            .apply();
    }

    public static boolean isSharingRepeaterSafeModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SHARING_REPEATER_SAFE_MODE, true);
    }

    public static void setSharingRepeaterSafeModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHARING_REPEATER_SAFE_MODE, enabled).apply();
    }

    public static boolean isSharingTempHotspotUseSystemEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SHARING_TEMP_HOTSPOT_USE_SYSTEM, false);
    }

    public static void setSharingTempHotspotUseSystemEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHARING_TEMP_HOTSPOT_USE_SYSTEM, enabled).apply();
    }

    public static String getSharingIpMonitorMode(Context context) {
        return trim(prefs(context).getString(KEY_SHARING_IP_MONITOR_MODE, SHARING_IP_MONITOR_NETLINK));
    }

    public static void setSharingIpMonitorMode(Context context, String value) {
        prefs(context)
            .edit()
            .putString(
                KEY_SHARING_IP_MONITOR_MODE,
                TextUtils.isEmpty(trim(value)) ? SHARING_IP_MONITOR_NETLINK : trim(value)
            )
            .apply();
    }

    public static void markOnboardingSeen(Context context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply();
    }

    public static boolean isBatteryOptimizationAcknowledged(Context context) {
        return prefs(context).getBoolean(KEY_BATTERY_OPTIMIZATION_ACKNOWLEDGED, false);
    }

    public static void setBatteryOptimizationAcknowledged(Context context, boolean acknowledged) {
        prefs(context).edit().putBoolean(KEY_BATTERY_OPTIMIZATION_ACKNOWLEDGED, acknowledged).apply();
    }

    public static void markFirstLaunchExperienceSeen(Context context) {
        prefs(context).edit().putBoolean(KEY_FIRST_LAUNCH_EXPERIENCE_SEEN, true).apply();
    }

    public static boolean isAppRoutingBypassEnabled(Context context) {
        return prefs(context).getBoolean(KEY_APP_ROUTING_BYPASS, true);
    }

    public static void setAppRoutingBypassEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_APP_ROUTING_BYPASS, enabled).apply();
    }

    public static Set<String> getAppRoutingPackages(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_APP_ROUTING_PACKAGES, null);
        if (stored == null || stored.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(stored);
    }

    public static void setAppRoutingPackageEnabled(Context context, String packageName, boolean enabled) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        Set<String> packages = getAppRoutingPackages(context);
        if (enabled) {
            packages.add(packageName);
        } else {
            packages.remove(packageName);
        }
        prefs(context).edit().putStringSet(KEY_APP_ROUTING_PACKAGES, packages).apply();
    }

    public static void setAppRoutingPackages(Context context, Set<String> packages) {
        LinkedHashSet<String> normalizedPackages = new LinkedHashSet<>();
        if (packages != null) {
            for (String packageName : packages) {
                if (!TextUtils.isEmpty(trim(packageName))) {
                    normalizedPackages.add(trim(packageName));
                }
            }
        }
        prefs(context).edit().putStringSet(KEY_APP_ROUTING_PACKAGES, normalizedPackages).apply();
    }

    public static ProxySettings getSettings(Context context) {
        SharedPreferences prefs = prefs(context);
        ProxySettings settings = new ProxySettings();
        settings.backendType = XrayStore.getBackendType(context);
        settings.endpoint = resolveEndpointForBackend(context, settings.backendType);
        settings.vkLink = trim(prefs.getString(KEY_VK_LINK, ""));
        settings.threads = parseInt(prefs.getString(KEY_THREADS, "8"), 8);
        settings.useUdp = prefs.getBoolean(KEY_USE_UDP, true);
        settings.noObfuscation = prefs.getBoolean(KEY_NO_OBFUSCATION, false);
        settings.manualCaptcha = prefs.getBoolean(KEY_MANUAL_CAPTCHA, false);
        settings.turnSessionMode = normalizeTurnSessionMode(prefs.getString(KEY_TURN_SESSION_MODE, "auto"));
        settings.localEndpoint = trim(prefs.getString(KEY_LOCAL_ENDPOINT, "127.0.0.1:9000"));
        settings.turnHost = trim(prefs.getString(KEY_TURN_HOST, ""));
        settings.turnPort = trim(prefs.getString(KEY_TURN_PORT, ""));
        settings.wgPrivateKey = trim(prefs.getString(KEY_WG_PRIVATE_KEY, ""));
        settings.wgAddresses = trim(prefs.getString(KEY_WG_ADDRESSES, ""));
        settings.wgDns = trim(prefs.getString(KEY_WG_DNS, "1.1.1.1, 1.0.0.1"));
        settings.wgMtu = parseInt(prefs.getString(KEY_WG_MTU, "1280"), 1280);
        settings.wgPublicKey = trim(prefs.getString(KEY_WG_PUBLIC_KEY, ""));
        settings.wgPresharedKey = trim(prefs.getString(KEY_WG_PRESHARED_KEY, ""));
        settings.wgAllowedIps = trim(prefs.getString(KEY_WG_ALLOWED_IPS, "0.0.0.0/0, ::/0"));
        settings.awgQuickConfig = AmneziaStore.getEffectiveQuickConfig(context);
        settings.rootModeEnabled = prefs.getBoolean(KEY_ROOT_MODE, false);
        settings.kernelWireguardEnabled = isKernelWireGuardEnabled(context);
        settings.activeXrayProfile = XrayStore.getActiveProfile(context);
        settings.xraySettings = XrayStore.getXraySettings(context);
        settings.byeDpiSettings = ByeDpiStore.getSettings(context);
        return settings;
    }

    public static void applyImportedConfig(Context context, WingsImportParser.ImportedConfig importedConfig) {
        if (importedConfig == null) {
            return;
        }
        BackendType backendType =
            importedConfig.backendType != null ? importedConfig.backendType : BackendType.VK_TURN_WIREGUARD;
        if (backendType == BackendType.XRAY && importedConfig.xrayMergeOnly) {
            mergeImportedXrayPayload(context, importedConfig);
            ActiveProbingManager.clearRestoreBackend(context);
            if (shouldActivateImportedXrayPayload(importedConfig)) {
                XrayStore.setBackendType(context, BackendType.XRAY);
            }
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit();

        if (importedConfig.hasTurnSettings) {
            applyImportedTurnSettings(editor, importedConfig, backendType);
        }
        if (importedConfig.hasWireGuardSettings) {
            applyImportedWireGuardSettings(editor, importedConfig, backendType);
        }
        editor.apply();

        if (importedConfig.hasAmneziaSettings) {
            try {
                AmneziaStore.applyRawConfig(context, importedConfig.awgQuickConfig);
            } catch (Exception ignored) {}
        }

        if (importedConfig.hasXraySettings) {
            applyImportedXraySettings(context, importedConfig);
        }
        if (importedConfig.hasXrayRouting) {
            XrayRoutingStore.setSourceUrl(context, XrayRoutingRule.MatchType.GEOIP, importedConfig.xrayRoutingGeoipUrl);
            XrayRoutingStore.setSourceUrl(
                context,
                XrayRoutingRule.MatchType.GEOSITE,
                importedConfig.xrayRoutingGeositeUrl
            );
            XrayRoutingStore.setRules(context, importedConfig.xrayRoutingRules);
        }
        if (importedConfig.hasAppRouting) {
            if (importedConfig.appRoutingBypass != null) {
                setAppRoutingBypassEnabled(context, importedConfig.appRoutingBypass);
            }
            setAppRoutingPackages(context, new LinkedHashSet<>(importedConfig.appRoutingPackages));
        }

        ActiveProbingManager.clearRestoreBackend(context);
        XrayStore.setBackendType(context, backendType);
    }

    private static void applyImportedTurnSettings(
        SharedPreferences.Editor editor,
        WingsImportParser.ImportedConfig importedConfig,
        BackendType backendType
    ) {
        if (backendType == BackendType.WIREGUARD && !importedConfig.hasAllSettings) {
            editor.putString(KEY_WG_ENDPOINT, trim(importedConfig.endpoint));
        } else {
            editor.putString(KEY_ENDPOINT, trim(importedConfig.endpoint));
        }
        editor.putString(KEY_VK_LINK, trim(importedConfig.link));
        editor.putString(
            KEY_THREADS,
            String.valueOf(importedConfig.threads != null && importedConfig.threads > 0 ? importedConfig.threads : 8)
        );
        editor.putBoolean(KEY_USE_UDP, importedConfig.useUdp == null || importedConfig.useUdp);
        editor.putBoolean(KEY_NO_OBFUSCATION, importedConfig.noObfuscation != null && importedConfig.noObfuscation);
        editor.putBoolean(KEY_MANUAL_CAPTCHA, false);
        editor.putString(KEY_TURN_SESSION_MODE, normalizeTurnSessionMode(importedConfig.turnSessionMode));
        editor.putString(
            KEY_LOCAL_ENDPOINT,
            TextUtils.isEmpty(trim(importedConfig.localEndpoint))
                ? "127.0.0.1:9000"
                : trim(importedConfig.localEndpoint)
        );
        editor.putString(KEY_TURN_HOST, trim(importedConfig.turnHost));
        editor.putString(KEY_TURN_PORT, trim(importedConfig.turnPort));
    }

    private static void applyImportedWireGuardSettings(
        SharedPreferences.Editor editor,
        WingsImportParser.ImportedConfig importedConfig,
        BackendType backendType
    ) {
        String wireGuardEndpoint = trim(importedConfig.wgEndpoint);
        if (TextUtils.isEmpty(wireGuardEndpoint) && backendType == BackendType.WIREGUARD) {
            wireGuardEndpoint = trim(importedConfig.endpoint);
        }
        editor.putString(KEY_WG_ENDPOINT, wireGuardEndpoint);
        editor.putString(KEY_WG_PRIVATE_KEY, trim(importedConfig.wgPrivateKey));
        editor.putString(KEY_WG_ADDRESSES, trim(importedConfig.wgAddresses));
        editor.putString(
            KEY_WG_DNS,
            TextUtils.isEmpty(trim(importedConfig.wgDns)) ? "1.1.1.1, 1.0.0.1" : trim(importedConfig.wgDns)
        );
        editor.putString(
            KEY_WG_MTU,
            String.valueOf(importedConfig.wgMtu != null && importedConfig.wgMtu > 0 ? importedConfig.wgMtu : 1280)
        );
        editor.putString(KEY_WG_PUBLIC_KEY, trim(importedConfig.wgPublicKey));
        editor.putString(KEY_WG_PRESHARED_KEY, trim(importedConfig.wgPresharedKey));
        editor.putString(
            KEY_WG_ALLOWED_IPS,
            TextUtils.isEmpty(trim(importedConfig.wgAllowedIps)) ? "0.0.0.0/0, ::/0" : trim(importedConfig.wgAllowedIps)
        );
    }

    private static void applyImportedXraySettings(Context context, WingsImportParser.ImportedConfig importedConfig) {
        java.util.List<XrayProfile> resolvedImportedProfiles = resolveImportedXrayProfiles(importedConfig);
        XrayStore.setXraySettings(context, importedConfig.xraySettings);
        XrayStore.setSubscriptions(context, importedConfig.xraySubscriptions);
        if (importedConfig.hasXrayProfiles) {
            XrayStore.setProfiles(context, resolvedImportedProfiles);
        }
        String importedActiveProfileId = trim(importedConfig.activeXrayProfileId);
        if (
            TextUtils.isEmpty(importedActiveProfileId) &&
            importedConfig.hasXrayProfiles &&
            !resolvedImportedProfiles.isEmpty()
        ) {
            importedActiveProfileId = resolvedImportedProfiles.get(0).id;
        }
        if (!TextUtils.isEmpty(importedActiveProfileId) || importedConfig.hasXrayProfiles) {
            XrayStore.setActiveProfileId(context, importedActiveProfileId);
        }
        if (importedConfig.hasXraySubscriptionJson) {
            XrayStore.setImportedSubscriptionJson(context, importedConfig.xraySubscriptionJson);
        }
        XrayStore.setLastSubscriptionsError(context, "");
    }

    public static void applyVkTurnSettings(Context context, ProxySettings settings) {
        if (settings == null) {
            return;
        }
        prefs(context)
            .edit()
            .putString(KEY_ENDPOINT, trim(settings.endpoint))
            .putString(KEY_VK_LINK, trim(settings.vkLink))
            .putString(KEY_THREADS, String.valueOf(settings.threads > 0 ? settings.threads : 8))
            .putBoolean(KEY_USE_UDP, settings.useUdp)
            .putBoolean(KEY_NO_OBFUSCATION, settings.noObfuscation)
            .putBoolean(KEY_MANUAL_CAPTCHA, settings.manualCaptcha)
            .putString(KEY_TURN_SESSION_MODE, normalizeTurnSessionMode(settings.turnSessionMode))
            .putString(
                KEY_LOCAL_ENDPOINT,
                TextUtils.isEmpty(trim(settings.localEndpoint)) ? "127.0.0.1:9000" : trim(settings.localEndpoint)
            )
            .putString(KEY_TURN_HOST, trim(settings.turnHost))
            .putString(KEY_TURN_PORT, trim(settings.turnPort))
            .putString(KEY_WG_PRIVATE_KEY, trim(settings.wgPrivateKey))
            .putString(KEY_WG_ADDRESSES, trim(settings.wgAddresses))
            .putString(KEY_WG_DNS, TextUtils.isEmpty(trim(settings.wgDns)) ? "1.1.1.1, 1.0.0.1" : trim(settings.wgDns))
            .putString(KEY_WG_MTU, String.valueOf(settings.wgMtu > 0 ? settings.wgMtu : 1280))
            .putString(KEY_WG_PUBLIC_KEY, trim(settings.wgPublicKey))
            .putString(KEY_WG_PRESHARED_KEY, trim(settings.wgPresharedKey))
            .putString(
                KEY_WG_ALLOWED_IPS,
                TextUtils.isEmpty(trim(settings.wgAllowedIps)) ? "0.0.0.0/0, ::/0" : trim(settings.wgAllowedIps)
            )
            .apply();
        XrayStore.setBackendType(context, BackendType.VK_TURN_WIREGUARD);
    }

    public static String getTurnEndpoint(Context context) {
        return trim(prefs(context).getString(KEY_ENDPOINT, ""));
    }

    public static String getWireGuardEndpoint(Context context) {
        return trim(prefs(context).getString(KEY_WG_ENDPOINT, ""));
    }

    public static String resolveEndpointForBackend(Context context, BackendType backendType) {
        if (backendType == BackendType.WIREGUARD) {
            return getWireGuardEndpoint(context);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            return AmneziaStore.getConfiguredEndpoint(context);
        }
        return getTurnEndpoint(context);
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static void mergeImportedXrayPayload(Context context, WingsImportParser.ImportedConfig importedConfig) {
        java.util.List<XraySubscription> currentSubscriptions = XrayStore.getSubscriptions(context);
        java.util.Map<String, XraySubscription> subscriptionsByKey = new java.util.LinkedHashMap<>();
        for (XraySubscription subscription : currentSubscriptions) {
            if (subscription != null && !TextUtils.isEmpty(subscription.url)) {
                subscriptionsByKey.put(subscription.stableDedupKey(), subscription);
            }
        }

        java.util.Map<String, String> importedSubscriptionIdsToMergedIds = new java.util.LinkedHashMap<>();
        for (XraySubscription importedSubscription : importedConfig.xraySubscriptions) {
            if (importedSubscription == null || TextUtils.isEmpty(importedSubscription.url)) {
                continue;
            }
            String dedupKey = importedSubscription.stableDedupKey();
            XraySubscription existing = subscriptionsByKey.get(dedupKey);
            XraySubscription merged =
                existing == null
                    ? importedSubscription
                    : new XraySubscription(
                          existing.id,
                          trim(importedSubscription.title),
                          trim(importedSubscription.url),
                          trim(importedSubscription.formatHint),
                          importedSubscription.refreshIntervalMinutes,
                          importedSubscription.autoUpdate,
                          importedSubscription.lastUpdatedAt,
                          importedSubscription.advertisedUploadBytes,
                          importedSubscription.advertisedDownloadBytes,
                          importedSubscription.advertisedTotalBytes,
                          importedSubscription.advertisedExpireAt
                      );
            subscriptionsByKey.put(dedupKey, merged);
            if (!TextUtils.isEmpty(importedSubscription.id)) {
                importedSubscriptionIdsToMergedIds.put(importedSubscription.id, merged.id);
            }
        }

        java.util.List<XraySubscription> mergedSubscriptions = new java.util.ArrayList<>(subscriptionsByKey.values());
        XrayStore.setSubscriptions(context, mergedSubscriptions);

        java.util.Map<String, XraySubscription> mergedSubscriptionsById = new java.util.LinkedHashMap<>();
        for (XraySubscription subscription : mergedSubscriptions) {
            if (subscription != null && !TextUtils.isEmpty(subscription.id)) {
                mergedSubscriptionsById.put(subscription.id, subscription);
            }
        }

        java.util.List<XrayProfile> currentProfiles = XrayStore.getProfiles(context);
        java.util.Map<String, XrayProfile> profilesByKey = new java.util.LinkedHashMap<>();
        for (XrayProfile profile : currentProfiles) {
            if (profile != null && !TextUtils.isEmpty(profile.rawLink)) {
                profilesByKey.put(profile.stableDedupKey(), profile);
            }
        }

        java.util.Map<String, String> importedProfileIdsToMergedIds = new java.util.LinkedHashMap<>();
        for (XrayProfile importedProfile : resolveImportedXrayProfiles(importedConfig)) {
            if (importedProfile == null || TextUtils.isEmpty(importedProfile.rawLink)) {
                continue;
            }
            String mergedSubscriptionId = trim(importedProfile.subscriptionId);
            if (!TextUtils.isEmpty(mergedSubscriptionId)) {
                mergedSubscriptionId = importedSubscriptionIdsToMergedIds.containsKey(mergedSubscriptionId)
                    ? importedSubscriptionIdsToMergedIds.get(mergedSubscriptionId)
                    : mergedSubscriptionId;
            }
            String mergedSubscriptionTitle = trim(importedProfile.subscriptionTitle);
            if (!TextUtils.isEmpty(mergedSubscriptionId)) {
                XraySubscription mergedSubscription = mergedSubscriptionsById.get(mergedSubscriptionId);
                if (mergedSubscription != null && !TextUtils.isEmpty(mergedSubscription.title)) {
                    mergedSubscriptionTitle = mergedSubscription.title;
                }
            }
            XrayProfile normalizedImportedProfile = new XrayProfile(
                importedProfile.id,
                importedProfile.title,
                importedProfile.rawLink,
                mergedSubscriptionId,
                mergedSubscriptionTitle,
                importedProfile.address,
                importedProfile.port
            );
            String dedupKey = normalizedImportedProfile.stableDedupKey();
            XrayProfile existing = profilesByKey.get(dedupKey);
            XrayProfile merged =
                existing == null
                    ? normalizedImportedProfile
                    : new XrayProfile(
                          existing.id,
                          normalizedImportedProfile.title,
                          normalizedImportedProfile.rawLink,
                          normalizedImportedProfile.subscriptionId,
                          normalizedImportedProfile.subscriptionTitle,
                          normalizedImportedProfile.address,
                          normalizedImportedProfile.port
                      );
            profilesByKey.put(dedupKey, merged);
            if (!TextUtils.isEmpty(importedProfile.id)) {
                importedProfileIdsToMergedIds.put(importedProfile.id, merged.id);
            }
        }

        java.util.List<XrayProfile> mergedProfiles = new java.util.ArrayList<>(profilesByKey.values());
        XrayStore.setProfiles(context, mergedProfiles);
        String mergedActiveProfileId = TextUtils.isEmpty(importedConfig.activeXrayProfileId)
            ? ""
            : importedProfileIdsToMergedIds.get(importedConfig.activeXrayProfileId);
        if (TextUtils.isEmpty(mergedActiveProfileId)) {
            String currentActiveProfileId = XrayStore.getActiveProfileId(context);
            if (TextUtils.isEmpty(currentActiveProfileId)) {
                if (!TextUtils.isEmpty(importedConfig.activeXrayProfileId)) {
                    XrayStore.setActiveProfileId(context, importedConfig.activeXrayProfileId);
                } else if (!mergedProfiles.isEmpty()) {
                    XrayStore.setActiveProfileId(context, mergedProfiles.get(0).id);
                }
            }
        } else {
            XrayStore.setActiveProfileId(context, mergedActiveProfileId);
        }
        XrayStore.setImportedSubscriptionJson(context, importedConfig.xraySubscriptionJson);
        XrayStore.setLastSubscriptionsError(context, "");
    }

    private static boolean shouldActivateImportedXrayPayload(WingsImportParser.ImportedConfig importedConfig) {
        return (
            importedConfig != null &&
            (!resolveImportedXrayProfiles(importedConfig).isEmpty() ||
                !TextUtils.isEmpty(importedConfig.activeXrayProfileId))
        );
    }

    private static java.util.List<XrayProfile> resolveImportedXrayProfiles(
        WingsImportParser.ImportedConfig importedConfig
    ) {
        java.util.ArrayList<XrayProfile> resolvedProfiles = new java.util.ArrayList<>();
        if (importedConfig == null) {
            return resolvedProfiles;
        }
        if (!importedConfig.xrayProfiles.isEmpty()) {
            resolvedProfiles.addAll(importedConfig.xrayProfiles);
            return resolvedProfiles;
        }
        if (TextUtils.isEmpty(importedConfig.xraySubscriptionJson)) {
            return resolvedProfiles;
        }
        XraySubscription primarySubscription = importedConfig.xraySubscriptions.isEmpty()
            ? null
            : importedConfig.xraySubscriptions.get(0);
        resolvedProfiles.addAll(
            XraySubscriptionParser.parseProfiles(
                importedConfig.xraySubscriptionJson,
                primarySubscription != null ? trim(primarySubscription.id) : "",
                primarySubscription != null ? trim(primarySubscription.title) : ""
            )
        );
        return resolvedProfiles;
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    private static int parseInt(String rawValue, int fallback) {
        try {
            return Integer.parseInt(trim(rawValue));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String normalizeTurnSessionMode(String value) {
        String normalized = trim(value);
        if (TextUtils.isEmpty(normalized) || TURN_SESSION_MODE_AUTO.equals(normalized)) {
            return TURN_SESSION_MODE_AUTO;
        }
        if (TURN_SESSION_MODE_MUX.equals(normalized)) {
            return TURN_SESSION_MODE_MUX;
        }
        if (TURN_SESSION_MODE_MAINLINE.equals(normalized)) {
            return TURN_SESSION_MODE_MAINLINE;
        }
        return TURN_SESSION_MODE_AUTO;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
