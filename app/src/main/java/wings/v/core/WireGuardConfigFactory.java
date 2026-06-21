package wings.v.core;

import android.content.Context;
import com.wireguard.config.Config;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class WireGuardConfigFactory {

    /**
     * IP ranges of stream.wb.ru / wbstream01-el.wb.ru (LiveKit signalling + TURN
     * relays). When WB Stream backend is active the wb-stream-proxy itself uses
     * these endpoints to reach the SFU; if they're routed through the very
     * tunnel that the proxy serves, the connection collapses into a loop.
     * The livekit-android-sdk does not expose a transport.Net hook to
     * protect()-mark its pion ICE sockets, so we exclude the network at the
     * routing layer instead via split-routing in AllowedIPs.
     */
    private static final String[] WB_STREAM_EXCLUDED_CIDRS = { "185.62.200.0/24", "194.1.214.0/24" };

    private WireGuardConfigFactory() {}

    public static Config build(Context context, ProxySettings settings) throws Exception {
        return build(context, settings, true);
    }

    public static Config build(Context context, ProxySettings settings, boolean includeAppRouting) throws Exception {
        String peerEndpoint =
            settings != null && settings.backendType == BackendType.WIREGUARD
                ? settings.endpoint
                : settings.localEndpoint;
        StringBuilder builder = new StringBuilder();
        builder.append("[Interface]\n");
        builder.append("PrivateKey = ").append(settings.wgPrivateKey).append('\n');
        builder.append("Address = ").append(settings.wgAddresses).append('\n');
        if (!isBlank(settings.wgDns)) {
            builder.append("DNS = ").append(settings.wgDns).append('\n');
        }
        if (includeAppRouting) {
            Set<String> routedPackages =
                context != null ? new TreeSet<>(AppPrefs.getEffectiveAppRoutingPackages(context)) : new TreeSet<>();
            if (!routedPackages.isEmpty()) {
                String joinedPackages = String.join(", ", routedPackages);
                AppRoutingMode mode = AppPrefs.getAppRoutingMode(context);
                if (mode == AppRoutingMode.WHITELIST) {
                    builder.append("IncludedApplications = ").append(joinedPackages).append('\n');
                } else {
                    builder.append("ExcludedApplications = ").append(joinedPackages).append('\n');
                }
            }
        }
        builder.append("MTU = ").append(settings.wgMtu).append("\n\n");
        builder.append("[Peer]\n");
        builder.append("PublicKey = ").append(settings.wgPublicKey).append('\n');
        if (!isBlank(settings.wgPresharedKey)) {
            builder.append("PresharedKey = ").append(settings.wgPresharedKey).append('\n');
        }
        String allowedIps = settings.wgAllowedIps;
        if (settings.backendType != null && settings.backendType.isWbStreamBackend()) {
            allowedIps = applySplitRouteExclusions(allowedIps, WB_STREAM_EXCLUDED_CIDRS);
        }
        builder.append("AllowedIPs = ").append(allowedIps).append('\n');
        builder.append("Endpoint = ").append(peerEndpoint).append('\n');

        return Config.parse(new ByteArrayInputStream(builder.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Subtracts each excludedCidr from the IPv4 portion of allowedIps and
     * returns a comma-separated CIDR list that explicitly covers everything
     * except the excluded ranges. IPv6 entries (and unparseable ones) pass
     * through unchanged.
     */
    private static String applySplitRouteExclusions(String allowedIps, String[] excludedCidrs) {
        List<long[]> ipv4Allowed = new ArrayList<>();
        List<String> passthrough = new ArrayList<>();
        for (String raw : allowedIps == null ? new String[0] : allowedIps.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            long[] range = parseIpv4Cidr(entry);
            if (range == null) {
                passthrough.add(entry);
                continue;
            }
            ipv4Allowed.add(range);
        }
        for (String exclude : excludedCidrs) {
            long[] excludeRange = parseIpv4Cidr(exclude);
            if (excludeRange == null) {
                continue;
            }
            List<long[]> next = new ArrayList<>();
            for (long[] allowed : ipv4Allowed) {
                next.addAll(subtractRange(allowed, excludeRange));
            }
            ipv4Allowed = next;
        }
        StringBuilder out = new StringBuilder();
        for (long[] range : ipv4Allowed) {
            for (String cidr : rangeToCidrs(range[0], range[1])) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(cidr);
            }
        }
        for (String entry : passthrough) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(entry);
        }
        return out.toString();
    }

    /** Returns [start, end] inclusive or null if entry is not IPv4 CIDR / single. */
    private static long[] parseIpv4Cidr(String entry) {
        String value = entry;
        int prefix = 32;
        int slash = value.indexOf('/');
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(value.substring(slash + 1).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
            value = value.substring(0, slash).trim();
        }
        String[] octets = value.split("\\.");
        if (octets.length != 4) {
            return null;
        }
        long ip = 0L;
        for (String octet : octets) {
            try {
                int n = Integer.parseInt(octet.trim());
                if (n < 0 || n > 255) {
                    return null;
                }
                ip = (ip << 8) | n;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (prefix < 0 || prefix > 32) {
            return null;
        }
        long mask = prefix == 0 ? 0L : (-1L << (32 - prefix)) & 0xffffffffL;
        long start = ip & mask;
        long end = start | (~mask & 0xffffffffL);
        return new long[] { start, end };
    }

    private static List<long[]> subtractRange(long[] allowed, long[] exclude) {
        List<long[]> result = new ArrayList<>(2);
        long allowedStart = allowed[0];
        long allowedEnd = allowed[1];
        long excludeStart = exclude[0];
        long excludeEnd = exclude[1];
        if (excludeEnd < allowedStart || excludeStart > allowedEnd) {
            result.add(allowed);
            return result;
        }
        if (excludeStart > allowedStart) {
            result.add(new long[] { allowedStart, excludeStart - 1 });
        }
        if (excludeEnd < allowedEnd) {
            result.add(new long[] { excludeEnd + 1, allowedEnd });
        }
        return result;
    }

    private static List<String> rangeToCidrs(long start, long end) {
        List<String> result = new ArrayList<>();
        long current = start;
        while (current <= end) {
            int trailingZeros = current == 0 ? 32 : Long.numberOfTrailingZeros(current);
            int sizeLog = trailingZeros;
            int maxFitLog = 32;
            long span = end - current + 1L;
            while ((1L << maxFitLog) > span) {
                maxFitLog--;
            }
            int prefixLen = 32 - Math.min(sizeLog, maxFitLog);
            long blockSize = 1L << (32 - prefixLen);
            result.add(formatIpv4(current) + "/" + prefixLen);
            current += blockSize;
        }
        return result;
    }

    private static String formatIpv4(long ip) {
        return ((ip >>> 24) & 0xff) + "." + ((ip >>> 16) & 0xff) + "." + ((ip >>> 8) & 0xff) + "." + (ip & 0xff);
    }
}
