package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
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
    // Routing rule priority. Must beat Android per-network and per-UID rules,
    // which live at 10000-22000 (per-network around 14000, secure VPN 12000,
    // tethering 18000). Without an explicit pref, our rule slots wherever
    // iproute2 sees fit and per-UID rules end up steering our marked DNS
    // packets through the underlying wlan0/rmnet anyway. 13999 sits just
    // before the per-UID fleet (14000+) and after secure-VPN (12000-13000),
    // and unlike sub-10000 values the Knox/SELinux audit on some Samsung ROMs
    // does not SIGKILL the helper for touching it.
    private static final int RULE_PRIORITY = 13999;
    private static final String CHAIN_PRE = "WINGS_XRAY_TP_PRE";
    private static final String CHAIN_OUT = "WINGS_XRAY_TP_OUT";
    private static final String CHAIN_PRE6 = "WINGS_XRAY_TP_PRE6";
    private static final String CHAIN_OUT6 = "WINGS_XRAY_TP_OUT6";
    private static final String CHAIN_REDIR = "WINGS_XRAY_REDIR";
    // -w 5 — wait up to 5 seconds for the kernel xtables lock so that parallel
    // iptables invocations from vpnhotspot / ConnectivityService / system tether
    // helpers don't collide with ours and trip "lock xtables already used by
    // another app". Without this the chain ends up half-built and Xray loses
    // its inbound traffic, which manifests as an endless upstream-reconnect.
    private static final String IPT4 = "iptables -w 5";
    private static final String IPT6 = "ip6tables -w 5";

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
            IPT4 +
            " -t mangle -A OUTPUT -m owner --uid-owner 0 -j RETURN 2>/dev/null && " +
            IPT4 +
            " -t mangle -D OUTPUT -m owner --uid-owner 0 -j RETURN 2>/dev/null && echo OK";
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

        List<String> localV4 = enumerateLocalAddresses(4);
        List<String> localV6 = enumerateLocalAddresses(6);

        StringBuilder s = new StringBuilder("set -e; ");
        appendStageMarker(s, "route");
        appendRouteSetup(s);

        // IPv4 chains
        appendStageMarker(s, "v4_chains");
        appendChainSetup(s, IPT4, CHAIN_PRE, CHAIN_OUT);
        // PREROUTING: drop reply traffic whose destination is one of this
        // device's own addresses BEFORE any TPROXY/DNS rule. Without this,
        // responses from the proxy server arriving on wlan0/rmnet (dst =
        // our public IP) get TPROXYed back into xray, which sees them as a
        // fresh inbound connection looping to itself and logs "loopback
        // connection detected". The xray runtime then leaks sockets/memory
        // until the helper hits OOM and dies with SIGKILL (exit 137).
        appendStageMarker(s, "v4_pre_localbypass");
        appendLocalAddrBypass(s, IPT4, CHAIN_PRE, localV4);
        appendStageMarker(s, "v4_pre_dns");
        // DNS capture in PREROUTING must precede the RFC1918 dest-bypass: in
        // TPROXY mode there is no VpnService.addDnsServer fallback, so netd
        // queries the underlying network's DHCP DNS (typically 192.168.x.x /
        // 10.x.x.x / 100.64.x.x), all inside the bypass list. Without this
        // rule those queries leak out in plaintext on wlan0/rmnet. Loopback
        // (127/8) DNS stays bypassed in the rule that follows, so on-device
        // resolvers keep working.
        appendDnsCapture(s, IPT4, CHAIN_PRE, tproxyPort, false);
        appendStageMarker(s, "v4_pre_destbypass");
        appendDestBypassV4(s, IPT4, CHAIN_PRE);
        appendStageMarker(s, "v4_pre_tproxy");
        appendTproxyRules(s, IPT4, CHAIN_PRE, tproxyPort);
        appendStageMarker(s, "v4_out_owner");
        // OUTPUT chain: owner exclusion for UID 0 (xray) MUST run before the
        // DNS capture rule, otherwise xray's own bootstrap UDP/53 query to
        // 1.1.1.1 (used to resolve the DoH endpoint host) gets marked, looped
        // back via loopback, TPROXYed back into xray, and we end up in an
        // infinite resolution loop that breaks DoH entirely. We also do NOT
        // exclude wings.v's own UID, so app traffic stays routed via the
        // proxy when probing public IP endpoints.
        appendOwnerExclusion(s, IPT4, CHAIN_OUT, 0);
        appendStageMarker(s, "v4_out_localbypass");
        // Same local-address bypass on OUTPUT: locally generated traffic to
        // our own IPs (e.g. localhost services, mdns, link-local) must not
        // be marked and rerouted via lo into the TPROXY listener.
        appendLocalAddrBypass(s, IPT4, CHAIN_OUT, localV4);
        appendStageMarker(s, "v4_out_dns");
        appendDnsCapture(s, IPT4, CHAIN_OUT, tproxyPort, true);
        appendStageMarker(s, "v4_out_destbypass");
        appendDestBypassV4(s, IPT4, CHAIN_OUT);
        appendStageMarker(s, "v4_out_app");
        appendAppRouting(s, IPT4, CHAIN_OUT, effectiveMode, uids);
        appendStageMarker(s, "v4_hooks");
        appendChainHooks(s, IPT4, CHAIN_PRE, CHAIN_OUT);

        // IPv6 chains
        appendStageMarker(s, "v6_chains");
        appendChainSetup(s, IPT6, CHAIN_PRE6, CHAIN_OUT6);
        appendStageMarker(s, "v6_pre_localbypass");
        appendLocalAddrBypass(s, IPT6, CHAIN_PRE6, localV6);
        appendStageMarker(s, "v6_pre_dns");
        appendDnsCapture(s, IPT6, CHAIN_PRE6, tproxyPort, false);
        appendStageMarker(s, "v6_pre_destbypass");
        appendDestBypassV6(s, IPT6, CHAIN_PRE6);
        appendStageMarker(s, "v6_pre_tproxy");
        appendTproxyRules(s, IPT6, CHAIN_PRE6, tproxyPort);
        appendStageMarker(s, "v6_out_owner");
        appendOwnerExclusion(s, IPT6, CHAIN_OUT6, 0);
        appendStageMarker(s, "v6_out_localbypass");
        appendLocalAddrBypass(s, IPT6, CHAIN_OUT6, localV6);
        appendStageMarker(s, "v6_out_dns");
        appendDnsCapture(s, IPT6, CHAIN_OUT6, tproxyPort, true);
        appendStageMarker(s, "v6_out_destbypass");
        appendDestBypassV6(s, IPT6, CHAIN_OUT6);
        appendStageMarker(s, "v6_out_app");
        appendAppRouting(s, IPT6, CHAIN_OUT6, effectiveMode, uids);
        appendStageMarker(s, "v6_hooks");
        appendChainHooks(s, IPT6, CHAIN_PRE6, CHAIN_OUT6);
        appendStageMarker(s, "done");

        RootShellCommand.exec(context, s.toString());
    }

    public static void revert(@NonNull Context context) throws Exception {
        StringBuilder s = new StringBuilder();
        appendChainTeardown(s, IPT4, CHAIN_PRE, CHAIN_OUT);
        appendChainTeardown(s, IPT6, CHAIN_PRE6, CHAIN_OUT6);
        // Selector-based del (looped to wipe duplicates from previous sessions).
        // Avoids `ip rule del pref <N>` which the Knox audit hook treats as a
        // policy-routing manipulation worth SIGKILLing the helper shell over.
        s
            .append("while ip rule del fwmark ")
            .append(FWMARK)
            .append(" lookup ")
            .append(ROUTE_TABLE)
            .append(" 2>/dev/null; do :; done; true; ");
        s.append("ip route del local default dev lo table ").append(ROUTE_TABLE).append(" 2>/dev/null; ");
        s
            .append("while ip -6 rule del fwmark ")
            .append(FWMARK)
            .append(" lookup ")
            .append(ROUTE_TABLE)
            .append(" 2>/dev/null; do :; done; true; ");
        s.append("ip -6 route del local default dev lo table ").append(ROUTE_TABLE).append(" 2>/dev/null; ");
        s.append("true;");
        RootShellCommand.exec(context, s.toString());
    }

    public static void revertQuietly(@NonNull Context context) {
        try {
            revert(context);
        } catch (Exception ignored) {}
    }

    // Transparently REDIRECT AP/hotspot (tethering) client traffic from the given
    // downstream interfaces into the local xray dokodemo-door redirect inbound
    // (redirectPort), so shared clients are tunneled through the active outbound.
    // Uses iptables nat REDIRECT (not TPROXY): the destination is DNATed to the
    // box before the routing decision, so the app-uid VpnService xray receives it
    // via SO_ORIGINAL_DST without needing IP_TRANSPARENT, and the packet never
    // reaches the gVisor TUN nor the system tether MASQUERADE. IPv4 only (shared
    // clients are IPv4; REDIRECT is an IPv4 nat target).
    public static void applyForwardedRedirect(
        @NonNull Context context,
        Set<String> downstreamInterfaces,
        int redirectPort
    ) throws Exception {
        revertForwardedRedirectQuietly(context);
        if (downstreamInterfaces == null || downstreamInterfaces.isEmpty() || redirectPort <= 0) {
            return;
        }
        StringBuilder s = new StringBuilder("set -e; ");
        s
            .append(IPT4)
            .append(" -t nat -N ")
            .append(CHAIN_REDIR)
            .append(" 2>/dev/null || ")
            .append(IPT4)
            .append(" -t nat -F ")
            .append(CHAIN_REDIR)
            .append("; ");
        for (String iface : downstreamInterfaces) {
            if (TextUtils.isEmpty(iface)) {
                continue;
            }
            appendRedirectRule(s, iface, "tcp", redirectPort);
            appendRedirectRule(s, iface, "udp", redirectPort);
        }
        // Idempotent hook at the TOP of nat PREROUTING (before the system tether /
        // vpnhotspot MASQUERADE chains) so shared-client packets are DNATed to the
        // local dokodemo-door before anything can send them out the physical
        // upstream. -C ... || -I keeps it single and survives `set -e`.
        s
            .append(IPT4)
            .append(" -t nat -C PREROUTING -j ")
            .append(CHAIN_REDIR)
            .append(" 2>/dev/null || ")
            .append(IPT4)
            .append(" -t nat -I PREROUTING -j ")
            .append(CHAIN_REDIR)
            .append("; ");
        s.append("true;");
        RootShellCommand.exec(context, s.toString());
    }

    private static void appendRedirectRule(StringBuilder s, String iface, String proto, int redirectPort) {
        s
            .append(IPT4)
            .append(" -t nat -A ")
            .append(CHAIN_REDIR)
            .append(" -i ")
            .append(iface)
            .append(" -p ")
            .append(proto);
        if ("udp".equals(proto)) {
            // Leave UDP/53 to the AP gateway's own dnsmasq forwarder. DNS over the
            // dokodemo-door followRedirect path does not survive the UDP return
            // trip, so capturing it here black-holes client name resolution; the
            // gateway resolver already answers the clients.
            s.append(" ! --dport 53");
        }
        s.append(" -j REDIRECT --to-ports ").append(redirectPort).append("; ");
    }

    public static void revertForwardedRedirect(@NonNull Context context) throws Exception {
        StringBuilder s = new StringBuilder();
        s.append(IPT4).append(" -t nat -D PREROUTING -j ").append(CHAIN_REDIR).append(" 2>/dev/null; ");
        s.append(IPT4).append(" -t nat -F ").append(CHAIN_REDIR).append(" 2>/dev/null; ");
        s.append(IPT4).append(" -t nat -X ").append(CHAIN_REDIR).append(" 2>/dev/null; ");
        s.append("true;");
        RootShellCommand.exec(context, s.toString());
    }

    public static void revertForwardedRedirectQuietly(@NonNull Context context) {
        try {
            revertForwardedRedirect(context);
        } catch (Exception ignored) {}
    }

    /**
     * Returns true if our PREROUTING/OUTPUT mangle hooks plus fwmark routing rule
     * are still in place. Network changes can flush custom ip rules / routes
     * (especially on AOSP variants that re-derive default state on each switch).
     */
    public static boolean isFullyApplied(@NonNull Context context) {
        String probe =
            IPT4 +
            " -t mangle -C PREROUTING -j " +
            CHAIN_PRE +
            " 2>/dev/null && " +
            IPT4 +
            " -t mangle -C OUTPUT -j " +
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
                IPT4 +
                " -t mangle -nvxL " +
                CHAIN_OUT +
                " 2>/dev/null; " +
                IPT6 +
                " -t mangle -nvxL " +
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
        // Drop the rule by its selector (fwmark + lookup) rather than by pref:
        // on some Samsung ROMs the audit hook SIGKILLs the helper shell when
        // it sees `ip rule del pref <N>`, regardless of the value. Looping
        // del-by-selector also collapses any duplicates the previous session
        // might have left behind.
        appendStageMarker(s, "route.v4_rule_del");
        s
            .append("while ip rule del fwmark ")
            .append(FWMARK)
            .append(" lookup ")
            .append(ROUTE_TABLE)
            .append(" 2>/dev/null; do :; done; true; ");
        appendStageMarker(s, "route.v6_rule_del");
        s
            .append("while ip -6 rule del fwmark ")
            .append(FWMARK)
            .append(" lookup ")
            .append(ROUTE_TABLE)
            .append(" 2>/dev/null; do :; done; true; ");
        appendStageMarker(s, "route.v4_rule_add");
        s
            .append("ip rule add pref ")
            .append(RULE_PRIORITY)
            .append(" fwmark ")
            .append(FWMARK)
            .append(" lookup ")
            .append(ROUTE_TABLE)
            .append(" 2>/dev/null || true; ");
        appendStageMarker(s, "route.v4_route_add");
        s.append("ip route add local default dev lo table ").append(ROUTE_TABLE).append(" 2>/dev/null || true; ");
        appendStageMarker(s, "route.v6_rule_add");
        s
            .append("ip -6 rule add pref ")
            .append(RULE_PRIORITY)
            .append(" fwmark ")
            .append(FWMARK)
            .append(" lookup ")
            .append(ROUTE_TABLE)
            .append(" 2>/dev/null || true; ");
        appendStageMarker(s, "route.v6_route_add");
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

    // Inserts RETURN rules for every address currently assigned to a local
    // interface (wlan/rmnet/lo/...). This is the static equivalent of
    // -m addrtype --dst-type LOCAL: we cannot use xt_addrtype here because
    // on some ROMs (notably Samsung One UI) the SELinux policy kills the
    // helper shell with SIGKILL (exit 137) when iptables tries to load the
    // addrtype match from app_process/su. Network-change reapply is wired
    // through requestXrayTproxyReapplyIfNeeded, so the rule set is refreshed
    // when wlan/rmnet IPs rotate.
    private static void appendLocalAddrBypass(StringBuilder s, String tool, String chain, List<String> addrs) {
        if (addrs == null || addrs.isEmpty()) {
            return;
        }
        for (String addr : addrs) {
            if (TextUtils.isEmpty(addr)) {
                continue;
            }
            s.append(tool).append(" -t mangle -A ").append(chain).append(" -d ").append(addr).append(" -j RETURN; ");
        }
    }

    // Emits a trace breadcrumb into the script's stdout. runRootHelper folds
    // the captured stdout into the IllegalStateException message when exit
    // code is non-zero, so when the helper is SIGKILLed mid-apply we get the
    // name of the last reached stage in the runtime log.
    private static void appendStageMarker(StringBuilder s, String stage) {
        s.append("echo 'TPROXY_APPLY:").append(stage).append("'; ");
    }

    private static List<String> enumerateLocalAddresses(int family) {
        List<String> addrs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return addrs;
            }
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface == null) {
                    continue;
                }
                List<InterfaceAddress> ifAddrs;
                try {
                    ifAddrs = iface.getInterfaceAddresses();
                } catch (Exception ignored) {
                    continue;
                }
                if (ifAddrs == null) {
                    continue;
                }
                for (InterfaceAddress ifAddr : ifAddrs) {
                    if (ifAddr == null) {
                        continue;
                    }
                    InetAddress addr = ifAddr.getAddress();
                    if (addr == null) {
                        continue;
                    }
                    String host;
                    if (family == 4 && addr instanceof Inet4Address) {
                        host = addr.getHostAddress();
                    } else if (family == 6 && addr instanceof Inet6Address) {
                        host = addr.getHostAddress();
                        if (host != null) {
                            int pct = host.indexOf('%');
                            if (pct >= 0) {
                                host = host.substring(0, pct);
                            }
                        }
                    } else {
                        continue;
                    }
                    if (!TextUtils.isEmpty(host) && !addrs.contains(host)) {
                        addrs.add(host);
                    }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "enumerateLocalAddresses(family=" + family + ") failed", error);
        }
        return addrs;
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

    private static void appendDnsCapture(
        StringBuilder s,
        String tool,
        String chain,
        int tproxyPort,
        boolean outputChain
    ) {
        // 127/8 and ::1 stay bypassed (on-device resolvers keep working) by
        // skipping loopback dests here; the RFC1918 bypass that follows can
        // safely RETURN private addresses without leaking DNS, since we already
        // captured port 53 on its way down the chain.
        String exclusion;
        if (tool.startsWith("ip6tables")) {
            exclusion = " ! -d ::1/128";
        } else {
            exclusion = " ! -d 127.0.0.0/8";
        }
        String action;
        if (outputChain) {
            action = " -j MARK --set-mark " + FWMARK;
        } else {
            action = " -j TPROXY --on-port " + tproxyPort + " --tproxy-mark " + FWMARK;
        }
        s
            .append(tool)
            .append(" -t mangle -A ")
            .append(chain)
            .append(" -p udp --dport 53")
            .append(exclusion)
            .append(action)
            .append("; ");
        s
            .append(tool)
            .append(" -t mangle -A ")
            .append(chain)
            .append(" -p tcp --dport 53")
            .append(exclusion)
            .append(action)
            .append("; ");
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
