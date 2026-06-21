package wings.v.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
        OFF,
        BYPASS,
        ONLY_SELECTED,
    }

    public static Mode modeFromPrefs(@NonNull Context context) {
        AppRoutingMode mode = AppPrefs.getAppRoutingMode(context);
        if (mode == AppRoutingMode.OFF) {
            return Mode.OFF;
        }
        if (mode == AppRoutingMode.WHITELIST) {
            return Mode.ONLY_SELECTED;
        }
        return Mode.BYPASS;
    }

    private RootMultiUserRouter() {}

    // Сериализуем все apply/applyFilterOnly/clearQuietly: stop'ы могут запускать
    // teardown в фоновом потоке через runFastStopCleanupStep с timeout'ом, и без
    // этого монитора новый apply из startXrayRuntime/applyKernelWg... успевает
    // отработать, после чего отстающая фоновая чистка сносит свежий chain.
    private static final Object SCRIPT_LOCK = new Object();

    // Имя iptables-цепочки для нашего OUTPUT-фильтра. Своя цепочка нужна чтобы
    // teardown был чистым: дропнуть chain - и всё, не выискивать конкретные
    // правила, которые сами могли быть инжектированы netd'ом между нашими.
    private static final String FILTER_CHAIN = "wingsv_tunblock_out";

    public static void apply(
        @NonNull Context context,
        @NonNull String tunnelTable,
        @NonNull String tunnelIface,
        @NonNull Mode mode,
        @NonNull Set<String> selectedPackages,
        @NonNull List<Integer> systemProxyPorts
    ) throws Exception {
        if (TextUtils.isEmpty(tunnelTable)) {
            throw new IllegalArgumentException("tunnelTable required");
        }
        Mode effectiveMode = mode == Mode.OFF ? Mode.BYPASS : mode;
        Set<String> effectivePackages = mode == Mode.OFF ? java.util.Collections.emptySet() : selectedPackages;
        List<Integer> userIds = listAndroidUserIds(context);
        Set<Integer> selectedAppIds = resolveAppIds(context, effectivePackages);
        int ownUid = context.getApplicationInfo().uid;
        StringBuilder script = new StringBuilder();
        appendClear(script);
        for (int i = 0; i < userIds.size(); i++) {
            int userId = userIds.get(i);
            int rangeMin = userId * PER_USER_UID_STRIDE + APP_UID_MIN_OFFSET;
            int rangeMax = userId * PER_USER_UID_STRIDE + APP_UID_MAX_OFFSET;
            int userPriority = USER_RANGE_PRIORITY_BASE + i;
            String userTable = effectiveMode == Mode.BYPASS ? tunnelTable : "main";
            appendAddRule(script, userPriority, rangeMin, rangeMax, userTable);
            int overridePriority = APP_OVERRIDE_PRIORITY_BASE + i;
            String overrideTable = effectiveMode == Mode.BYPASS ? "main" : tunnelTable;
            for (int appId : selectedAppIds) {
                int uid = userId * PER_USER_UID_STRIDE + appId;
                appendAddRule(script, overridePriority, uid, uid, overrideTable);
            }
        }
        if (!TextUtils.isEmpty(tunnelIface) || !systemProxyPorts.isEmpty()) {
            appendFilterRules(script, tunnelIface, userIds, selectedAppIds, effectiveMode, ownUid, systemProxyPorts);
        }
        synchronized (SCRIPT_LOCK) {
            RootUtils.runRootHelper(context, "shell", script.toString());
        }
    }

    public static void clearQuietly(@NonNull Context context) {
        try {
            StringBuilder script = new StringBuilder();
            appendClear(script);
            synchronized (SCRIPT_LOCK) {
                RootUtils.runRootHelper(context, "shell", script.toString());
            }
        } catch (Exception ignored) {}
    }

    /**
     * Применяет только iptables OUTPUT-фильтр (без ip-rule per-user routing).
     * Используется для backend'ов, где маршрутизацию делает Android (Xray-
     * VpnService, AmneziaWG-go), а нам нужно только закрыть split-tunnel дыру:
     * tunneled UID не должен биндиться к underlying network, excluded UID не
     * должен достучаться до tun.
     */
    public static void applyFilterOnly(
        @NonNull Context context,
        @Nullable String tunnelIface,
        @NonNull Mode mode,
        @NonNull Set<String> selectedPackages,
        @NonNull List<Integer> systemProxyPorts
    ) throws Exception {
        if (TextUtils.isEmpty(tunnelIface) && systemProxyPorts.isEmpty()) {
            // Без tun-имени и без локальных system-proxy портов резать нечего.
            return;
        }
        Mode effectiveMode = mode == Mode.OFF ? Mode.BYPASS : mode;
        Set<String> effectivePackages = mode == Mode.OFF ? java.util.Collections.emptySet() : selectedPackages;
        List<Integer> userIds = listAndroidUserIds(context);
        Set<Integer> selectedAppIds = resolveAppIds(context, effectivePackages);
        int ownUid = context.getApplicationInfo().uid;
        StringBuilder script = new StringBuilder();
        appendFilterTeardown(script);
        appendFilterRules(script, tunnelIface, userIds, selectedAppIds, effectiveMode, ownUid, systemProxyPorts);
        synchronized (SCRIPT_LOCK) {
            RootUtils.runRootHelper(context, "shell", script.toString());
        }
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
        appendFilterTeardown(script);
    }

    private static void appendFilterTeardown(StringBuilder script) {
        // Снимаем jump'ы из OUTPUT (могло быть несколько от прошлых попыток
        // повторного apply), потом флашим и удаляем собственную цепочку.
        for (String cmd : new String[] { "iptables", "ip6tables" }) {
            script
                .append("while ")
                .append(cmd)
                .append(" -D OUTPUT -j ")
                .append(FILTER_CHAIN)
                .append(" 2>/dev/null; do :; done; ");
            script.append(cmd).append(" -F ").append(FILTER_CHAIN).append(" 2>/dev/null; ");
            script.append(cmd).append(" -X ").append(FILTER_CHAIN).append(" 2>/dev/null; ");
        }
        script.append("true; ");
    }

    private static void appendFilterRules(
        StringBuilder script,
        @Nullable String tunnelIface,
        List<Integer> userIds,
        Set<Integer> selectedAppIds,
        Mode mode,
        int ownUid,
        List<Integer> systemProxyPorts
    ) {
        // Многонаправленный OUTPUT-фильтр:
        //   - исключённые UID, пробующие достучаться до tun -> REJECT
        //   - tunneled UID, пробующие выйти НЕ через tun (например через
        //     Network.bindSocket(underlying)) -> REJECT
        //   - excluded UID, пробующие достучаться до системного PAC/HTTP прокси
        //     на 127.0.0.1:port (com.android.proxyhandler и подобные) -> REJECT.
        //     Через системный прокси трафик отдаётся от UID 10103, которого
        //     наши bypass-правила не касаются - и пакет в итоге уходит через
        //     туннель, выдавая несовпадение IP с прямым исходом приложения.
        // bindSocket остаётся успешным (это лишь mark на сокете), а на
        // connect/send пакет ловит REJECT и приложение получает ECONNREFUSED
        // через underlying.
        boolean hasTun = !TextUtils.isEmpty(tunnelIface);
        String quotedIface = hasTun ? RootUtils.shellQuote(tunnelIface) : "";
        for (String cmd : new String[] { "iptables", "ip6tables" }) {
            String loopbackAddr = "iptables".equals(cmd) ? "127.0.0.0/8" : "::1";
            script.append(cmd).append(" -N ").append(FILTER_CHAIN).append(" 2>/dev/null; ");

            // Свой собственный UID всегда RETURN ПЕРЕД loopback-пропуском - иначе
            // proxy-port REJECT блоки ниже срубят наши же подключения к PAC.
            // Системные UID (<10000) и так не попадают под --uid-owner блоки
            // ниже, потому что они вне APP_UID_MIN_OFFSET..APP_UID_MAX_OFFSET.
            if (ownUid > 0) {
                script
                    .append(cmd)
                    .append(" -A ")
                    .append(FILTER_CHAIN)
                    .append(" -m owner --uid-owner ")
                    .append(ownUid)
                    .append(" -j RETURN 2>/dev/null; ");
            }

            // Блок 0: system-proxy порты на loopback'е (PAC handler и др.).
            // Идёт ДО общего lo RETURN, иначе loopback-allow проглатывает.
            if (!systemProxyPorts.isEmpty()) {
                appendSystemProxyRejects(script, cmd, loopbackAddr, userIds, selectedAppIds, mode, systemProxyPorts);
            }

            // Loopback всегда пропускаем (после system-proxy блока). Без этого
            // ломаются local proxy, системные daemon'ы и любая IPC.
            script.append(cmd).append(" -A ").append(FILTER_CHAIN).append(" -o lo -j RETURN 2>/dev/null; ");

            // Блок 1: пакеты, идущие через tun. REJECT для тех, кто не должен
            // туда ходить, RETURN для остальных - чтобы блок 2 их уже не трогал.
            if (hasTun) {
                if (mode == Mode.BYPASS) {
                    for (int userId : userIds) {
                        for (int appId : selectedAppIds) {
                            int uid = userId * PER_USER_UID_STRIDE + appId;
                            script
                                .append(cmd)
                                .append(" -A ")
                                .append(FILTER_CHAIN)
                                .append(" -o ")
                                .append(quotedIface)
                                .append(" -m owner --uid-owner ")
                                .append(uid)
                                .append(" -j REJECT 2>/dev/null; ");
                        }
                    }
                } else {
                    for (int userId : userIds) {
                        for (int appId : selectedAppIds) {
                            int uid = userId * PER_USER_UID_STRIDE + appId;
                            script
                                .append(cmd)
                                .append(" -A ")
                                .append(FILTER_CHAIN)
                                .append(" -o ")
                                .append(quotedIface)
                                .append(" -m owner --uid-owner ")
                                .append(uid)
                                .append(" -j RETURN 2>/dev/null; ");
                        }
                        int rangeMin = userId * PER_USER_UID_STRIDE + APP_UID_MIN_OFFSET;
                        int rangeMax = userId * PER_USER_UID_STRIDE + APP_UID_MAX_OFFSET;
                        script
                            .append(cmd)
                            .append(" -A ")
                            .append(FILTER_CHAIN)
                            .append(" -o ")
                            .append(quotedIface)
                            .append(" -m owner --uid-owner ")
                            .append(rangeMin)
                            .append('-')
                            .append(rangeMax)
                            .append(" -j REJECT 2>/dev/null; ");
                    }
                }
                script
                    .append(cmd)
                    .append(" -A ")
                    .append(FILTER_CHAIN)
                    .append(" -o ")
                    .append(quotedIface)
                    .append(" -j RETURN 2>/dev/null; ");

                // Блок 2: пакеты, идущие НЕ через tun и НЕ через lo. Здесь режем
                // tunneled UIDs, чтобы Network.bindSocket(underlying) обламывался.
                if (mode == Mode.BYPASS) {
                    for (int userId : userIds) {
                        for (int appId : selectedAppIds) {
                            int uid = userId * PER_USER_UID_STRIDE + appId;
                            script
                                .append(cmd)
                                .append(" -A ")
                                .append(FILTER_CHAIN)
                                .append(" -m owner --uid-owner ")
                                .append(uid)
                                .append(" -j RETURN 2>/dev/null; ");
                        }
                        int rangeMin = userId * PER_USER_UID_STRIDE + APP_UID_MIN_OFFSET;
                        int rangeMax = userId * PER_USER_UID_STRIDE + APP_UID_MAX_OFFSET;
                        script
                            .append(cmd)
                            .append(" -A ")
                            .append(FILTER_CHAIN)
                            .append(" -m owner --uid-owner ")
                            .append(rangeMin)
                            .append('-')
                            .append(rangeMax)
                            .append(" -j REJECT 2>/dev/null; ");
                    }
                } else {
                    for (int userId : userIds) {
                        for (int appId : selectedAppIds) {
                            int uid = userId * PER_USER_UID_STRIDE + appId;
                            script
                                .append(cmd)
                                .append(" -A ")
                                .append(FILTER_CHAIN)
                                .append(" -m owner --uid-owner ")
                                .append(uid)
                                .append(" -j REJECT 2>/dev/null; ");
                        }
                    }
                }
            }

            // Хук безусловный - chain сам разруливает направление через -o.
            script.append(cmd).append(" -A OUTPUT -j ").append(FILTER_CHAIN).append(" 2>/dev/null; ");
        }
        script.append("true; ");
    }

    private static void appendSystemProxyRejects(
        StringBuilder script,
        String cmd,
        String loopbackAddr,
        List<Integer> userIds,
        Set<Integer> selectedAppIds,
        Mode mode,
        List<Integer> systemProxyPorts
    ) {
        for (Integer port : systemProxyPorts) {
            if (port == null || port <= 0 || port > 65535) {
                continue;
            }
            if (mode == Mode.BYPASS) {
                // Excluded UIDs не должны ходить в системный proxy: трафик
                // через него уйдёт в туннель и выдаст другой IP.
                for (int userId : userIds) {
                    for (int appId : selectedAppIds) {
                        int uid = userId * PER_USER_UID_STRIDE + appId;
                        script
                            .append(cmd)
                            .append(" -A ")
                            .append(FILTER_CHAIN)
                            .append(" -d ")
                            .append(loopbackAddr)
                            .append(" -p tcp --dport ")
                            .append(port)
                            .append(" -m owner --uid-owner ")
                            .append(uid)
                            .append(" -j REJECT 2>/dev/null; ");
                    }
                }
            } else {
                // ONLY_SELECTED: всё что НЕ в selected (== bypassed) -> REJECT.
                for (int userId : userIds) {
                    for (int appId : selectedAppIds) {
                        int uid = userId * PER_USER_UID_STRIDE + appId;
                        script
                            .append(cmd)
                            .append(" -A ")
                            .append(FILTER_CHAIN)
                            .append(" -d ")
                            .append(loopbackAddr)
                            .append(" -p tcp --dport ")
                            .append(port)
                            .append(" -m owner --uid-owner ")
                            .append(uid)
                            .append(" -j RETURN 2>/dev/null; ");
                    }
                    int rangeMin = userId * PER_USER_UID_STRIDE + APP_UID_MIN_OFFSET;
                    int rangeMax = userId * PER_USER_UID_STRIDE + APP_UID_MAX_OFFSET;
                    script
                        .append(cmd)
                        .append(" -A ")
                        .append(FILTER_CHAIN)
                        .append(" -d ")
                        .append(loopbackAddr)
                        .append(" -p tcp --dport ")
                        .append(port)
                        .append(" -m owner --uid-owner ")
                        .append(rangeMin)
                        .append('-')
                        .append(rangeMax)
                        .append(" -j REJECT 2>/dev/null; ");
                }
            }
        }
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
        Mode mode = modeFromPrefs(context);
        int packages = AppPrefs.getActiveAppRoutingPackages(context).size();
        return String.format(Locale.ROOT, "mode=%s packages=%d", mode.name().toLowerCase(Locale.ROOT), packages);
    }
}
