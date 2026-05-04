package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.AvoidCatchingGenericException",
        "PMD.SignatureDeclareThrowsException",
        "PMD.LawOfDemeter",
        "PMD.LongVariable",
        "PMD.GodClass",
        "PMD.CyclomaticComplexity",
        "PMD.NPathComplexity",
        "PMD.CognitiveComplexity",
        "PMD.AvoidUsingHardCodedIP",
        "PMD.SystemPrintln",
    }
)
public final class XrayTproxyRouter {

    private static final String TAG = "XrayTproxyRouter";
    public static final int FWMARK = 0x1;
    public static final int ROUTE_TABLE = 100;
    private static final String CHAIN_PRE = "WINGS_XRAY_TP_PRE";
    private static final String CHAIN_OUT = "WINGS_XRAY_TP_OUT";
    private static final String CHAIN_PRE6 = "WINGS_XRAY_TP_PRE6";
    private static final String CHAIN_OUT6 = "WINGS_XRAY_TP_OUT6";

    private XrayTproxyRouter() {}

    public enum AppRoutingMode {
        NONE,
        BYPASS,
        ONLY_SELECTED,
    }

    /**
     * Detects whether {@code -m owner --uid-owner} matches packets in the OUTPUT mangle chain.
     * Some Android kernels/iptables flavours strip the owner match for UDP, or only match in
     * specific tables. Caller may downgrade to AppRoutingMode.NONE on failure.
     */
    public static boolean isUidOwnerMatchSupported(@NonNull Context context) {
        String probe =
            "iptables -t mangle -A OUTPUT -m owner --uid-owner 0 -j RETURN 2>/dev/null && " +
            "iptables -t mangle -D OUTPUT -m owner --uid-owner 0 -j RETURN 2>/dev/null && echo OK";
        try {
            String result = RootShellCommand.exec(context, probe);
            return result != null && result.contains("OK");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void apply(
        @NonNull Context context,
        int tproxyPort,
        @NonNull AppRoutingMode appRoutingMode,
        @NonNull List<Integer> routedUids
    ) throws Exception {
        revertQuietly(context);

        Set<Integer> uids = new LinkedHashSet<>();
        for (Integer uid : routedUids) {
            if (uid != null && uid > 0) {
                uids.add(uid);
            }
        }

        AppRoutingMode effectiveMode = appRoutingMode;
        if (effectiveMode != AppRoutingMode.NONE && !isUidOwnerMatchSupported(context)) {
            Log.w(TAG, "iptables -m owner --uid-owner is not supported on this kernel; disabling app-routing");
            effectiveMode = AppRoutingMode.NONE;
        }

        StringBuilder s = new StringBuilder("set -e; ");
        appendRouteSetup(s);

        // IPv4 chains
        appendChainSetup(s, "iptables", CHAIN_PRE, CHAIN_OUT);
        appendDestBypassV4(s, "iptables", CHAIN_PRE);
        appendTproxyRules(s, "iptables", CHAIN_PRE, tproxyPort);
        appendDestBypassV4(s, "iptables", CHAIN_OUT);
        // Xray runtime is forked under `su` (UID 0); excluding it here breaks the
        // outbound→TPROXY→Xray→outbound loop. Crucially we do NOT exclude
        // wings.v's own UID — the WINGSV process should see the proxied IP/country
        // when probing public IP endpoints, just like every other routed app.
        appendOwnerExclusion(s, "iptables", CHAIN_OUT, 0);
        appendAppRouting(s, "iptables", CHAIN_OUT, effectiveMode, uids);
        appendChainHooks(s, "iptables", CHAIN_PRE, CHAIN_OUT);

        // IPv6 chains
        appendChainSetup(s, "ip6tables", CHAIN_PRE6, CHAIN_OUT6);
        appendDestBypassV6(s, "ip6tables", CHAIN_PRE6);
        appendTproxyRules(s, "ip6tables", CHAIN_PRE6, tproxyPort);
        appendDestBypassV6(s, "ip6tables", CHAIN_OUT6);
        appendOwnerExclusion(s, "ip6tables", CHAIN_OUT6, 0);
        appendAppRouting(s, "ip6tables", CHAIN_OUT6, effectiveMode, uids);
        appendChainHooks(s, "ip6tables", CHAIN_PRE6, CHAIN_OUT6);

        RootShellCommand.exec(context, s.toString());
    }

    public static void revert(@NonNull Context context) throws Exception {
        StringBuilder s = new StringBuilder();
        appendChainTeardown(s, "iptables", CHAIN_PRE, CHAIN_OUT);
        appendChainTeardown(s, "ip6tables", CHAIN_PRE6, CHAIN_OUT6);
        s.append("ip rule del fwmark ").append(FWMARK).append(" lookup ").append(ROUTE_TABLE).append(" 2>/dev/null; ");
        s.append("ip route del local default dev lo table ").append(ROUTE_TABLE).append(" 2>/dev/null; ");
        s
            .append("ip -6 rule del fwmark ")
            .append(FWMARK)
            .append(" lookup ")
            .append(ROUTE_TABLE)
            .append(" 2>/dev/null; ");
        s.append("ip -6 route del local default dev lo table ").append(ROUTE_TABLE).append(" 2>/dev/null; ");
        s.append("true;");
        RootShellCommand.exec(context, s.toString());
    }

    public static void revertQuietly(@NonNull Context context) {
        try {
            revert(context);
        } catch (Exception ignored) {}
    }

    /**
     * Returns true if our PREROUTING/OUTPUT mangle hooks plus fwmark routing rule
     * are still in place. Network changes can flush custom ip rules / routes
     * (especially on AOSP variants that re-derive default state on each switch).
     */
    public static boolean isFullyApplied(@NonNull Context context) {
        String probe =
            "iptables -t mangle -C PREROUTING -j " +
            CHAIN_PRE +
            " 2>/dev/null && " +
            "iptables -t mangle -C OUTPUT -j " +
            CHAIN_OUT +
            " 2>/dev/null && " +
            "ip rule show | grep -q 'fwmark 0x" +
            Integer.toHexString(FWMARK) +
            " lookup " +
            ROUTE_TABLE +
            "' && " +
            "echo OK";
        try {
            String result = RootShellCommand.exec(context, probe);
            return result != null && result.contains("OK");
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Reads cumulative bytes counted by the {@code MARK} rules in our IPv4 + IPv6
     * OUTPUT mangle chains. Counters are the ones {@code XrayTproxyRouter.apply}
     * created with the chain — they reset to zero on every reapply.
     *
     * Returns 0 if the chains are not in place or if the root shell call fails.
     */
    public static long readMarkBytesQuiet(@NonNull Context context) {
        try {
            String script =
                "iptables -t mangle -nvxL " +
                CHAIN_OUT +
                " 2>/dev/null; " +
                "ip6tables -t mangle -nvxL " +
                CHAIN_OUT6 +
                " 2>/dev/null";
            String output = RootShellCommand.exec(context, script);
            if (output == null) {
                return 0L;
            }
            long total = 0L;
            for (String rawLine : output.split("\n")) {
                String[] parts = rawLine.trim().split("\\s+");
                // Expected layout: pkts bytes target prot opt in out src dst [extras...]
                if (parts.length < 3 || !"MARK".equals(parts[2])) {
                    continue;
                }
                try {
                    total += Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
            return total;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public static List<Integer> resolveRoutedUids(@NonNull Context context, @Nullable Set<String> packageNames) {
        List<Integer> uids = new ArrayList<>();
        if (packageNames == null || packageNames.isEmpty()) {
            return uids;
        }
        for (String packageName : packageNames) {
            if (TextUtils.isEmpty(packageName)) {
                continue;
            }
            try {
                int uid = context.getPackageManager().getApplicationInfo(packageName, 0).uid;
                if (uid > 0) {
                    uids.add(uid);
                }
            } catch (Exception ignored) {}
        }
        return uids;
    }

    private static void appendRouteSetup(StringBuilder s) {
        s
            .append("ip rule add fwmark ")
            .append(FWMARK)
            .append(" lookup ")
            .append(ROUTE_TABLE)
            .append(" 2>/dev/null || true; ");
        s.append("ip route add local default dev lo table ").append(ROUTE_TABLE).append(" 2>/dev/null || true; ");
        s
            .append("ip -6 rule add fwmark ")
            .append(FWMARK)
            .append(" lookup ")
            .append(ROUTE_TABLE)
            .append(" 2>/dev/null || true; ");
        s.append("ip -6 route add local default dev lo table ").append(ROUTE_TABLE).append(" 2>/dev/null || true; ");
    }

    private static void appendChainSetup(StringBuilder s, String tool, String pre, String out) {
        s
            .append(tool)
            .append(" -t mangle -N ")
            .append(pre)
            .append(" 2>/dev/null || ")
            .append(tool)
            .append(" -t mangle -F ")
            .append(pre)
            .append("; ");
        s
            .append(tool)
            .append(" -t mangle -N ")
            .append(out)
            .append(" 2>/dev/null || ")
            .append(tool)
            .append(" -t mangle -F ")
            .append(out)
            .append("; ");
    }

    private static void appendChainTeardown(StringBuilder s, String tool, String pre, String out) {
        s.append(tool).append(" -t mangle -D PREROUTING -j ").append(pre).append(" 2>/dev/null; ");
        s.append(tool).append(" -t mangle -D OUTPUT -j ").append(out).append(" 2>/dev/null; ");
        s.append(tool).append(" -t mangle -F ").append(pre).append(" 2>/dev/null; ");
        s.append(tool).append(" -t mangle -F ").append(out).append(" 2>/dev/null; ");
        s.append(tool).append(" -t mangle -X ").append(pre).append(" 2>/dev/null; ");
        s.append(tool).append(" -t mangle -X ").append(out).append(" 2>/dev/null; ");
    }

    private static void appendDestBypassV4(StringBuilder s, String tool, String chain) {
        appendBypassDest(s, tool, chain, "0.0.0.0/8");
        appendBypassDest(s, tool, chain, "10.0.0.0/8");
        appendBypassDest(s, tool, chain, "100.64.0.0/10");
        appendBypassDest(s, tool, chain, "127.0.0.0/8");
        appendBypassDest(s, tool, chain, "169.254.0.0/16");
        appendBypassDest(s, tool, chain, "172.16.0.0/12");
        appendBypassDest(s, tool, chain, "192.168.0.0/16");
        appendBypassDest(s, tool, chain, "224.0.0.0/4");
        appendBypassDest(s, tool, chain, "240.0.0.0/4");
        appendBypassDest(s, tool, chain, "255.255.255.255/32");
    }

    private static void appendDestBypassV6(StringBuilder s, String tool, String chain) {
        appendBypassDest(s, tool, chain, "::/128");
        appendBypassDest(s, tool, chain, "::1/128");
        appendBypassDest(s, tool, chain, "fc00::/7");
        appendBypassDest(s, tool, chain, "fe80::/10");
        appendBypassDest(s, tool, chain, "ff00::/8");
    }

    private static void appendBypassDest(StringBuilder s, String tool, String chain, String cidr) {
        s.append(tool).append(" -t mangle -A ").append(chain).append(" -d ").append(cidr).append(" -j RETURN; ");
    }

    private static void appendOwnerExclusion(StringBuilder s, String tool, String chain, int uid) {
        s
            .append(tool)
            .append(" -t mangle -A ")
            .append(chain)
            .append(" -m owner --uid-owner ")
            .append(uid)
            .append(" -j RETURN; ");
    }

    private static void appendAppRouting(
        StringBuilder s,
        String tool,
        String chain,
        AppRoutingMode mode,
        Set<Integer> uids
    ) {
        switch (mode) {
            case BYPASS:
                for (Integer uid : uids) {
                    appendOwnerExclusion(s, tool, chain, uid);
                }
                appendOutputMarkAll(s, tool, chain);
                break;
            case ONLY_SELECTED:
                if (uids.isEmpty()) {
                    s.append(tool).append(" -t mangle -A ").append(chain).append(" -j RETURN; ");
                } else {
                    for (Integer uid : uids) {
                        appendOutputMarkForUid(s, tool, chain, uid);
                    }
                    s.append(tool).append(" -t mangle -A ").append(chain).append(" -j RETURN; ");
                }
                break;
            case NONE:
            default:
                appendOutputMarkAll(s, tool, chain);
                break;
        }
    }

    private static void appendTproxyRules(StringBuilder s, String tool, String chain, int tproxyPort) {
        s
            .append(tool)
            .append(" -t mangle -A ")
            .append(chain)
            .append(" -p tcp -j TPROXY --on-port ")
            .append(tproxyPort)
            .append(" --tproxy-mark ")
            .append(FWMARK)
            .append("; ");
        s
            .append(tool)
            .append(" -t mangle -A ")
            .append(chain)
            .append(" -p udp -j TPROXY --on-port ")
            .append(tproxyPort)
            .append(" --tproxy-mark ")
            .append(FWMARK)
            .append("; ");
    }

    private static void appendOutputMarkAll(StringBuilder s, String tool, String chain) {
        s
            .append(tool)
            .append(" -t mangle -A ")
            .append(chain)
            .append(" -p tcp -j MARK --set-mark ")
            .append(FWMARK)
            .append("; ");
        s
            .append(tool)
            .append(" -t mangle -A ")
            .append(chain)
            .append(" -p udp -j MARK --set-mark ")
            .append(FWMARK)
            .append("; ");
    }

    private static void appendOutputMarkForUid(StringBuilder s, String tool, String chain, int uid) {
        s
            .append(tool)
            .append(" -t mangle -A ")
            .append(chain)
            .append(" -p tcp -m owner --uid-owner ")
            .append(uid)
            .append(" -j MARK --set-mark ")
            .append(FWMARK)
            .append("; ");
        s
            .append(tool)
            .append(" -t mangle -A ")
            .append(chain)
            .append(" -p udp -m owner --uid-owner ")
            .append(uid)
            .append(" -j MARK --set-mark ")
            .append(FWMARK)
            .append("; ");
    }

    private static void appendChainHooks(StringBuilder s, String tool, String pre, String out) {
        s
            .append(tool)
            .append(" -t mangle -C PREROUTING -j ")
            .append(pre)
            .append(" 2>/dev/null || ")
            .append(tool)
            .append(" -t mangle -A PREROUTING -j ")
            .append(pre)
            .append("; ");
        s
            .append(tool)
            .append(" -t mangle -C OUTPUT -j ")
            .append(out)
            .append(" 2>/dev/null || ")
            .append(tool)
            .append(" -t mangle -A OUTPUT -j ")
            .append(out)
            .append(";");
    }

    private static final class RootShellCommand {

        private RootShellCommand() {}

        static String exec(Context context, String script) throws Exception {
            String trimmed = script == null ? "" : script.trim();
            if (TextUtils.isEmpty(trimmed)) {
                return "";
            }
            return RootUtils.runRootHelper(context, "shell", trimmed);
        }
    }
}
