package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import java.util.LinkedHashSet;
import java.util.Set;
import wings.v.R;

public final class XposedSecurityScore {

    private static final int WEIGHT_MODULE_ENABLED = 30;
    private static final int WEIGHT_ALL_APPS = 8;
    private static final int WEIGHT_TARGETED_APPS = 6;
    private static final int WEIGHT_HIDE_VPN_APPS = 12;
    private static final int WEIGHT_HIDE_VPN_RECOMMENDED = 8;
    private static final int WEIGHT_NATIVE_HOOKS = 10;
    private static final int WEIGHT_PROCFS_MODE = 7;
    private static final int WEIGHT_ICMP_SPOOFING = 6;
    private static final int WEIGHT_DUMPSYS = 8;
    private static final int WEIGHT_XRAY_PROXY_AUTH = 8;
    private static final int WEIGHT_BYEDPI_PROXY_AUTH = 8;
    private static final int WEIGHT_BYPASS_RECOMMENDED = 10;

    private XposedSecurityScore() {}

    @NonNull
    public static Snapshot compute(@NonNull Context context) {
        int totalWeight = 0;
        int currentScore = 0;
        Set<String> highlights = new LinkedHashSet<>();

        XposedModulePrefs.ensureDefaults(context);
        boolean moduleEnabled = XposedModulePrefs.prefs(context).getBoolean(
            XposedModulePrefs.KEY_ENABLED,
            XposedModulePrefs.DEFAULT_ENABLED
        );
        totalWeight += WEIGHT_MODULE_ENABLED;
        if (moduleEnabled) {
            currentScore += WEIGHT_MODULE_ENABLED;
        } else {
            highlights.add(context.getString(R.string.xposed_security_hint_module_disabled));
        }

        boolean allApps = XposedModulePrefs.prefs(context).getBoolean(
            XposedModulePrefs.KEY_ALL_APPS,
            XposedModulePrefs.DEFAULT_ALL_APPS
        );
        totalWeight += WEIGHT_ALL_APPS;
        if (allApps) {
            currentScore += WEIGHT_ALL_APPS;
        } else if (!XposedModulePrefs.getTargetPackages(context).isEmpty()) {
            currentScore += WEIGHT_TARGETED_APPS;
        } else {
            highlights.add(context.getString(R.string.xposed_security_hint_scope_narrow));
        }

        boolean hideVpnApps = XposedModulePrefs.prefs(context).getBoolean(
            XposedModulePrefs.KEY_HIDE_VPN_APPS,
            XposedModulePrefs.DEFAULT_HIDE_VPN_APPS
        );
        totalWeight += WEIGHT_HIDE_VPN_APPS;
        if (hideVpnApps) {
            currentScore += WEIGHT_HIDE_VPN_APPS;
        } else {
            highlights.add(context.getString(R.string.xposed_security_hint_hide_vpn_disabled));
        }

        totalWeight += WEIGHT_HIDE_VPN_RECOMMENDED;
        Set<String> installedRecommendedHidden = intersectInstalled(
            context,
            XposedModulePrefs.getRecommendedHiddenVpnPackages(context)
        );
        if (!installedRecommendedHidden.isEmpty() && hideVpnApps) {
            float ratio = coverageRatio(installedRecommendedHidden, XposedModulePrefs.getHiddenVpnPackages(context));
            currentScore += Math.round(WEIGHT_HIDE_VPN_RECOMMENDED * ratio);
            if (ratio < 1f) {
                highlights.add(context.getString(R.string.xposed_security_hint_hidden_vpn_coverage));
            }
        }

        boolean nativeHooksEnabled = XposedModulePrefs.prefs(context).getBoolean(
            XposedModulePrefs.KEY_NATIVE_HOOK_ENABLED,
            XposedModulePrefs.DEFAULT_NATIVE_HOOK_ENABLED
        );
        totalWeight += WEIGHT_NATIVE_HOOKS;
        if (nativeHooksEnabled) {
            currentScore += WEIGHT_NATIVE_HOOKS;
        } else {
            highlights.add(context.getString(R.string.xposed_security_hint_native_disabled));
        }

        totalWeight += WEIGHT_PROCFS_MODE;
        String procfsMode = XposedModulePrefs.prefs(context).getString(
            XposedModulePrefs.KEY_PROCFS_HOOK_MODE,
            XposedModulePrefs.DEFAULT_PROCFS_HOOK_MODE
        );
        if (nativeHooksEnabled && XposedModulePrefs.isProcfsHookModeProtective(procfsMode)) {
            currentScore += WEIGHT_PROCFS_MODE;
        } else {
            highlights.add(context.getString(R.string.xposed_security_hint_procfs_disabled));
        }

        totalWeight += WEIGHT_ICMP_SPOOFING;
        String icmpSpoofingMode = XposedModulePrefs.normalizeIcmpSpoofingMode(
            XposedModulePrefs.prefs(context).getString(
                XposedModulePrefs.KEY_ICMP_SPOOFING_MODE,
                XposedModulePrefs.DEFAULT_ICMP_SPOOFING_MODE
            )
        );
        if (
            XposedModulePrefs.ICMP_SPOOFING_MODE_PING_NOT_FOUND.equals(icmpSpoofingMode) ||
            XposedModulePrefs.ICMP_SPOOFING_MODE_EMPTY_RESPONSE.equals(icmpSpoofingMode)
        ) {
            currentScore += WEIGHT_ICMP_SPOOFING;
        } else {
            highlights.add(context.getString(R.string.xposed_security_hint_icmp_spoofing_disabled));
        }

        boolean hideFromDumpsys = XposedModulePrefs.prefs(context).getBoolean(
            XposedModulePrefs.KEY_HIDE_FROM_DUMPSYS,
            XposedModulePrefs.DEFAULT_HIDE_FROM_DUMPSYS
        );
        totalWeight += WEIGHT_DUMPSYS;
        if (hideFromDumpsys) {
            currentScore += WEIGHT_DUMPSYS;
        } else {
            highlights.add(context.getString(R.string.xposed_security_hint_dumpsys_disabled));
        }

        XraySettings xraySettings = XrayStore.getXraySettings(context);
        totalWeight += WEIGHT_XRAY_PROXY_AUTH;
        if (!xraySettings.localProxyEnabled) {
            currentScore += WEIGHT_XRAY_PROXY_AUTH;
        } else if (
            xraySettings.localProxyAuthEnabled &&
            !SocksAuthSecurity.isPasswordTooSimple(xraySettings.localProxyUsername, xraySettings.localProxyPassword)
        ) {
            currentScore += WEIGHT_XRAY_PROXY_AUTH;
        } else if (xraySettings.localProxyAuthEnabled) {
            currentScore += Math.round(WEIGHT_XRAY_PROXY_AUTH * 0.45f);
            highlights.add(context.getString(R.string.xposed_security_hint_xray_auth_weak));
        } else {
            highlights.add(context.getString(R.string.xposed_security_hint_xray_auth_disabled));
        }

        ByeDpiSettings byeDpiSettings = ByeDpiStore.getSettings(context);
        totalWeight += WEIGHT_BYEDPI_PROXY_AUTH;
        if (
            byeDpiSettings.proxyAuthEnabled &&
            !SocksAuthSecurity.isPasswordTooSimple(byeDpiSettings.proxyUsername, byeDpiSettings.proxyPassword)
        ) {
            currentScore += WEIGHT_BYEDPI_PROXY_AUTH;
        } else if (byeDpiSettings.proxyAuthEnabled) {
            currentScore += Math.round(WEIGHT_BYEDPI_PROXY_AUTH * 0.45f);
            highlights.add(context.getString(R.string.xposed_security_hint_byedpi_auth_weak));
        } else {
            highlights.add(context.getString(R.string.xposed_security_hint_byedpi_auth_disabled));
        }

        totalWeight += WEIGHT_BYPASS_RECOMMENDED;
        if (AppPrefs.isAppRoutingBypassEnabled(context)) {
            Set<String> installedRecommendedBypass = intersectInstalled(
                context,
                RuStoreRecommendedAppsAsset.getPackageNames(context)
            );
            if (!installedRecommendedBypass.isEmpty()) {
                float ratio = coverageRatio(installedRecommendedBypass, AppPrefs.getAppRoutingPackages(context));
                currentScore += Math.round(WEIGHT_BYPASS_RECOMMENDED * ratio);
                if (ratio < 1f) {
                    highlights.add(context.getString(R.string.xposed_security_hint_bypass_recommended));
                }
            }
        } else {
            currentScore += WEIGHT_BYPASS_RECOMMENDED;
        }

        int normalized = totalWeight <= 0 ? 0 : Math.round((currentScore * 100f) / totalWeight);
        return new Snapshot(normalized, getLevel(normalized), highlights.isEmpty() ? "" : highlights.iterator().next());
    }

    @NonNull
    private static Level getLevel(int score) {
        if (score >= 75) {
            return Level.MAXIMUM;
        }
        if (score >= 40) {
            return Level.MEDIUM;
        }
        return Level.WEAK;
    }

    private static float coverageRatio(@NonNull Set<String> recommended, @NonNull Set<String> selected) {
        if (recommended.isEmpty()) {
            return 1f;
        }
        int matched = 0;
        for (String packageName : recommended) {
            if (selected.contains(packageName)) {
                matched++;
            }
        }
        return matched / (float) recommended.size();
    }

    @NonNull
    private static Set<String> intersectInstalled(@NonNull Context context, @NonNull Set<String> packages) {
        Set<String> installed = new LinkedHashSet<>();
        for (String packageName : packages) {
            if (!TextUtils.isEmpty(packageName) && isInstalled(context, packageName)) {
                installed.add(packageName);
            }
        }
        return installed;
    }

    private static boolean isInstalled(@NonNull Context context, @NonNull String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public enum Level {
        WEAK,
        MEDIUM,
        MAXIMUM,
    }

    public static final class Snapshot {

        public final int score;

        @NonNull
        public final Level level;

        @NonNull
        public final String hint;

        public Snapshot(int score, @NonNull Level level, @NonNull String hint) {
            this.score = score;
            this.level = level;
            this.hint = hint;
        }
    }
}
