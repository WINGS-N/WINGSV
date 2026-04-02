package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.LinkedHashSet;
import java.util.Set;

import wings.v.R;

public final class AppPrefs {
    public static final String KEY_ENDPOINT = "pref_endpoint";
    public static final String KEY_VK_LINK = "pref_vk_link";
    public static final String KEY_THREADS = "pref_threads";
    public static final String KEY_USE_UDP = "pref_use_udp";
    public static final String KEY_NO_OBFUSCATION = "pref_no_obfuscation";
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
    public static final String KEY_APP_ROUTING_BYPASS = "pref_app_routing_bypass";
    public static final String KEY_APP_ROUTING_PACKAGES = "pref_app_routing_packages";
    public static final String KEY_ROOT_MODE = "pref_root_mode";
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

    private AppPrefs() {
    }

    public static void ensureDefaults(Context context) {
        PreferenceManager.setDefaultValues(context, R.xml.proxy_preferences, false);
    }

    public static boolean isOnboardingSeen(Context context) {
        return prefs(context).getBoolean(KEY_ONBOARDING_SEEN, false);
    }

    public static boolean isRootModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ROOT_MODE, false);
    }

    public static void setRootModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ROOT_MODE, enabled).apply();
    }

    public static boolean isRootAccessGranted(Context context) {
        return prefs(context).getBoolean(KEY_ROOT_ACCESS_GRANTED, false);
    }

    public static void setRootAccessGranted(Context context, boolean granted) {
        prefs(context).edit()
                .putBoolean(KEY_ROOT_ACCESS_GRANTED, granted)
                .putLong(KEY_ROOT_ACCESS_CHECKED_AT, System.currentTimeMillis())
                .apply();
    }

    public static long getRootAccessCheckedAt(Context context) {
        return prefs(context).getLong(KEY_ROOT_ACCESS_CHECKED_AT, 0L);
    }

    public static boolean hasRootRuntimeState(Context context) {
        return prefs(context).getBoolean(KEY_ROOT_RUNTIME_ACTIVE, false)
                && !TextUtils.isEmpty(getRootRuntimeTunnelName(context));
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
        prefs(context).edit()
                .putBoolean(KEY_ROOT_RUNTIME_ACTIVE, active)
                .putString(KEY_ROOT_RUNTIME_TUNNEL, normalizedTunnelName)
                .putLong(KEY_ROOT_RUNTIME_PROXY_PID, Math.max(proxyPid, 0L))
                .apply();
    }

    public static void clearRootRuntimeState(Context context) {
        prefs(context).edit()
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
        prefs(context).edit()
                .remove(KEY_RUNTIME_UPSTREAM_INTERFACE)
                .remove(KEY_RUNTIME_UPSTREAM_ROOT_DNS)
                .apply();
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
        LinkedHashSet<TetherType> result = new LinkedHashSet<>();
        if (stored == null || stored.isEmpty()) {
            return result;
        }
        for (String rawValue : stored) {
            if (TextUtils.isEmpty(rawValue)) {
                continue;
            }
            try {
                result.add(TetherType.fromCommandName(rawValue.trim()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    public static void setSharingLastActiveTypes(Context context, Set<TetherType> types) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
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
        prefs(context).edit().putString(
                KEY_SHARING_MASQUERADE_MODE,
                TextUtils.isEmpty(trim(value)) ? SHARING_MASQUERADE_SIMPLE : trim(value)
        ).apply();
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
        prefs(context).edit().putString(
                KEY_SHARING_WIFI_LOCK,
                TextUtils.isEmpty(trim(value)) ? SHARING_WIFI_LOCK_SYSTEM : trim(value)
        ).apply();
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
        prefs(context).edit().putString(
                KEY_SHARING_IP_MONITOR_MODE,
                TextUtils.isEmpty(trim(value)) ? SHARING_IP_MONITOR_NETLINK : trim(value)
        ).apply();
    }

    public static void markOnboardingSeen(Context context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply();
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

    public static ProxySettings getSettings(Context context) {
        SharedPreferences prefs = prefs(context);
        ProxySettings settings = new ProxySettings();
        settings.endpoint = trim(prefs.getString(KEY_ENDPOINT, ""));
        settings.vkLink = trim(prefs.getString(KEY_VK_LINK, ""));
        settings.threads = parseInt(prefs.getString(KEY_THREADS, "8"), 8);
        settings.useUdp = prefs.getBoolean(KEY_USE_UDP, true);
        settings.noObfuscation = prefs.getBoolean(KEY_NO_OBFUSCATION, false);
        settings.turnSessionMode = trim(prefs.getString(KEY_TURN_SESSION_MODE, "auto"));
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
        settings.rootModeEnabled = prefs.getBoolean(KEY_ROOT_MODE, false);
        return settings;
    }

    public static void applyImportedConfig(Context context, WingsImportParser.ImportedConfig importedConfig) {
        SharedPreferences.Editor editor = prefs(context).edit();

        editor.putString(KEY_ENDPOINT, trim(importedConfig.endpoint));
        editor.putString(KEY_VK_LINK, trim(importedConfig.link));
        editor.putString(KEY_THREADS, String.valueOf(
                importedConfig.threads != null && importedConfig.threads > 0 ? importedConfig.threads : 8
        ));
        editor.putBoolean(KEY_USE_UDP, importedConfig.useUdp == null || importedConfig.useUdp);
        editor.putBoolean(KEY_NO_OBFUSCATION,
                importedConfig.noObfuscation != null && importedConfig.noObfuscation);
        editor.putString(KEY_TURN_SESSION_MODE, TextUtils.isEmpty(trim(importedConfig.turnSessionMode))
                ? "auto"
                : trim(importedConfig.turnSessionMode));
        editor.putString(KEY_LOCAL_ENDPOINT, TextUtils.isEmpty(trim(importedConfig.localEndpoint))
                ? "127.0.0.1:9000"
                : trim(importedConfig.localEndpoint));
        editor.putString(KEY_TURN_HOST, trim(importedConfig.turnHost));
        editor.putString(KEY_TURN_PORT, trim(importedConfig.turnPort));
        editor.putString(KEY_WG_PRIVATE_KEY, trim(importedConfig.wgPrivateKey));
        editor.putString(KEY_WG_ADDRESSES, trim(importedConfig.wgAddresses));
        editor.putString(KEY_WG_DNS, TextUtils.isEmpty(trim(importedConfig.wgDns))
                ? "1.1.1.1, 1.0.0.1"
                : trim(importedConfig.wgDns));
        editor.putString(KEY_WG_MTU, String.valueOf(
                importedConfig.wgMtu != null && importedConfig.wgMtu > 0 ? importedConfig.wgMtu : 1280
        ));
        editor.putString(KEY_WG_PUBLIC_KEY, trim(importedConfig.wgPublicKey));
        editor.putString(KEY_WG_PRESHARED_KEY, trim(importedConfig.wgPresharedKey));
        editor.putString(KEY_WG_ALLOWED_IPS, TextUtils.isEmpty(trim(importedConfig.wgAllowedIps))
                ? "0.0.0.0/0, ::/0"
                : trim(importedConfig.wgAllowedIps));

        editor.apply();
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

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
