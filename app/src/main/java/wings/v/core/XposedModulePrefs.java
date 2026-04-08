package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
    public static final String KEY_OPEN_SETTINGS = "pref_open_xposed_settings";
    public static final String KEY_ENABLED = "pref_xposed_enabled";
    public static final String KEY_ALL_APPS = "pref_xposed_all_apps";
    public static final String KEY_TARGET_PACKAGES = "pref_xposed_target_packages";
    public static final String KEY_NATIVE_HOOK_ENABLED = "pref_xposed_native_hook_enabled";
    public static final String KEY_HIDE_VPN_APPS = "pref_xposed_hide_vpn_apps";
    public static final String KEY_HIDDEN_VPN_PACKAGES = "pref_xposed_hidden_vpn_packages";

    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_ALL_APPS = true;
    public static final boolean DEFAULT_NATIVE_HOOK_ENABLED = false;
    public static final boolean DEFAULT_HIDE_VPN_APPS = true;
    public static final String DEFAULT_HIDDEN_VPN_PACKAGES =
        "com.github.dyhkwong.sagernet\n" +
        "com.v2ray.ang\n" +
        "org.amnezia.awg\n" +
        "org.amnezia.vpn\n" +
        "de.blinkt.openvpn\n" +
        "net.openvpn.openvpn\n" +
        "com.wireguard.android\n" +
        "com.cloudflare.onedotonedotonedotone\n" +
        "com.psiphon3\n" +
        "app.hiddify.com\n" +
        "io.nekohasekai.sfa\n" +
        "com.nordvpn.android\n" +
        "com.expressvpn.vpn\n" +
        "com.protonvpn.android\n" +
        "free.vpn.unblock.proxy.turbovpn\n" +
        "com.zaneschepke.wireguardautotunnel\n" +
        "moe.nb4a\n" +
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

    public static String buildPackagesSummary(Context context, String key) {
        int count = getPackageSet(context, key).size();
        if (count <= 0) {
            return context.getString(R.string.xposed_apps_count_zero);
        }
        return context.getString(R.string.xposed_apps_count, count);
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
        boolean nativeHookEnabled = prefs(context).getBoolean(KEY_NATIVE_HOOK_ENABLED, DEFAULT_NATIVE_HOOK_ENABLED);
        String value = nativeHookEnabled ? "1" : "0";
        try {
            Process process = new ProcessBuilder(
                "su",
                "-c",
                "setprop " + shellQuote(PROP_NATIVE_HOOK_ENABLED) + " " + shellQuote(value)
            )
                .redirectErrorStream(true)
                .start();
            process.waitFor();
        } catch (Exception ignored) {}
    }

    private static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
