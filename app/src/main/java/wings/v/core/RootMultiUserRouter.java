package wings.v.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs ip-rules that make kernel-WireGuard pick up traffic from all active
 * Android users (primary account + work profile + secondary users), while
 * honouring the per-app bypass list configured in the primary user's WINGSV.
 *
 * <p>Android lays out per-user UIDs as {@code userId * 100000 + appId}. So an
 * app with appId 10142 in the primary user (UID 10142) gets UID 1010142 in
 * user 10, UID 1110142 in user 11 and so on. Without explicit ip-rules, netd
 * may route secondary users through their own routing table that does not
 * carry the wg route, and their traffic silently skips the tunnel.
 *
 * <p>Modes mirror {@link AppPrefs#isAppRoutingBypassEnabled(Context)}:
 * <ul>
 *   <li>{@link Mode#BYPASS}: by default everyone in the user range goes via
 *       the wg table; selected packages are routed via {@code main} to bypass
 *       the tunnel.</li>
 *   <li>{@link Mode#ONLY_SELECTED}: by default everyone in the user range
 *       stays on {@code main}; only the selected packages are diverted to the
 *       wg table.</li>
 * </ul>
 */
public final class RootMultiUserRouter {

    private static final String TAG = "WINGSV/MultiUserRouter";

    // ip-rule priority block we own. Lower number = higher priority, so the
    // per-app override sits ABOVE the broad per-user rule. Both stay above
    // netd's typical app-rule range (10500-13000) but below default catch-all
    // (~32000), which puts them in the right place to take effect.
    private static final int APP_OVERRIDE_PRIORITY_BASE = 17000;
    private static final int USER_RANGE_PRIORITY_BASE = 17500;
    private static final int PRIORITY_BLOCK_END = 18000;

    // App-UID range inside one Android user. System/core UIDs (0-9999) are
    // intentionally skipped: routing system_server / netd / radio through wg
    // breaks captive-portal probes and Mobile-data signalling.
    private static final int APP_UID_MIN_OFFSET = 10000;
    private static final int APP_UID_MAX_OFFSET = 99999;
    private static final int PER_USER_UID_STRIDE = 100_000;

    public enum Mode {
        BYPASS,
        ONLY_SELECTED,
    }

    private RootMultiUserRouter() {}

    public static void apply(
        @NonNull Context context,
        @NonNull String tunnelTable,
        @NonNull Mode mode,
        @NonNull Set<String> selectedPackages
    ) throws Exception {
        if (TextUtils.isEmpty(tunnelTable)) {
            throw new IllegalArgumentException("tunnelTable required");
        }
        List<Integer> userIds = listAndroidUserIds(context);
        Set<Integer> selectedAppIds = resolveAppIds(context, selectedPackages);
        StringBuilder script = new StringBuilder();
        appendClear(script);
        for (int i = 0; i < userIds.size(); i++) {
            int userId = userIds.get(i);
            int rangeMin = userId * PER_USER_UID_STRIDE + APP_UID_MIN_OFFSET;
            int rangeMax = userId * PER_USER_UID_STRIDE + APP_UID_MAX_OFFSET;
            int userPriority = USER_RANGE_PRIORITY_BASE + i;
            String userTable = mode == Mode.BYPASS ? tunnelTable : "main";
            appendAddRule(script, userPriority, rangeMin, rangeMax, userTable);
            int overridePriority = APP_OVERRIDE_PRIORITY_BASE + i;
            String overrideTable = mode == Mode.BYPASS ? "main" : tunnelTable;
            for (int appId : selectedAppIds) {
                int uid = userId * PER_USER_UID_STRIDE + appId;
                appendAddRule(script, overridePriority, uid, uid, overrideTable);
            }
        }
        RootUtils.runRootHelper(context, "shell", script.toString());
    }

    public static void clearQuietly(@NonNull Context context) {
        try {
            StringBuilder script = new StringBuilder();
            appendClear(script);
            RootUtils.runRootHelper(context, "shell", script.toString());
        } catch (Exception ignored) {}
    }

    private static void appendClear(StringBuilder script) {
        // ip-rule does not have a "delete all at priority X" op; loop until the
        // del-by-pref starts failing. -4 / -6 both swept.
        script
            .append("for p in $(seq ")
            .append(APP_OVERRIDE_PRIORITY_BASE)
            .append(' ')
            .append(PRIORITY_BLOCK_END)
            .append("); do ");
        script.append("while ip rule del pref $p 2>/dev/null; do :; done; ");
        script.append("while ip -6 rule del pref $p 2>/dev/null; do :; done; ");
        script.append("done; ");
    }

    private static void appendAddRule(StringBuilder script, int pref, int uidMin, int uidMax, String table) {
        appendAddRuleSingle(script, "ip", pref, uidMin, uidMax, table);
        appendAddRuleSingle(script, "ip -6", pref, uidMin, uidMax, table);
    }

    private static void appendAddRuleSingle(
        StringBuilder script,
        String ipCmd,
        int pref,
        int uidMin,
        int uidMax,
        String table
    ) {
        script
            .append(ipCmd)
            .append(" rule add pref ")
            .append(pref)
            .append(" uidrange ")
            .append(uidMin)
            .append('-')
            .append(uidMax)
            .append(" lookup ")
            .append(RootUtils.shellQuote(table))
            .append(" 2>/dev/null || true; ");
    }

    /**
     * Reads {@code pm list users} under root and returns the list of currently
     * configured Android user ids. Falls back to {@code [0]} on any parse
     * failure so the primary user keeps working.
     */
    static List<Integer> listAndroidUserIds(@NonNull Context context) {
        try {
            String out = RootUtils.runRootHelper(context, "shell", "pm list users 2>/dev/null");
            List<Integer> ids = parseUserIds(out);
            if (!ids.isEmpty()) {
                return ids;
            }
        } catch (Exception error) {
            Log.w(TAG, "pm list users failed: " + error.getMessage());
        }
        return Collections.singletonList(0);
    }

    static List<Integer> parseUserIds(String output) {
        if (TextUtils.isEmpty(output)) {
            return Collections.emptyList();
        }
        // Matches lines like: UserInfo{0:Owner:13} running
        Pattern pattern = Pattern.compile("UserInfo\\{(\\d+):");
        Set<Integer> sorted = new TreeSet<>();
        Matcher matcher = pattern.matcher(output);
        while (matcher.find()) {
            try {
                sorted.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {}
        }
        return new ArrayList<>(sorted);
    }

    private static Set<Integer> resolveAppIds(@NonNull Context context, @NonNull Set<String> packageNames) {
        if (packageNames.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Integer> appIds = new LinkedHashSet<>();
        PackageManager pm = context.getPackageManager();
        for (String packageName : packageNames) {
            if (TextUtils.isEmpty(packageName)) {
                continue;
            }
            try {
                int uid = pm.getApplicationInfo(packageName, 0).uid;
                if (uid > 0) {
                    // Strip the user-id prefix to get the appId (Android assigns
                    // the same appId across users for a given package).
                    appIds.add(uid % PER_USER_UID_STRIDE);
                }
            } catch (Exception error) {
                Log.w(TAG, "Failed to resolve UID for " + packageName + ": " + error.getMessage());
            }
        }
        return appIds;
    }

    public static String describeFromPrefs(@NonNull Context context) {
        Mode mode = AppPrefs.isAppRoutingBypassEnabled(context) ? Mode.BYPASS : Mode.ONLY_SELECTED;
        int packages = AppPrefs.getAppRoutingPackages(context).size();
        return String.format(Locale.ROOT, "mode=%s packages=%d", mode.name().toLowerCase(Locale.ROOT), packages);
    }
}
