package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import java.util.Locale;
import java.util.Set;

/**
 * Bumps the TTL/HL of packets traversing tether interfaces by +1 in both
 * directions. Carriers that detect tethered traffic by the TTL drop the
 * inner device causes (1 hop) will instead see a TTL matching direct
 * mobile traffic, so the hotspot is not flagged as tethering.
 *
 * The rule set mirrors evdenis/tether_unblock - PREROUTING for traffic
 * arriving from the tethered device, POSTROUTING for traffic leaving back
 * to it. Requires root and the xt_HL/xt_hashlimit kernel module.
 */
public final class SharingTtlHider {

    private static final String CHAIN_V4 = "wingsv_sharing_ttl";
    private static final String CHAIN_V6 = "wingsv_sharing_hl";

    private SharingTtlHider() {}

    public static void apply(Context context, Set<String> tetherInterfaces) throws Exception {
        if (!RootUtils.isRootAccessGranted(context)) return;
        StringBuilder script = new StringBuilder();
        appendTeardown(script);
        if (tetherInterfaces == null || tetherInterfaces.isEmpty()) {
            RootUtils.runRootHelper(context, "shell", script.toString());
            return;
        }
        script.append("iptables  -t mangle -N ").append(CHAIN_V4).append(" 2>/dev/null; ");
        script.append("ip6tables -t mangle -N ").append(CHAIN_V6).append(" 2>/dev/null; ");
        for (String iface : tetherInterfaces) {
            if (TextUtils.isEmpty(iface)) continue;
            String quoted = RootUtils.shellQuote(iface);
            script
                .append(
                    String.format(Locale.ROOT, "iptables  -t mangle -A %s -i %s -j TTL --ttl-inc 1; ", CHAIN_V4, quoted)
                )
                .append(
                    String.format(Locale.ROOT, "iptables  -t mangle -A %s -o %s -j TTL --ttl-inc 1; ", CHAIN_V4, quoted)
                )
                .append(
                    String.format(
                        Locale.ROOT,
                        "ip6tables -t mangle -A %s ! -p icmpv6 -i %s -j HL --hl-inc 1; ",
                        CHAIN_V6,
                        quoted
                    )
                )
                .append(
                    String.format(
                        Locale.ROOT,
                        "ip6tables -t mangle -A %s ! -p icmpv6 -o %s -j HL --hl-inc 1; ",
                        CHAIN_V6,
                        quoted
                    )
                );
        }
        script.append("iptables  -t mangle -I PREROUTING  -j ").append(CHAIN_V4).append("; ");
        script.append("iptables  -t mangle -I POSTROUTING -j ").append(CHAIN_V4).append("; ");
        script.append("ip6tables -t mangle -I PREROUTING  -j ").append(CHAIN_V6).append("; ");
        script.append("ip6tables -t mangle -I POSTROUTING -j ").append(CHAIN_V6).append("; ");
        script.append("true;");
        RootUtils.runRootHelper(context, "shell", script.toString());
    }

    public static void clearQuietly(Context context) {
        if (!RootUtils.isRootAccessGranted(context)) return;
        try {
            StringBuilder script = new StringBuilder();
            appendTeardown(script);
            RootUtils.runRootHelper(context, "shell", script.toString());
        } catch (Exception ignored) {}
    }

    private static void appendTeardown(StringBuilder script) {
        for (String[] entry : new String[][] { { "iptables", CHAIN_V4 }, { "ip6tables", CHAIN_V6 } }) {
            String cmd = entry[0];
            String chain = entry[1];
            script
                .append("while ")
                .append(cmd)
                .append(" -t mangle -D PREROUTING  -j ")
                .append(chain)
                .append(" 2>/dev/null; do :; done; ");
            script
                .append("while ")
                .append(cmd)
                .append(" -t mangle -D POSTROUTING -j ")
                .append(chain)
                .append(" 2>/dev/null; do :; done; ");
            script.append(cmd).append(" -t mangle -F ").append(chain).append(" 2>/dev/null; ");
            script.append(cmd).append(" -t mangle -X ").append(chain).append(" 2>/dev/null; ");
        }
        script.append("true; ");
    }
}
