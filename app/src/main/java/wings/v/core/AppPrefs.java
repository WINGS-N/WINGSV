package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
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

    private static final String RUNTIME_PREFS_NAME = "wingsv_runtime_state";
    private static final String TURN_SESSION_MODE_AUTO = "auto";
    private static final String TURN_SESSION_MODE_MAINLINE = "mainline";
    private static final String TURN_SESSION_MODE_MU = "mu";
    private static final String DEFAULT_ROOT_WIREGUARD_INTERFACE_NAME = "wingsv-{{hex}}";
    private static final int MAX_INTERFACE_NAME_LENGTH = 15;
    private static final String HEX_PLACEHOLDER = "{{hex}}";
    private static final Pattern INTERFACE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+");

    public static final String KEY_ENDPOINT = "pref_endpoint";
    public static final String KEY_VK_LINK = "pref_vk_link";
    public static final String KEY_VK_LINKS_JSON = "pref_vk_links_json";
    public static final String KEY_VK_LINK_SECONDARY = "pref_vk_link_secondary";
    public static final String KEY_VK_OAUTH_ACCESS_TOKEN = "pref_vk_oauth_access_token";
    public static final String KEY_VK_OAUTH_EXPIRES_AT_SECONDS = "pref_vk_oauth_expires_at_seconds";
    public static final String KEY_VK_OAUTH_USER_ID = "pref_vk_oauth_user_id";
    public static final String KEY_VK_OAUTH_PENDING_STATE = "pref_vk_oauth_pending_state";
    public static final String KEY_OPEN_VK_LINKS = "pref_open_vk_links";
    public static final String KEY_THREADS = "pref_threads";
    public static final String KEY_CREDS_GROUP_SIZE = "pref_creds_group_size";
    public static final String KEY_USE_UDP = "pref_use_udp";
    public static final String KEY_NO_OBFUSCATION = "pref_no_obfuscation";
    public static final String KEY_MANUAL_CAPTCHA = "pref_manual_captcha";
    public static final String KEY_CAPTCHA_AUTO_SOLVER = "pref_captcha_auto_solver";
    public static final String CAPTCHA_AUTO_SOLVER_DEFAULT = "v2";
    public static final String KEY_VK_TURN_RESTART_ON_NETWORK_CHANGE = "pref_vk_turn_restart_on_network_change";
    public static final String KEY_VK_TURN_RUNTIME_MODE = "pref_vk_turn_runtime_mode";
    public static final String KEY_VK_TURN_USER_DNS = "pref_vk_turn_user_dns";
    public static final String KEY_VK_TURN_WRAP_MODE = "pref_vk_turn_wrap_mode";
    public static final String KEY_VK_TURN_WRAP_CIPHER = "pref_vk_turn_wrap_cipher";
    public static final String KEY_VK_TURN_WRAP_KEY_HEX = "pref_vk_turn_wrap_key_hex";
    public static final String KEY_VK_TURN_WRAP_GENERATE = "pref_vk_turn_wrap_generate";
    public static final String KEY_VK_TURN_WRAP_SEND_KEY = "pref_vk_turn_wrap_send_key";
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
    public static final String KEY_WB_STREAM_ROOM_ID = "pref_wb_stream_room_id";
    public static final String KEY_WB_STREAM_DISPLAY_NAME = "pref_wb_stream_display_name";
    public static final String KEY_WB_STREAM_EXCHANGE_VIA_VK_TURN = "pref_wb_stream_exchange_via_vk_turn";
    public static final String KEY_WB_STREAM_E2E_ENABLED = "pref_wb_stream_e2e_enabled";
    public static final String KEY_WB_STREAM_E2E_SECRET = "pref_wb_stream_e2e_secret";
    public static final String KEY_WB_STREAM_ROOM_COUNT = "pref_wb_stream_room_count";
    public static final int DEFAULT_WB_STREAM_ROOM_COUNT = 1;
    public static final int MAX_WB_STREAM_ROOM_COUNT = 16;
    public static final String KEY_OPEN_WB_STREAM_SETTINGS = "pref_open_wb_stream_settings";
    public static final String KEY_BACKEND_TYPE = "pref_backend_type";
    /** UI-only top-level выбор backend'а; pref_backend_type вычисляется из него + sub-backend. */
    public static final String KEY_BACKEND_TOP = "pref_backend_top";
    /** Под-backend для top-level VK TURN: "wireguard" | "amneziawg". */
    public static final String KEY_VK_TURN_TUNNEL_MODE = "pref_vk_turn_tunnel_mode";
    /** Под-backend для top-level WB Stream: "wireguard" | "amneziawg". */
    public static final String KEY_WB_STREAM_TUNNEL_MODE = "pref_wb_stream_tunnel_mode";
    public static final String KEY_PREFS_SCHEMA_VERSION = "pref_prefs_schema_version";
    public static final int CURRENT_PREFS_SCHEMA_VERSION = 1;
    /** Версия последнего применённого guardian-конфига; отправляется в ClientHello и
     *  переписывается на каждом успешном applyConfigPush. */
    public static final String KEY_GUARDIAN_LAST_APPLIED_CONFIG_VERSION = "pref_guardian_last_applied_config_version";
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
    public static final String KEY_XRAY_LOCAL_PROXY_LISTEN_ADDRESS = "pref_xray_local_proxy_listen_address";
    public static final String KEY_XRAY_HTTP_PROXY_ENABLED = "pref_xray_http_proxy_enabled";
    public static final String KEY_XRAY_HTTP_PROXY_AUTH_ENABLED = "pref_xray_http_proxy_auth_enabled";
    public static final String KEY_XRAY_HTTP_PROXY_USERNAME = "pref_xray_http_proxy_username";
    public static final String KEY_XRAY_HTTP_PROXY_PASSWORD = "pref_xray_http_proxy_password";
    public static final String KEY_XRAY_HTTP_PROXY_PORT = "pref_xray_http_proxy_port";
    public static final String KEY_XRAY_HTTP_PROXY_LISTEN_ADDRESS = "pref_xray_http_proxy_listen_address";
    public static final String KEY_XRAY_WAKE_PROBE_MODE = "pref_xray_wake_probe_mode";
    public static final String KEY_XRAY_REMOTE_DNS = "pref_xray_remote_dns";
    public static final String KEY_XRAY_DIRECT_DNS = "pref_xray_direct_dns";
    public static final String KEY_XRAY_IPV6_ENABLED = "pref_xray_ipv6_enabled";
    public static final String KEY_XRAY_SNIFFING_ENABLED = "pref_xray_sniffing_enabled";
    public static final String KEY_XRAY_TUN_UID_LOOKUP_TIMEOUT_MS = "pref_xray_tun_uid_lookup_timeout_ms";
    public static final String KEY_XRAY_PROXY_QUIC_ENABLED = "pref_xray_proxy_quic_enabled";
    public static final String KEY_XRAY_RESTART_ON_NETWORK_CHANGE = "pref_xray_restart_on_network_change";
    public static final String KEY_XRAY_RUNTIME_MODE = "pref_xray_runtime_mode";
    public static final String KEY_XRAY_TRANSPORT_MODE = "pref_xray_transport_mode";
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
    public static final String KEY_XRAY_ACTIVE_PROFILE_RAW_LINK = "pref_xray_active_profile_raw_link";
    public static final String KEY_XRAY_SUBSCRIPTIONS_REFRESH_HOURS = "pref_xray_subscriptions_refresh_hours";
    public static final String KEY_XRAY_SUBSCRIPTIONS_REFRESH_MINUTES = "pref_xray_subscriptions_refresh_minutes";
    public static final String KEY_XRAY_SUBSCRIPTIONS_AUTO_REFRESH_ENABLED =
        "pref_xray_subscriptions_auto_refresh_enabled";
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
    public static final String KEY_APP_ROUTING_RECOMMENDED_DISMISSED = "pref_app_routing_recommended_dismissed";
    public static final String KEY_ROOT_MODE = "pref_root_mode";
    public static final String KEY_KERNEL_WIREGUARD = "pref_kernel_wireguard";
    public static final String KEY_XRAY_TPROXY_MODE = "pref_xray_tproxy_mode";
    public static final String KEY_ROOT_ACCESS_GRANTED = "pref_root_access_granted";
    public static final String KEY_ROOT_ACCESS_CHECKED_AT = "pref_root_access_checked_at";
    public static final String KEY_ROOT_SU_PATH = "pref_root_su_path";
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
    public static final String DNS_MODE_AUTO = "auto";
    public static final String DNS_MODE_UDP = "udp";
    public static final String DNS_MODE_DOH = "doh";
    public static final String KEY_DNS_MODE = "pref_dns_mode";
    public static final String KEY_GUARDIAN_ENABLED = "pref_guardian_enabled";
    public static final String KEY_GUARDIAN_AUTO_START_ON_BOOT = "pref_guardian_auto_start_on_boot";
    public static final String KEY_GUARDIAN_WS_URL = "pref_guardian_ws_url";
    public static final String KEY_GUARDIAN_CLIENT_ID = "pref_guardian_client_id";
    public static final String KEY_GUARDIAN_CLIENT_TOKEN_B64 = "pref_guardian_client_token_b64";
    public static final String KEY_GUARDIAN_CLIENT_NAME = "pref_guardian_client_name";
    public static final String KEY_GUARDIAN_LOG_RUNTIME_ALLOWED = "pref_guardian_log_runtime_allowed";
    public static final String KEY_GUARDIAN_LOG_PROXY_ALLOWED = "pref_guardian_log_proxy_allowed";
    public static final String KEY_GUARDIAN_LOG_XRAY_ALLOWED = "pref_guardian_log_xray_allowed";
    public static final String KEY_GUARDIAN_SYNC_MODE = "pref_guardian_sync_mode";
    public static final String KEY_GUARDIAN_PERIODIC_MINUTES = "pref_guardian_periodic_minutes";

    public static final String GUARDIAN_SYNC_MODE_ALWAYS = "always";
    public static final String GUARDIAN_SYNC_MODE_PERIODIC = "periodic";
    public static final String GUARDIAN_SYNC_MODE_FOREGROUND_ONLY = "foreground";

    public static final int GUARDIAN_PERIODIC_DEFAULT_MINUTES = 30;
    public static final int GUARDIAN_PERIODIC_MIN_MINUTES = 15;

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
        return runtimePrefs(context).getBoolean(KEY_EXTERNAL_ACTION_TRANSIENT_LAUNCH, false);
    }

    public static void setExternalActionTransientLaunchPending(Context context, boolean pending) {
        runtimePrefs(context).edit().putBoolean(KEY_EXTERNAL_ACTION_TRANSIENT_LAUNCH, pending).commit();
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
        prefs(context).edit().putBoolean(KEY_ROOT_MODE, enabled).commit();
    }

    public static boolean isKernelWireGuardEnabled(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.contains(KEY_KERNEL_WIREGUARD)) {
            return preferences.getBoolean(KEY_KERNEL_WIREGUARD, false);
        }
        return preferences.getBoolean(KEY_ROOT_MODE, false);
    }

    public static boolean isXrayTproxyModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_XRAY_TPROXY_MODE, false);
    }

    public static void setXrayTproxyModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_XRAY_TPROXY_MODE, enabled).commit();
    }

    public static void setKernelWireGuardEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KERNEL_WIREGUARD, enabled).commit();
    }

    public static String getThemeMode(Context context) {
        return normalizeThemeMode(prefs(context).getString(KEY_THEME_MODE, THEME_MODE_SYSTEM));
    }

    public static void setThemeMode(Context context, String value) {
        prefs(context).edit().putString(KEY_THEME_MODE, normalizeThemeMode(value)).apply();
    }

    public static String getDnsMode(Context context) {
        return normalizeDnsMode(prefs(context).getString(KEY_DNS_MODE, DNS_MODE_AUTO));
    }

    public static void setDnsMode(Context context, String value) {
        prefs(context).edit().putString(KEY_DNS_MODE, normalizeDnsMode(value)).apply();
    }

    public static String normalizeDnsMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (DNS_MODE_UDP.equals(normalized) || DNS_MODE_DOH.equals(normalized)) {
            return normalized;
        }
        return DNS_MODE_AUTO;
    }

    public static boolean isGuardianEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GUARDIAN_ENABLED, false);
    }

    public static void setGuardianEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_GUARDIAN_ENABLED, value).apply();
    }

    public static boolean isGuardianAutoStartOnBootEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GUARDIAN_AUTO_START_ON_BOOT, true);
    }

    public static void setGuardianAutoStartOnBootEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_GUARDIAN_AUTO_START_ON_BOOT, value).apply();
    }

    public static String getGuardianSyncMode(Context context) {
        return normalizeGuardianSyncMode(prefs(context).getString(KEY_GUARDIAN_SYNC_MODE, GUARDIAN_SYNC_MODE_ALWAYS));
    }

    public static void setGuardianSyncMode(Context context, String value) {
        prefs(context).edit().putString(KEY_GUARDIAN_SYNC_MODE, normalizeGuardianSyncMode(value)).apply();
    }

    public static String normalizeGuardianSyncMode(String value) {
        if (value == null) return GUARDIAN_SYNC_MODE_ALWAYS;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (GUARDIAN_SYNC_MODE_PERIODIC.equals(normalized) || GUARDIAN_SYNC_MODE_FOREGROUND_ONLY.equals(normalized)) {
            return normalized;
        }
        return GUARDIAN_SYNC_MODE_ALWAYS;
    }

    public static int getGuardianPeriodicIntervalMinutes(Context context) {
        int v = prefs(context).getInt(KEY_GUARDIAN_PERIODIC_MINUTES, GUARDIAN_PERIODIC_DEFAULT_MINUTES);
        return Math.max(GUARDIAN_PERIODIC_MIN_MINUTES, v);
    }

    public static void setGuardianPeriodicIntervalMinutes(Context context, int minutes) {
        int normalized = Math.max(
            GUARDIAN_PERIODIC_MIN_MINUTES,
            minutes <= 0 ? GUARDIAN_PERIODIC_DEFAULT_MINUTES : minutes
        );
        prefs(context).edit().putInt(KEY_GUARDIAN_PERIODIC_MINUTES, normalized).apply();
    }

    public static String getGuardianWsUrl(Context context) {
        return prefs(context).getString(KEY_GUARDIAN_WS_URL, "");
    }

    public static String getGuardianClientId(Context context) {
        return prefs(context).getString(KEY_GUARDIAN_CLIENT_ID, "");
    }

    public static String getGuardianClientTokenB64(Context context) {
        return prefs(context).getString(KEY_GUARDIAN_CLIENT_TOKEN_B64, "");
    }

    public static String getGuardianClientName(Context context) {
        return prefs(context).getString(KEY_GUARDIAN_CLIENT_NAME, "");
    }

    public static boolean isGuardianConfigured(Context context) {
        return (
            !TextUtils.isEmpty(getGuardianWsUrl(context)) &&
            !TextUtils.isEmpty(getGuardianClientId(context)) &&
            !TextUtils.isEmpty(getGuardianClientTokenB64(context))
        );
    }

    public static void setGuardianCredentials(
        Context context,
        String wsUrl,
        String clientId,
        String clientTokenB64,
        String clientName
    ) {
        prefs(context)
            .edit()
            .putString(KEY_GUARDIAN_WS_URL, wsUrl == null ? "" : wsUrl.trim())
            .putString(KEY_GUARDIAN_CLIENT_ID, clientId == null ? "" : clientId.trim())
            .putString(KEY_GUARDIAN_CLIENT_TOKEN_B64, clientTokenB64 == null ? "" : clientTokenB64.trim())
            .putString(KEY_GUARDIAN_CLIENT_NAME, clientName == null ? "" : clientName.trim())
            .apply();
    }

    public static void clearGuardian(Context context) {
        prefs(context)
            .edit()
            .remove(KEY_GUARDIAN_WS_URL)
            .remove(KEY_GUARDIAN_CLIENT_ID)
            .remove(KEY_GUARDIAN_CLIENT_TOKEN_B64)
            .remove(KEY_GUARDIAN_CLIENT_NAME)
            .putBoolean(KEY_GUARDIAN_ENABLED, false)
            .apply();
    }

    public static boolean isGuardianLogRuntimeAllowed(Context context) {
        return prefs(context).getBoolean(KEY_GUARDIAN_LOG_RUNTIME_ALLOWED, true);
    }

    public static boolean isGuardianLogProxyAllowed(Context context) {
        return prefs(context).getBoolean(KEY_GUARDIAN_LOG_PROXY_ALLOWED, true);
    }

    public static boolean isGuardianLogXRayAllowed(Context context) {
        return prefs(context).getBoolean(KEY_GUARDIAN_LOG_XRAY_ALLOWED, false);
    }

    public static void setGuardianLogControl(Context context, boolean runtime, boolean proxy, boolean xray) {
        prefs(context)
            .edit()
            .putBoolean(KEY_GUARDIAN_LOG_RUNTIME_ALLOWED, runtime)
            .putBoolean(KEY_GUARDIAN_LOG_PROXY_ALLOWED, proxy)
            .putBoolean(KEY_GUARDIAN_LOG_XRAY_ALLOWED, xray)
            .apply();
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

    public static String getRootSuPath(Context context) {
        return trim(prefs(context).getString(KEY_ROOT_SU_PATH, ""));
    }

    public static void setRootSuPath(Context context, String path) {
        prefs(context).edit().putString(KEY_ROOT_SU_PATH, trim(path)).apply();
    }

    public static boolean hasRootRuntimeState(Context context) {
        return (
            runtimePrefs(context).getBoolean(KEY_ROOT_RUNTIME_ACTIVE, false) &&
            !TextUtils.isEmpty(getRootRuntimeTunnelName(context))
        );
    }

    public static String getRootRuntimeTunnelName(Context context) {
        return trim(runtimePrefs(context).getString(KEY_ROOT_RUNTIME_TUNNEL, ""));
    }

    public static long getRootRuntimeProxyPid(Context context) {
        return runtimePrefs(context).getLong(KEY_ROOT_RUNTIME_PROXY_PID, 0L);
    }

    public static void setRootRuntimeState(Context context, String tunnelName, long proxyPid) {
        String normalizedTunnelName = trim(tunnelName);
        boolean active = !TextUtils.isEmpty(normalizedTunnelName);
        runtimePrefs(context)
            .edit()
            .putBoolean(KEY_ROOT_RUNTIME_ACTIVE, active)
            .putString(KEY_ROOT_RUNTIME_TUNNEL, normalizedTunnelName)
            .putLong(KEY_ROOT_RUNTIME_PROXY_PID, Math.max(proxyPid, 0L))
            .apply();
    }

    public static void clearRootRuntimeState(Context context) {
        runtimePrefs(context)
            .edit()
            .remove(KEY_ROOT_RUNTIME_ACTIVE)
            .remove(KEY_ROOT_RUNTIME_TUNNEL)
            .remove(KEY_ROOT_RUNTIME_PROXY_PID)
            .apply();
    }

    public static String getRuntimeUpstreamInterface(Context context) {
        return trim(runtimePrefs(context).getString(KEY_RUNTIME_UPSTREAM_INTERFACE, ""));
    }

    public static String getRuntimeUpstreamRootDns(Context context) {
        return trim(runtimePrefs(context).getString(KEY_RUNTIME_UPSTREAM_ROOT_DNS, ""));
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
        runtimePrefs(context)
            .edit()
            .remove(KEY_RUNTIME_UPSTREAM_INTERFACE)
            .remove(KEY_RUNTIME_UPSTREAM_ROOT_DNS)
            .apply();
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
        prefs(context).edit().putBoolean(KEY_APP_ROUTING_BYPASS, enabled).commit();
    }

    public static Set<String> getAppRoutingPackages(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_APP_ROUTING_PACKAGES, null);
        if (stored == null || stored.isEmpty()) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<String> packages = new LinkedHashSet<>(stored);
        packages.remove(context.getPackageName());
        return packages;
    }

    /**
     * Returns the bypass/allowlist set every routing backend should actually
     * enforce. Differs from {@link #getAppRoutingPackages(Context)} when the
     * Xposed "hide VPN status" feature is enabled: apps that are lied to
     * about the VPN being absent must also be physically kept out of the
     * tunnel, otherwise the app sees "no VPN" yet its packets still go via
     * the tunnel and outbound connections silently fail.
     *
     * In bypass mode (the default) the hidden-VPN list is merged into the
     * user-managed bypass set. In allowlist mode the hidden-VPN packages are
     * subtracted from the allowlist so they are excluded from the tunnel.
     * The user's own package is always removed from the result.
     */
    public static Set<String> getEffectiveAppRoutingPackages(Context context) {
        LinkedHashSet<String> packages = new LinkedHashSet<>(getAppRoutingPackages(context));
        Set<String> hiddenVpnPackages = effectiveHiddenVpnPackages(context);
        if (!hiddenVpnPackages.isEmpty()) {
            if (isAppRoutingBypassEnabled(context)) {
                packages.addAll(hiddenVpnPackages);
            } else {
                packages.removeAll(hiddenVpnPackages);
            }
        }
        packages.remove(context.getPackageName());
        return packages;
    }

    private static Set<String> effectiveHiddenVpnPackages(Context context) {
        SharedPreferences xposedPrefs = XposedModulePrefs.prefs(context);
        boolean moduleEnabled = xposedPrefs.getBoolean(
            XposedModulePrefs.KEY_ENABLED,
            XposedModulePrefs.DEFAULT_ENABLED
        );
        boolean hideEnabled = xposedPrefs.getBoolean(
            XposedModulePrefs.KEY_HIDE_VPN_APPS,
            XposedModulePrefs.DEFAULT_HIDE_VPN_APPS
        );
        if (!moduleEnabled || !hideEnabled) {
            return java.util.Collections.emptySet();
        }
        Set<String> hidden = XposedModulePrefs.getHiddenVpnPackages(context);
        if (hidden == null || hidden.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        return hidden;
    }

    public static void setAppRoutingPackageEnabled(Context context, String packageName, boolean enabled) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        Set<String> packages = getAppRoutingPackages(context);
        if (enabled && !TextUtils.equals(packageName, context.getPackageName())) {
            packages.add(packageName);
        } else {
            packages.remove(packageName);
        }
        prefs(context).edit().putStringSet(KEY_APP_ROUTING_PACKAGES, new LinkedHashSet<>(packages)).commit();
    }

    public static void setAppRoutingPackages(Context context, Set<String> packages) {
        LinkedHashSet<String> normalizedPackages = new LinkedHashSet<>();
        if (packages != null) {
            for (String packageName : packages) {
                String normalizedPackageName = trim(packageName);
                if (
                    !TextUtils.isEmpty(normalizedPackageName) &&
                    !TextUtils.equals(normalizedPackageName, context.getPackageName())
                ) {
                    normalizedPackages.add(normalizedPackageName);
                }
            }
        }
        prefs(context).edit().putStringSet(KEY_APP_ROUTING_PACKAGES, normalizedPackages).commit();
    }

    public static Set<String> getAppRoutingRecommendedDismissedPackages(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_APP_ROUTING_RECOMMENDED_DISMISSED, null);
        if (stored == null || stored.isEmpty()) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<String> packages = new LinkedHashSet<>(stored);
        packages.remove(context.getPackageName());
        return packages;
    }

    public static void setAppRoutingRecommendedPackageDismissed(
        Context context,
        String packageName,
        boolean dismissed
    ) {
        String normalizedPackageName = trim(packageName);
        if (
            TextUtils.isEmpty(normalizedPackageName) ||
            TextUtils.equals(normalizedPackageName, context.getPackageName())
        ) {
            return;
        }
        Set<String> packages = getAppRoutingRecommendedDismissedPackages(context);
        if (dismissed) {
            packages.add(normalizedPackageName);
        } else {
            packages.remove(normalizedPackageName);
        }
        prefs(context)
            .edit()
            .putStringSet(KEY_APP_ROUTING_RECOMMENDED_DISMISSED, new LinkedHashSet<>(packages))
            .commit();
    }

    public static boolean maybeAutoEnableRecommendedAppRoutingPackage(Context context, String packageName) {
        String normalizedPackageName = trim(packageName);
        if (
            TextUtils.isEmpty(normalizedPackageName) ||
            TextUtils.equals(normalizedPackageName, context.getPackageName()) ||
            !isAppRoutingBypassEnabled(context)
        ) {
            return false;
        }
        if (!RuStoreRecommendedAppsAsset.getApps(context).containsKey(normalizedPackageName)) {
            return false;
        }
        Set<String> dismissedPackages = getAppRoutingRecommendedDismissedPackages(context);
        if (dismissedPackages.contains(normalizedPackageName)) {
            return false;
        }
        Set<String> enabledPackages = getAppRoutingPackages(context);
        if (enabledPackages.contains(normalizedPackageName)) {
            return false;
        }
        enabledPackages.add(normalizedPackageName);
        prefs(context).edit().putStringSet(KEY_APP_ROUTING_PACKAGES, new LinkedHashSet<>(enabledPackages)).commit();
        return true;
    }

    public static boolean syncRecommendedAppRoutingPackages(Context context, Set<String> installedPackages) {
        if (!isAppRoutingBypassEnabled(context) || installedPackages == null || installedPackages.isEmpty()) {
            return false;
        }
        Set<String> dismissedPackages = getAppRoutingRecommendedDismissedPackages(context);
        Set<String> recommendedPackages = RuStoreRecommendedAppsAsset.getPackageNames(context);
        if (recommendedPackages.isEmpty()) {
            return false;
        }
        Set<String> enabledPackages = getAppRoutingPackages(context);
        boolean changed = false;
        for (String packageName : installedPackages) {
            String normalizedPackageName = trim(packageName);
            if (
                TextUtils.isEmpty(normalizedPackageName) ||
                TextUtils.equals(normalizedPackageName, context.getPackageName()) ||
                !recommendedPackages.contains(normalizedPackageName) ||
                dismissedPackages.contains(normalizedPackageName) ||
                enabledPackages.contains(normalizedPackageName)
            ) {
                continue;
            }
            enabledPackages.add(normalizedPackageName);
            changed = true;
        }
        if (changed) {
            prefs(context).edit().putStringSet(KEY_APP_ROUTING_PACKAGES, new LinkedHashSet<>(enabledPackages)).commit();
        }
        return changed;
    }

    public static ProxySettings getSettings(Context context) {
        SharedPreferences prefs = prefs(context);
        ProxySettings settings = new ProxySettings();
        settings.backendType = XrayStore.getBackendType(context);
        settings.endpoint = resolveEndpointForBackend(context, settings.backendType);
        settings.vkLink = trim(prefs.getString(KEY_VK_LINK, ""));
        settings.vkLinks = readVkLinks(prefs, settings.vkLink);
        settings.vkLinkSecondary = trim(prefs.getString(KEY_VK_LINK_SECONDARY, ""));
        if (!settings.vkLinks.isEmpty()) {
            settings.vkLink = settings.vkLinks.get(0);
        }
        settings.threads = parseInt(prefs.getString(KEY_THREADS, "24"), 24);
        settings.credsGroupSize = parseInt(prefs.getString(KEY_CREDS_GROUP_SIZE, "12"), 12);
        settings.useUdp = prefs.getBoolean(KEY_USE_UDP, true);
        settings.noObfuscation = prefs.getBoolean(KEY_NO_OBFUSCATION, false);
        settings.manualCaptcha = prefs.getBoolean(KEY_MANUAL_CAPTCHA, false);
        settings.captchaAutoSolver = normalizeCaptchaAutoSolver(
            prefs.getString(KEY_CAPTCHA_AUTO_SOLVER, CAPTCHA_AUTO_SOLVER_DEFAULT)
        );
        settings.vkTurnRestartOnNetworkChange = prefs.getBoolean(KEY_VK_TURN_RESTART_ON_NETWORK_CHANGE, true);
        settings.vkTurnRuntimeMode = ProxyRuntimeMode.fromPrefValue(
            prefs.getString(KEY_VK_TURN_RUNTIME_MODE, ProxyRuntimeMode.VPN.prefValue)
        );
        settings.vkTurnUserDns = trim(prefs.getString(KEY_VK_TURN_USER_DNS, ""));
        settings.vkTurnWrapMode = normalizeWrapMode(prefs.getString(KEY_VK_TURN_WRAP_MODE, "preferred"));
        settings.vkTurnWrapCipher = normalizeWrapCipher(prefs.getString(KEY_VK_TURN_WRAP_CIPHER, "srtp-aes-gcm"));
        settings.vkTurnWrapKeyHex = trim(prefs.getString(KEY_VK_TURN_WRAP_KEY_HEX, ""));
        settings.vkTurnWrapSendKey = prefs.getBoolean(KEY_VK_TURN_WRAP_SEND_KEY, true);
        settings.turnSessionMode = normalizeTurnSessionMode(prefs.getString(KEY_TURN_SESSION_MODE, "mainline"));
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
        if (backendType.usesXrayCore() && importedConfig.xrayMergeOnly) {
            mergeImportedXrayPayload(context, importedConfig);
            ActiveProbingManager.clearRestoreBackend(context);
            if (shouldActivateImportedXrayPayload(importedConfig)) {
                XrayStore.setBackendType(
                    context,
                    importedConfig.backendType != null && importedConfig.backendType.usesXrayCore()
                        ? importedConfig.backendType
                        : BackendType.XRAY
                );
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
        if (importedConfig.hasWbStreamSettings) {
            editor.putString(KEY_WB_STREAM_ROOM_ID, trim(importedConfig.wbStreamRoomId));
            editor.putString(KEY_WB_STREAM_DISPLAY_NAME, trim(importedConfig.wbStreamDisplayName));
            editor.putBoolean(KEY_WB_STREAM_EXCHANGE_VIA_VK_TURN, importedConfig.wbStreamExchangeViaVkTurn);
            editor.putBoolean(KEY_WB_STREAM_E2E_ENABLED, importedConfig.wbStreamE2eEnabled);
            if (!TextUtils.isEmpty(importedConfig.wbStreamE2eSecret)) {
                editor.putString(KEY_WB_STREAM_E2E_SECRET, trim(importedConfig.wbStreamE2eSecret));
            }
            if (importedConfig.wbStreamRoomCount != null) {
                editor.putString(
                    KEY_WB_STREAM_ROOM_COUNT,
                    String.valueOf(clampWbStreamRoomCount(importedConfig.wbStreamRoomCount))
                );
            }
            if (importedConfig.wbStreamTunnelMode != null) {
                editor.putString(KEY_WB_STREAM_TUNNEL_MODE, importedConfig.wbStreamTunnelMode.prefValue);
            }
        }
        if (importedConfig.vkTurnTunnelMode != null) {
            editor.putString(KEY_VK_TURN_TUNNEL_MODE, importedConfig.vkTurnTunnelMode.prefValue);
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
        // Root/xposed/sharing/kernel-wg/xray-tproxy фичи доступны только когда у
        // нас реально есть рут. Раньше эти блоки писались в prefs безусловно,
        // и импорт с рутованного устройства на не-рутованное оживлял Sharing
        // и пытался поднять root-туннели, что вешало сервис.
        boolean rootAvailable = RootUtils.isRootAccessGranted(context);
        if (importedConfig.hasXposedSettings && rootAvailable) {
            applyImportedXposedSettings(context, importedConfig);
        }
        if (importedConfig.hasRootSettings && rootAvailable) {
            applyImportedRootSettings(context, importedConfig);
        }
        if (importedConfig.hasAppPreferences) {
            applyImportedAppPreferences(context, importedConfig);
        }
        if (importedConfig.hasGuardian) {
            applyImportedGuardian(context, importedConfig);
        }
        if (importedConfig.hasSubscriptionHwid) {
            applyImportedSubscriptionHwid(context, importedConfig);
        }
        if (importedConfig.hasSharingSettings && rootAvailable) {
            applyImportedSharingSettings(context, importedConfig);
        }
        if (!rootAvailable) {
            // Если в импорте root-флаги были True но рута нет, форсируем их в
            // False, иначе UI продолжает показывать Sharing-таб и Settings
            // открывает root-секцию для несуществующего рута.
            forceDisableRootDependentFlags(context);
        }
        if (importedConfig.hasByeDpiSettings) {
            applyImportedByeDpiSettings(context, importedConfig);
        }

        if (importedConfig.updateBackendType) {
            ActiveProbingManager.clearRestoreBackend(context);
            XrayStore.setBackendType(context, backendType);
        }
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
        String importedLink = trim(importedConfig.link);
        editor.putString(KEY_VK_LINK, importedLink);
        ArrayList<String> importedLinks = new ArrayList<>();
        if (importedConfig.links != null) {
            for (String entry : importedConfig.links) {
                String trimmed = trim(entry);
                if (!TextUtils.isEmpty(trimmed)) {
                    importedLinks.add(trimmed);
                }
            }
        }
        if (importedLinks.isEmpty() && !TextUtils.isEmpty(importedLink)) {
            importedLinks.add(importedLink);
        }
        editor.putString(KEY_VK_LINKS_JSON, encodeVkLinks(importedLinks));
        editor.putString(KEY_VK_LINK_SECONDARY, trim(importedConfig.linkSecondary));
        editor.putString(
            KEY_CREDS_GROUP_SIZE,
            String.valueOf(
                importedConfig.credsGroupSize != null && importedConfig.credsGroupSize > 0
                    ? importedConfig.credsGroupSize
                    : 12
            )
        );
        editor.putString(
            KEY_THREADS,
            String.valueOf(importedConfig.threads != null && importedConfig.threads > 0 ? importedConfig.threads : 24)
        );
        editor.putBoolean(KEY_USE_UDP, importedConfig.useUdp == null || importedConfig.useUdp);
        editor.putBoolean(KEY_NO_OBFUSCATION, importedConfig.noObfuscation != null && importedConfig.noObfuscation);
        editor.putBoolean(KEY_MANUAL_CAPTCHA, importedConfig.manualCaptcha != null && importedConfig.manualCaptcha);
        if (!TextUtils.isEmpty(importedConfig.captchaAutoSolver)) {
            editor.putString(KEY_CAPTCHA_AUTO_SOLVER, normalizeCaptchaAutoSolver(importedConfig.captchaAutoSolver));
        }
        editor.putBoolean(
            KEY_VK_TURN_RESTART_ON_NETWORK_CHANGE,
            importedConfig.vkTurnRestartOnNetworkChange == null || importedConfig.vkTurnRestartOnNetworkChange
        );
        if (importedConfig.vkTurnRuntimeMode != null) {
            editor.putString(KEY_VK_TURN_RUNTIME_MODE, importedConfig.vkTurnRuntimeMode.prefValue);
        }
        if (importedConfig.vkTurnUserDns != null) {
            editor.putString(KEY_VK_TURN_USER_DNS, trim(importedConfig.vkTurnUserDns));
        }
        if (importedConfig.vkTurnWrapMode != null) {
            editor.putString(KEY_VK_TURN_WRAP_MODE, normalizeWrapMode(importedConfig.vkTurnWrapMode));
        }
        if (importedConfig.vkTurnWrapCipher != null) {
            editor.putString(KEY_VK_TURN_WRAP_CIPHER, normalizeWrapCipher(importedConfig.vkTurnWrapCipher));
        }
        if (importedConfig.vkTurnWrapKeyHex != null) {
            editor.putString(KEY_VK_TURN_WRAP_KEY_HEX, trim(importedConfig.vkTurnWrapKeyHex));
        }
        if (importedConfig.vkTurnWrapSendKey != null) {
            editor.putBoolean(KEY_VK_TURN_WRAP_SEND_KEY, importedConfig.vkTurnWrapSendKey);
        }
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

    private static void forceDisableRootDependentFlags(Context context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_ROOT_MODE, false)
            .putBoolean(KEY_KERNEL_WIREGUARD, false)
            .putBoolean(KEY_XRAY_TPROXY_MODE, false)
            .apply();
    }

    private static void applyImportedRootSettings(Context context, WingsImportParser.ImportedConfig importedConfig) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (importedConfig.rootModeEnabled != null) {
            editor.putBoolean(KEY_ROOT_MODE, importedConfig.rootModeEnabled);
        }
        if (importedConfig.kernelWireguardEnabled != null) {
            editor.putBoolean(KEY_KERNEL_WIREGUARD, importedConfig.kernelWireguardEnabled);
        }
        if (importedConfig.xrayTproxyModeEnabled != null) {
            editor.putBoolean(KEY_XRAY_TPROXY_MODE, importedConfig.xrayTproxyModeEnabled);
        }
        if (!TextUtils.isEmpty(importedConfig.rootWireguardInterfaceName)) {
            String normalized = normalizeRootWireGuardInterfaceNameTemplate(importedConfig.rootWireguardInterfaceName);
            editor.putString(KEY_ROOT_WIREGUARD_INTERFACE_NAME, normalized);
        }
        editor.apply();
    }

    private static void applyImportedAppPreferences(Context context, WingsImportParser.ImportedConfig importedConfig) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (!TextUtils.isEmpty(importedConfig.themeMode)) {
            editor.putString(KEY_THEME_MODE, normalizeThemeMode(importedConfig.themeMode));
        }
        if (importedConfig.autoStartOnBoot != null) {
            editor.putBoolean(KEY_AUTO_START_ON_BOOT, importedConfig.autoStartOnBoot);
        }
        if (!TextUtils.isEmpty(importedConfig.dnsMode)) {
            editor.putString(KEY_DNS_MODE, normalizeDnsMode(importedConfig.dnsMode));
        }
        editor.apply();
    }

    private static void applyImportedGuardian(Context context, WingsImportParser.ImportedConfig importedConfig) {
        boolean hasCreds =
            !TextUtils.isEmpty(importedConfig.guardianWsUrl) &&
            !TextUtils.isEmpty(importedConfig.guardianClientId) &&
            importedConfig.guardianClientToken != null &&
            importedConfig.guardianClientToken.length > 0;
        boolean hasSyncOnly =
            !hasCreds &&
            (!TextUtils.isEmpty(importedConfig.guardianSyncMode) || importedConfig.guardianPeriodicIntervalMinutes > 0);
        if (!hasCreds && !hasSyncOnly) {
            return;
        }
        if (hasCreds) {
            String tokenB64 = android.util.Base64.encodeToString(
                importedConfig.guardianClientToken,
                android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE
            );
            setGuardianCredentials(
                context,
                importedConfig.guardianWsUrl,
                importedConfig.guardianClientId,
                tokenB64,
                importedConfig.guardianClientName == null ? "" : importedConfig.guardianClientName
            );
            setGuardianEnabled(context, true);
        }
        if (!TextUtils.isEmpty(importedConfig.guardianSyncMode)) {
            setGuardianSyncMode(context, importedConfig.guardianSyncMode);
        }
        if (importedConfig.guardianPeriodicIntervalMinutes > 0) {
            setGuardianPeriodicIntervalMinutes(context, importedConfig.guardianPeriodicIntervalMinutes);
        }
        wings.v.guardian.GuardianRunner.applyMode(context.getApplicationContext());
    }

    private static void applyImportedSubscriptionHwid(
        Context context,
        WingsImportParser.ImportedConfig importedConfig
    ) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (importedConfig.subscriptionHwidEnabled != null) {
            editor.putBoolean(KEY_SUBSCRIPTION_HWID_ENABLED, importedConfig.subscriptionHwidEnabled);
        }
        if (importedConfig.subscriptionHwidManualEnabled != null) {
            editor.putBoolean(KEY_SUBSCRIPTION_HWID_MANUAL_ENABLED, importedConfig.subscriptionHwidManualEnabled);
        }
        if (!TextUtils.isEmpty(importedConfig.subscriptionHwidValue)) {
            editor.putString(KEY_SUBSCRIPTION_HWID_VALUE, trim(importedConfig.subscriptionHwidValue));
        }
        if (!TextUtils.isEmpty(importedConfig.subscriptionHwidDeviceOs)) {
            editor.putString(KEY_SUBSCRIPTION_HWID_DEVICE_OS, trim(importedConfig.subscriptionHwidDeviceOs));
        }
        if (!TextUtils.isEmpty(importedConfig.subscriptionHwidVerOs)) {
            editor.putString(KEY_SUBSCRIPTION_HWID_VER_OS, trim(importedConfig.subscriptionHwidVerOs));
        }
        if (!TextUtils.isEmpty(importedConfig.subscriptionHwidDeviceModel)) {
            editor.putString(KEY_SUBSCRIPTION_HWID_DEVICE_MODEL, trim(importedConfig.subscriptionHwidDeviceModel));
        }
        editor.apply();
    }

    private static void applyImportedSharingSettings(Context context, WingsImportParser.ImportedConfig importedConfig) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (importedConfig.sharingAutoStartOnBoot != null) {
            editor.putBoolean(KEY_SHARING_AUTO_START_ON_BOOT, importedConfig.sharingAutoStartOnBoot);
        }
        if (!importedConfig.sharingLastActiveTypes.isEmpty()) {
            LinkedHashSet<String> commandNames = new LinkedHashSet<>();
            for (String entry : importedConfig.sharingLastActiveTypes) {
                String trimmed = trim(entry);
                if (!TextUtils.isEmpty(trimmed)) {
                    commandNames.add(trimmed);
                }
            }
            editor.putStringSet(KEY_SHARING_LAST_ACTIVE_TYPES, commandNames);
        }
        if (!TextUtils.isEmpty(importedConfig.sharingUpstreamInterface)) {
            editor.putString(KEY_SHARING_UPSTREAM_INTERFACE, trim(importedConfig.sharingUpstreamInterface));
        }
        if (!TextUtils.isEmpty(importedConfig.sharingFallbackUpstreamInterface)) {
            editor.putString(
                KEY_SHARING_FALLBACK_UPSTREAM_INTERFACE,
                trim(importedConfig.sharingFallbackUpstreamInterface)
            );
        }
        if (!TextUtils.isEmpty(importedConfig.sharingMasqueradeMode)) {
            editor.putString(KEY_SHARING_MASQUERADE_MODE, trim(importedConfig.sharingMasqueradeMode));
        }
        if (importedConfig.sharingDisableIpv6 != null) {
            editor.putBoolean(KEY_SHARING_DISABLE_IPV6, importedConfig.sharingDisableIpv6);
        }
        if (importedConfig.sharingDhcpWorkaround != null) {
            editor.putBoolean(KEY_SHARING_DHCP_WORKAROUND, importedConfig.sharingDhcpWorkaround);
        }
        if (!TextUtils.isEmpty(importedConfig.sharingWifiLockMode)) {
            editor.putString(KEY_SHARING_WIFI_LOCK, trim(importedConfig.sharingWifiLockMode));
        }
        if (importedConfig.sharingRepeaterSafeMode != null) {
            editor.putBoolean(KEY_SHARING_REPEATER_SAFE_MODE, importedConfig.sharingRepeaterSafeMode);
        }
        if (importedConfig.sharingTempHotspotUseSystem != null) {
            editor.putBoolean(KEY_SHARING_TEMP_HOTSPOT_USE_SYSTEM, importedConfig.sharingTempHotspotUseSystem);
        }
        if (!TextUtils.isEmpty(importedConfig.sharingIpMonitorMode)) {
            editor.putString(KEY_SHARING_IP_MONITOR_MODE, trim(importedConfig.sharingIpMonitorMode));
        }
        editor.apply();
    }

    private static void applyImportedByeDpiSettings(Context context, WingsImportParser.ImportedConfig importedConfig) {
        ByeDpiSettings s = importedConfig.byeDpiSettings;
        if (s == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean(ByeDpiStore.KEY_AUTO_START_WITH_XRAY, s.launchOnXrayStart);
        editor.putBoolean(ByeDpiStore.KEY_USE_COMMAND_SETTINGS, s.useCommandLineSettings);
        editor.putString(ByeDpiStore.KEY_PROXY_IP, trim(s.proxyIp));
        editor.putString(ByeDpiStore.KEY_PROXY_PORT, String.valueOf(s.proxyPort));
        editor.putBoolean(ByeDpiStore.KEY_PROXY_AUTH_ENABLED, s.proxyAuthEnabled);
        if (!TextUtils.isEmpty(s.proxyUsername)) {
            editor.putString(ByeDpiStore.KEY_PROXY_USERNAME, trim(s.proxyUsername));
        }
        if (!TextUtils.isEmpty(s.proxyPassword)) {
            editor.putString(ByeDpiStore.KEY_PROXY_PASSWORD, trim(s.proxyPassword));
        }
        editor.putString(ByeDpiStore.KEY_MAX_CONNECTIONS, String.valueOf(s.maxConnections));
        editor.putString(ByeDpiStore.KEY_BUFFER_SIZE, String.valueOf(s.bufferSize));
        editor.putBoolean(ByeDpiStore.KEY_NO_DOMAIN, s.noDomain);
        editor.putBoolean(ByeDpiStore.KEY_TCP_FAST_OPEN, s.tcpFastOpen);
        editor.putString(
            ByeDpiStore.KEY_HOSTS_MODE,
            s.hostsMode == null ? ByeDpiSettings.HostsMode.DISABLE.prefValue : s.hostsMode.prefValue
        );
        editor.putString(ByeDpiStore.KEY_HOSTS_BLACKLIST, trim(s.hostsBlacklist));
        editor.putString(ByeDpiStore.KEY_HOSTS_WHITELIST, trim(s.hostsWhitelist));
        editor.putString(ByeDpiStore.KEY_DEFAULT_TTL, String.valueOf(s.defaultTtl));
        editor.putString(
            ByeDpiStore.KEY_DESYNC_METHOD,
            s.desyncMethod == null ? ByeDpiSettings.DesyncMethod.OOB.prefValue : s.desyncMethod.prefValue
        );
        editor.putString(ByeDpiStore.KEY_SPLIT_POSITION, String.valueOf(s.splitPosition));
        editor.putBoolean(ByeDpiStore.KEY_SPLIT_AT_HOST, s.splitAtHost);
        editor.putBoolean(ByeDpiStore.KEY_DROP_SACK, s.dropSack);
        editor.putString(ByeDpiStore.KEY_FAKE_TTL, String.valueOf(s.fakeTtl));
        editor.putString(ByeDpiStore.KEY_FAKE_OFFSET, String.valueOf(s.fakeOffset));
        editor.putString(ByeDpiStore.KEY_FAKE_SNI, trim(s.fakeSni));
        editor.putString(ByeDpiStore.KEY_OOB_DATA, trim(s.oobData));
        editor.putBoolean(ByeDpiStore.KEY_DESYNC_HTTP, s.desyncHttp);
        editor.putBoolean(ByeDpiStore.KEY_DESYNC_HTTPS, s.desyncHttps);
        editor.putBoolean(ByeDpiStore.KEY_DESYNC_UDP, s.desyncUdp);
        editor.putBoolean(ByeDpiStore.KEY_HOST_MIXED_CASE, s.hostMixedCase);
        editor.putBoolean(ByeDpiStore.KEY_DOMAIN_MIXED_CASE, s.domainMixedCase);
        editor.putBoolean(ByeDpiStore.KEY_HOST_REMOVE_SPACES, s.hostRemoveSpaces);
        editor.putBoolean(ByeDpiStore.KEY_TLSREC_ENABLED, s.tlsRecordSplit);
        editor.putString(ByeDpiStore.KEY_TLSREC_POSITION, String.valueOf(s.tlsRecordSplitPosition));
        editor.putBoolean(ByeDpiStore.KEY_TLSREC_AT_SNI, s.tlsRecordSplitAtSni);
        editor.putString(ByeDpiStore.KEY_UDP_FAKE_COUNT, String.valueOf(s.udpFakeCount));
        editor.putString(ByeDpiStore.KEY_CMD_ARGS, trim(s.rawCommandArgs));
        editor.putString(ByeDpiStore.KEY_PROXYTEST_DELAY, String.valueOf(s.proxyTestDelaySeconds));
        editor.putString(ByeDpiStore.KEY_PROXYTEST_REQUESTS, String.valueOf(s.proxyTestRequests));
        editor.putString(ByeDpiStore.KEY_PROXYTEST_LIMIT, String.valueOf(s.proxyTestConcurrencyLimit));
        editor.putString(ByeDpiStore.KEY_PROXYTEST_TIMEOUT, String.valueOf(s.proxyTestTimeoutSeconds));
        editor.putString(ByeDpiStore.KEY_PROXYTEST_SNI, trim(s.proxyTestSni));
        editor.putBoolean(ByeDpiStore.KEY_PROXYTEST_USE_CUSTOM_STRATEGIES, s.proxyTestUseCustomStrategies);
        editor.putString(ByeDpiStore.KEY_PROXYTEST_CUSTOM_STRATEGIES, trim(s.proxyTestCustomStrategies));
        editor.apply();
    }

    private static void applyImportedXposedSettings(Context context, WingsImportParser.ImportedConfig importedConfig) {
        SharedPreferences.Editor editor = XposedModulePrefs.prefs(context).edit();
        if (importedConfig.xposedEnabled != null) {
            editor.putBoolean(XposedModulePrefs.KEY_ENABLED, importedConfig.xposedEnabled);
        }
        if (importedConfig.xposedAllApps != null) {
            editor.putBoolean(XposedModulePrefs.KEY_ALL_APPS, importedConfig.xposedAllApps);
        }
        if (importedConfig.xposedNativeHookEnabled != null) {
            editor.putBoolean(XposedModulePrefs.KEY_NATIVE_HOOK_ENABLED, importedConfig.xposedNativeHookEnabled);
        }
        if (importedConfig.xposedHideVpnApps != null) {
            editor.putBoolean(XposedModulePrefs.KEY_HIDE_VPN_APPS, importedConfig.xposedHideVpnApps);
        }
        if (importedConfig.xposedHideFromDumpsys != null) {
            editor.putBoolean(XposedModulePrefs.KEY_HIDE_FROM_DUMPSYS, importedConfig.xposedHideFromDumpsys);
        }
        if (!TextUtils.isEmpty(importedConfig.xposedProcfsHookMode)) {
            editor.putString(
                XposedModulePrefs.KEY_PROCFS_HOOK_MODE,
                XposedModulePrefs.normalizeProcfsHookMode(importedConfig.xposedProcfsHookMode)
            );
        }
        if (!TextUtils.isEmpty(importedConfig.xposedIcmpSpoofingMode)) {
            editor.putString(
                XposedModulePrefs.KEY_ICMP_SPOOFING_MODE,
                XposedModulePrefs.normalizeIcmpSpoofingMode(importedConfig.xposedIcmpSpoofingMode)
            );
        }
        if (!importedConfig.xposedTargetPackages.isEmpty()) {
            editor.putStringSet(
                XposedModulePrefs.KEY_TARGET_PACKAGES,
                new LinkedHashSet<>(importedConfig.xposedTargetPackages)
            );
        }
        if (!importedConfig.xposedHiddenVpnPackages.isEmpty()) {
            editor.putStringSet(
                XposedModulePrefs.KEY_HIDDEN_VPN_PACKAGES,
                new LinkedHashSet<>(importedConfig.xposedHiddenVpnPackages)
            );
        }
        editor.apply();
        XposedModulePrefs.export(context);
    }

    public static void applyVkTurnSettings(Context context, ProxySettings settings) {
        if (settings == null) {
            return;
        }
        prefs(context)
            .edit()
            .putString(KEY_ENDPOINT, trim(settings.endpoint))
            .putString(KEY_VK_LINK, trim(settings.vkLink))
            .putString(KEY_VK_LINKS_JSON, encodeVkLinks(settings.vkLinks))
            .putString(KEY_VK_LINK_SECONDARY, trim(settings.vkLinkSecondary))
            .putString(KEY_THREADS, String.valueOf(settings.threads > 0 ? settings.threads : 24))
            .putString(KEY_CREDS_GROUP_SIZE, String.valueOf(settings.credsGroupSize > 0 ? settings.credsGroupSize : 12))
            .putBoolean(KEY_USE_UDP, settings.useUdp)
            .putBoolean(KEY_NO_OBFUSCATION, settings.noObfuscation)
            .putBoolean(KEY_MANUAL_CAPTCHA, settings.manualCaptcha)
            .putString(KEY_CAPTCHA_AUTO_SOLVER, normalizeCaptchaAutoSolver(settings.captchaAutoSolver))
            .putBoolean(KEY_VK_TURN_RESTART_ON_NETWORK_CHANGE, settings.vkTurnRestartOnNetworkChange)
            .putString(
                KEY_VK_TURN_RUNTIME_MODE,
                settings.vkTurnRuntimeMode == null
                    ? ProxyRuntimeMode.VPN.prefValue
                    : settings.vkTurnRuntimeMode.prefValue
            )
            .putString(KEY_VK_TURN_USER_DNS, trim(settings.vkTurnUserDns))
            .putString(KEY_VK_TURN_WRAP_MODE, normalizeWrapMode(settings.vkTurnWrapMode))
            .putString(KEY_VK_TURN_WRAP_CIPHER, normalizeWrapCipher(settings.vkTurnWrapCipher))
            .putString(KEY_VK_TURN_WRAP_KEY_HEX, trim(settings.vkTurnWrapKeyHex))
            .putBoolean(KEY_VK_TURN_WRAP_SEND_KEY, settings.vkTurnWrapSendKey)
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
        return defaultSharedPreferences(context);
    }

    public static TunnelMode getVkTurnTunnelMode(Context context) {
        return TunnelMode.fromPrefValue(
            prefs(context).getString(KEY_VK_TURN_TUNNEL_MODE, TunnelMode.WIREGUARD.prefValue)
        );
    }

    public static void setVkTurnTunnelMode(Context context, TunnelMode mode) {
        prefs(context)
            .edit()
            .putString(KEY_VK_TURN_TUNNEL_MODE, (mode == null ? TunnelMode.WIREGUARD : mode).prefValue)
            .apply();
    }

    public static TunnelMode getWbStreamTunnelMode(Context context) {
        return TunnelMode.fromPrefValue(
            prefs(context).getString(KEY_WB_STREAM_TUNNEL_MODE, TunnelMode.WIREGUARD.prefValue)
        );
    }

    public static void setWbStreamTunnelMode(Context context, TunnelMode mode) {
        prefs(context)
            .edit()
            .putString(KEY_WB_STREAM_TUNNEL_MODE, (mode == null ? TunnelMode.WIREGUARD : mode).prefValue)
            .apply();
    }

    public static long getGuardianLastAppliedConfigVersion(Context context) {
        return prefs(context).getLong(KEY_GUARDIAN_LAST_APPLIED_CONFIG_VERSION, 0L);
    }

    public static void setGuardianLastAppliedConfigVersion(Context context, long version) {
        prefs(context).edit().putLong(KEY_GUARDIAN_LAST_APPLIED_CONFIG_VERSION, version).apply();
    }

    /**
     * Запускается из {@link wings.v.WingsApplication#onCreate()} один раз при старте.
     * Идемпотентна — версия хранится в {@link #KEY_PREFS_SCHEMA_VERSION}, повторные
     * вызовы no-op. Текущие миграции:
     * <ul>
     *   <li>v1: вывести {@link #KEY_VK_TURN_TUNNEL_MODE} и {@link #KEY_WB_STREAM_TUNNEL_MODE}
     *       из существующего {@link #KEY_BACKEND_TYPE}, чтобы UI-дропдауны под-backend'а
     *       показывали актуальное состояние у пользователей с предыдущей версии.</li>
     * </ul>
     */
    public static void runMigrationsIfNeeded(Context context) {
        SharedPreferences sharedPreferences = prefs(context);
        int currentVersion = sharedPreferences.getInt(KEY_PREFS_SCHEMA_VERSION, 0);
        if (currentVersion >= CURRENT_PREFS_SCHEMA_VERSION) {
            return;
        }
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (currentVersion < 1) {
            applyMigrationV1(sharedPreferences, editor);
        }
        editor.putInt(KEY_PREFS_SCHEMA_VERSION, CURRENT_PREFS_SCHEMA_VERSION).apply();
    }

    private static void applyMigrationV1(SharedPreferences sharedPreferences, SharedPreferences.Editor editor) {
        if (!sharedPreferences.contains(KEY_VK_TURN_TUNNEL_MODE)) {
            String legacyBackend = sharedPreferences.getString(KEY_BACKEND_TYPE, "");
            TunnelMode vkTurnMode = "amneziawg".equals(legacyBackend) ? TunnelMode.AMNEZIAWG : TunnelMode.WIREGUARD;
            editor.putString(KEY_VK_TURN_TUNNEL_MODE, vkTurnMode.prefValue);
        }
        if (!sharedPreferences.contains(KEY_WB_STREAM_TUNNEL_MODE)) {
            // До v1 у WB Stream был только WireGuard-туннель — сохраняем поведение.
            editor.putString(KEY_WB_STREAM_TUNNEL_MODE, TunnelMode.WIREGUARD.prefValue);
        }
    }

    public static List<String> getVkLinks(Context context) {
        SharedPreferences sharedPreferences = prefs(context);
        return readVkLinks(sharedPreferences, trim(sharedPreferences.getString(KEY_VK_LINK, "")));
    }

    public static void setVkLinks(Context context, List<String> links) {
        prefs(context).edit().putString(KEY_VK_LINKS_JSON, encodeVkLinks(links)).apply();
    }

    public static String getVkLinkSecondary(Context context) {
        return trim(prefs(context).getString(KEY_VK_LINK_SECONDARY, ""));
    }

    public static void setVkLinkSecondary(Context context, String value) {
        prefs(context).edit().putString(KEY_VK_LINK_SECONDARY, trim(value)).apply();
    }

    public static String getWbStreamRoomId(Context context) {
        return trim(prefs(context).getString(KEY_WB_STREAM_ROOM_ID, ""));
    }

    public static void setWbStreamRoomId(Context context, String value) {
        prefs(context).edit().putString(KEY_WB_STREAM_ROOM_ID, trim(value)).apply();
    }

    public static String getWbStreamDisplayName(Context context) {
        return trim(prefs(context).getString(KEY_WB_STREAM_DISPLAY_NAME, ""));
    }

    public static void setWbStreamDisplayName(Context context, String value) {
        prefs(context).edit().putString(KEY_WB_STREAM_DISPLAY_NAME, trim(value)).apply();
    }

    public static boolean isWbStreamExchangeViaVkTurn(Context context) {
        return prefs(context).getBoolean(KEY_WB_STREAM_EXCHANGE_VIA_VK_TURN, false);
    }

    public static void setWbStreamExchangeViaVkTurn(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_WB_STREAM_EXCHANGE_VIA_VK_TURN, value).apply();
    }

    public static boolean isWbStreamE2eEnabled(Context context) {
        return prefs(context).getBoolean(KEY_WB_STREAM_E2E_ENABLED, false);
    }

    public static void setWbStreamE2eEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_WB_STREAM_E2E_ENABLED, value).apply();
    }

    public static String getWbStreamE2eSecret(Context context) {
        return trim(prefs(context).getString(KEY_WB_STREAM_E2E_SECRET, ""));
    }

    public static void setWbStreamE2eSecret(Context context, String value) {
        prefs(context).edit().putString(KEY_WB_STREAM_E2E_SECRET, trim(value)).apply();
    }

    public static int getWbStreamRoomCount(Context context) {
        int raw = parseInt(
            prefs(context).getString(KEY_WB_STREAM_ROOM_COUNT, String.valueOf(DEFAULT_WB_STREAM_ROOM_COUNT)),
            DEFAULT_WB_STREAM_ROOM_COUNT
        );
        return clampWbStreamRoomCount(raw);
    }

    public static void setWbStreamRoomCount(Context context, int value) {
        prefs(context)
            .edit()
            .putString(KEY_WB_STREAM_ROOM_COUNT, String.valueOf(clampWbStreamRoomCount(value)))
            .apply();
    }

    public static int clampWbStreamRoomCount(int value) {
        if (value < 1) {
            return 1;
        }
        if (value > MAX_WB_STREAM_ROOM_COUNT) {
            return MAX_WB_STREAM_ROOM_COUNT;
        }
        return value;
    }

    private static List<String> readVkLinks(SharedPreferences sharedPreferences, String legacyFallback) {
        ArrayList<String> result = new ArrayList<>();
        String json = sharedPreferences.getString(KEY_VK_LINKS_JSON, "");
        if (!TextUtils.isEmpty(json)) {
            try {
                JSONArray array = new JSONArray(json);
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    String value = trim(array.optString(i, ""));
                    if (!TextUtils.isEmpty(value) && seen.add(value)) {
                        result.add(value);
                    }
                }
            } catch (JSONException ignored) {}
        }
        if (result.isEmpty() && !TextUtils.isEmpty(legacyFallback)) {
            result.add(legacyFallback);
        }
        return result;
    }

    private static String encodeVkLinks(List<String> links) {
        if (links == null || links.isEmpty()) {
            return "";
        }
        JSONArray array = new JSONArray();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String link : links) {
            String value = trim(link);
            if (!TextUtils.isEmpty(value) && seen.add(value)) {
                array.put(value);
            }
        }
        return array.toString();
    }

    @SuppressWarnings("deprecation")
    public static SharedPreferences defaultSharedPreferences(Context context) {
        Context appContext = context.getApplicationContext();
        return appContext.getSharedPreferences(
            appContext.getPackageName() + "_preferences",
            Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS
        );
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences runtimePrefs(Context context) {
        return context
            .getApplicationContext()
            .getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
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
        if ("mux".equals(normalized) || TURN_SESSION_MODE_MU.equals(normalized)) {
            return TURN_SESSION_MODE_MU;
        }
        if (TURN_SESSION_MODE_MAINLINE.equals(normalized)) {
            return TURN_SESSION_MODE_MAINLINE;
        }
        return TURN_SESSION_MODE_AUTO;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeCaptchaAutoSolver(String value) {
        String normalized = trim(value).toLowerCase(java.util.Locale.ROOT);
        if ("v1".equals(normalized) || "v2".equals(normalized)) {
            return normalized;
        }
        return CAPTCHA_AUTO_SOLVER_DEFAULT;
    }

    public static String normalizeWrapMode(String value) {
        String normalized = trim(value).toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "off":
            case "preferred":
            case "required":
                return normalized;
            default:
                return "preferred";
        }
    }

    public static String normalizeWrapCipher(String value) {
        String normalized = trim(value).toLowerCase(java.util.Locale.ROOT);
        if ("srtp-chacha20-poly1305".equals(normalized)) {
            return "srtp-chacha20-poly1305";
        }
        return "srtp-aes-gcm";
    }
}
