package wings.v.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import androidx.preference.PreferenceManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import wings.v.R;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public final class XposedModulePrefs {

    public static final String PREFS_NAME = "xposed_module_preferences";
    public static final String PROP_NATIVE_HOOK_ENABLED = "persist.wingsv.xposed.native_hook";
    public static final String PROP_PROCFS_HOOK_MODE = "persist.wingsv.xposed.procfs_hook_mode";
    public static final String KEY_OPEN_SETTINGS = "pref_open_xposed_settings";
    public static final String KEY_ENABLED = "pref_xposed_enabled";
    public static final String KEY_ALL_APPS = "pref_xposed_all_apps";
    public static final String KEY_TARGET_PACKAGES = "pref_xposed_target_packages";
    public static final String KEY_TARGET_PACKAGES_RECOMMENDED_DISMISSED =
        "pref_xposed_target_packages_recommended_dismissed";
    public static final String KEY_NATIVE_HOOK_ENABLED = "pref_xposed_native_hook_enabled";
    public static final String KEY_PROCFS_HOOK_MODE = "pref_xposed_procfs_hook_mode";
    public static final String KEY_HIDE_VPN_APPS = "pref_xposed_hide_vpn_apps";
    public static final String KEY_HIDDEN_VPN_PACKAGES = "pref_xposed_hidden_vpn_packages";
    public static final String KEY_HIDDEN_VPN_PACKAGES_RECOMMENDED_DISMISSED =
        "pref_xposed_hidden_vpn_packages_recommended_dismissed";
    public static final String KEY_HIDE_FROM_DUMPSYS = "pref_xposed_hide_from_dumpsys";

    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_ALL_APPS = true;
    public static final boolean DEFAULT_NATIVE_HOOK_ENABLED = false;
    public static final String PROCFS_HOOK_MODE_DISABLED = "disabled";
    public static final String PROCFS_HOOK_MODE_NO_ACCESS = "no_access";
    public static final String PROCFS_HOOK_MODE_FILE_NOT_FOUND = "file_not_found";
    public static final String DEFAULT_PROCFS_HOOK_MODE = PROCFS_HOOK_MODE_DISABLED;
    public static final boolean DEFAULT_HIDE_VPN_APPS = true;
    public static final boolean DEFAULT_HIDE_FROM_DUMPSYS = false;
    public static final String DEFAULT_HIDDEN_VPN_PACKAGES =
        "com.github.dyhkwong.sagernet\n" +
        "com.v2ray.ang\n" +
        "io.github.saeeddev94.xray\n" +
        "org.amnezia.awg\n" +
        "org.amnezia.vpn\n" +
        "de.blinkt.openvpn\n" +
        "net.openvpn.openvpn\n" +
        "com.wireguard.android\n" +
        "com.strongswan.android\n" +
        "com.cloudflare.onedotonedotonedotone\n" +
        "com.psiphon3\n" +
        "org.outline.android.client\n" +
        "org.getlantern.lantern\n" +
        "org.torproject.android\n" +
        "info.guardianproject.orfox\n" +
        "org.torproject.torbrowser\n" +
        "app.hiddify.com\n" +
        "io.nekohasekai.sfa\n" +
        "com.happproxy\n" +
        "com.github.metacubex.clash.meta\n" +
        "com.github.shadowsocks\n" +
        "com.github.shadowsocks.tv\n" +
        "com.nordvpn.android\n" +
        "com.expressvpn.vpn\n" +
        "com.protonvpn.android\n" +
        "free.vpn.unblock.proxy.turbovpn\n" +
        "com.zaneschepke.wireguardautotunnel\n" +
        "moe.nb4a\n" +
        "io.github.dovecoteescapee.byedpi\n" +
        "com.romanvht.byebyedpi\n" +
        "org.aspect.tgwsproxy\n" +
        "org.aspect.tgwsproxy.android\n" +
        "com.termux\n" +
        "io.github.romanvht.byedpi\n" +
        "com.vkturn.proxy";

    private XposedModulePrefs() {}

    public static void ensureDefaults(Context context) {
        PreferenceManager.setDefaultValues(context, PREFS_NAME, Context.MODE_PRIVATE, R.xml.xposed_preferences, false);
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = null;
        if (!preferences.contains(KEY_HIDDEN_VPN_PACKAGES)) {
            editor = preferences
                .edit()
                .putStringSet(KEY_HIDDEN_VPN_PACKAGES, parsePackageSet(DEFAULT_HIDDEN_VPN_PACKAGES));
        }
        if (editor != null) {
            editor.commit();
        }
        export(context);
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static Set<String> getTargetPackages(Context context) {
        return getPackageSet(context, KEY_TARGET_PACKAGES);
    }

    public static Set<String> getHiddenVpnPackages(Context context) {
        return getPackageSet(context, KEY_HIDDEN_VPN_PACKAGES);
    }

    public static Set<String> getDefaultHiddenVpnPackages() {
        return parsePackageSet(DEFAULT_HIDDEN_VPN_PACKAGES);
    }

    public static Set<String> getRecommendedHiddenVpnPackages(Context context) {
        LinkedHashSet<String> recommendedPackages = new LinkedHashSet<>(getDefaultHiddenVpnPackages());
        recommendedPackages.addAll(getInstalledVpnServicePackages(context));
        return recommendedPackages;
    }

    public static Set<String> getRecommendedTargetPackages(Context context) {
        return new LinkedHashSet<>(RuStoreRecommendedAppsAsset.getPackageNames(context));
    }

    public static Set<String> getPackageSet(Context context, String key) {
        SharedPreferences preferences = prefs(context);
        Set<String> stored = preferences.getStringSet(key, null);
        if (stored != null) {
            return new LinkedHashSet<>(stored);
        }
        return parsePackageSet(preferences.getString(key, ""));
    }

    public static void setPackageEnabled(Context context, String key, String packageName, boolean enabled) {
        if (packageName == null || packageName.isBlank()) {
            return;
        }
        Set<String> packages = getPackageSet(context, key);
        if (enabled) {
            packages.add(packageName.trim());
        } else {
            packages.remove(packageName.trim());
        }
        prefs(context).edit().putStringSet(key, packages).commit();
        export(context);
    }

    public static Set<String> getRecommendedDismissedPackages(Context context, String key) {
        String dismissedKey = getRecommendedDismissedKey(key);
        if (dismissedKey == null) {
            return new LinkedHashSet<>();
        }
        Set<String> stored = prefs(context).getStringSet(dismissedKey, null);
        if (stored == null || stored.isEmpty()) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<String> packages = new LinkedHashSet<>(stored);
        packages.remove(context.getPackageName());
        return packages;
    }

    public static void setRecommendedPackageDismissed(
        Context context,
        String key,
        String packageName,
        boolean dismissed
    ) {
        String normalizedPackageName = packageName == null ? "" : packageName.trim();
        String dismissedKey = getRecommendedDismissedKey(key);
        if (
            dismissedKey == null ||
            normalizedPackageName.isEmpty() ||
            normalizedPackageName.equals(context.getPackageName())
        ) {
            return;
        }
        Set<String> packages = getRecommendedDismissedPackages(context, key);
        if (dismissed) {
            packages.add(normalizedPackageName);
        } else {
            packages.remove(normalizedPackageName);
        }
        prefs(context).edit().putStringSet(dismissedKey, new LinkedHashSet<>(packages)).commit();
    }

    public static boolean maybeAutoEnableRecommendedPackage(Context context, String key, String packageName) {
        String normalizedPackageName = packageName == null ? "" : packageName.trim();
        if (normalizedPackageName.isEmpty() || normalizedPackageName.equals(context.getPackageName())) {
            return false;
        }
        Set<String> recommendedPackages = getRecommendedPackages(context, key);
        if (!recommendedPackages.contains(normalizedPackageName)) {
            return false;
        }
        Set<String> dismissedPackages = getRecommendedDismissedPackages(context, key);
        if (dismissedPackages.contains(normalizedPackageName)) {
            return false;
        }
        Set<String> enabledPackages = getPackageSet(context, key);
        if (enabledPackages.contains(normalizedPackageName)) {
            return false;
        }
        enabledPackages.add(normalizedPackageName);
        prefs(context).edit().putStringSet(key, new LinkedHashSet<>(enabledPackages)).commit();
        export(context);
        return true;
    }

    public static boolean syncRecommendedPackages(Context context, String key, Set<String> installedPackages) {
        if (installedPackages == null || installedPackages.isEmpty()) {
            return false;
        }
        Set<String> dismissedPackages = getRecommendedDismissedPackages(context, key);
        Set<String> recommendedPackages = getRecommendedPackages(context, key);
        if (recommendedPackages.isEmpty()) {
            return false;
        }
        Set<String> enabledPackages = getPackageSet(context, key);
        boolean changed = false;
        for (String packageName : installedPackages) {
            String normalizedPackageName = packageName == null ? "" : packageName.trim();
            if (
                normalizedPackageName.isEmpty() ||
                normalizedPackageName.equals(context.getPackageName()) ||
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
            prefs(context).edit().putStringSet(key, new LinkedHashSet<>(enabledPackages)).commit();
            export(context);
        }
        return changed;
    }

    public static String buildPackagesSummary(Context context, String key) {
        int count = countInstalledPackages(context, getPackageSet(context, key));
        if (count <= 0) {
            return context.getString(R.string.xposed_apps_count_zero);
        }
        return context.getString(R.string.xposed_apps_count, count);
    }

    private static int countInstalledPackages(Context context, Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return 0;
        }
        PackageManager packageManager = context.getPackageManager();
        int count = 0;
        for (String packageName : packages) {
            if (isPackageInstalled(packageManager, packageName)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isPackageInstalled(PackageManager packageManager, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0));
            } else {
                packageManager.getApplicationInfo(packageName, 0);
            }
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static Set<String> getInstalledVpnServicePackages(Context context) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        if (context == null) {
            return packages;
        }
        PackageManager packageManager = context.getPackageManager();
        Intent vpnServiceIntent = new Intent(VpnService.SERVICE_INTERFACE);
        List<ResolveInfo> resolveInfos = queryVpnServices(packageManager, vpnServiceIntent);
        for (ResolveInfo resolveInfo : resolveInfos) {
            ServiceInfo serviceInfo = resolveInfo != null ? resolveInfo.serviceInfo : null;
            if (serviceInfo != null && !isBlank(serviceInfo.packageName)) {
                packages.add(serviceInfo.packageName.trim());
            }
        }
        return packages;
    }

    private static List<ResolveInfo> queryVpnServices(PackageManager packageManager, Intent intent) {
        if (packageManager == null || intent == null) {
            return java.util.Collections.emptyList();
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return new ArrayList<>(
                    packageManager.queryIntentServices(
                        intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL)
                    )
                );
            }
            return new ArrayList<>(packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL));
        } catch (RuntimeException ignored) {
            return java.util.Collections.emptyList();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String getRecommendedDismissedKey(String key) {
        if (KEY_TARGET_PACKAGES.equals(key)) {
            return KEY_TARGET_PACKAGES_RECOMMENDED_DISMISSED;
        }
        if (KEY_HIDDEN_VPN_PACKAGES.equals(key)) {
            return KEY_HIDDEN_VPN_PACKAGES_RECOMMENDED_DISMISSED;
        }
        return null;
    }

    private static Set<String> getRecommendedPackages(Context context, String key) {
        if (KEY_TARGET_PACKAGES.equals(key)) {
            return getRecommendedTargetPackages(context);
        }
        if (KEY_HIDDEN_VPN_PACKAGES.equals(key)) {
            return getRecommendedHiddenVpnPackages(context);
        }
        return java.util.Collections.emptySet();
    }

    public static Set<String> parsePackageSet(String value) {
        Set<String> packages = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return packages;
        }
        Arrays.stream(value.split("[,\\n\\r\\t ]+"))
            .map(String::trim)
            .filter(packageName -> !packageName.isEmpty())
            .forEach(packages::add);
        return packages;
    }

    public static void export(Context context) {
        File file = getPrefsFile(context);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.setExecutable(true, false);
            parent.setReadable(true, false);
        }
        if (file.exists()) {
            file.setReadable(true, false);
        }
        exportSystemProperties(context);
    }

    private static File getPrefsFile(Context context) {
        return new File(context.getApplicationInfo().dataDir + "/shared_prefs/" + PREFS_NAME + ".xml");
    }

    private static void exportSystemProperties(Context context) {
        SharedPreferences preferences = prefs(context);
        boolean nativeHookEnabled = preferences.getBoolean(KEY_NATIVE_HOOK_ENABLED, DEFAULT_NATIVE_HOOK_ENABLED);
        String nativeHookValue = nativeHookEnabled ? "1" : "0";
        String procfsHookMode = normalizeProcfsHookMode(
            preferences.getString(KEY_PROCFS_HOOK_MODE, DEFAULT_PROCFS_HOOK_MODE)
        );
        try {
            Process process = new ProcessBuilder(
                "su",
                "-c",
                "setprop " +
                    shellQuote(PROP_NATIVE_HOOK_ENABLED) +
                    " " +
                    shellQuote(nativeHookValue) +
                    " && setprop " +
                    shellQuote(PROP_PROCFS_HOOK_MODE) +
                    " " +
                    shellQuote(procfsHookMode)
            )
                .redirectErrorStream(true)
                .start();
            process.waitFor();
        } catch (Exception ignored) {}
    }

    public static String normalizeProcfsHookMode(String rawValue) {
        if (PROCFS_HOOK_MODE_DISABLED.equals(rawValue)) {
            return PROCFS_HOOK_MODE_DISABLED;
        }
        if (PROCFS_HOOK_MODE_NO_ACCESS.equals(rawValue)) {
            return PROCFS_HOOK_MODE_NO_ACCESS;
        }
        if (PROCFS_HOOK_MODE_FILE_NOT_FOUND.equals(rawValue)) {
            return PROCFS_HOOK_MODE_FILE_NOT_FOUND;
        }
        return PROCFS_HOOK_MODE_DISABLED;
    }

    private static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
