package wings.v.vpnhotspot.bridge.sharing;

import java.util.Objects;

public final class VpnHotspotSharingConfig {
    public static final String MASQUERADE_NONE = "none";
    public static final String MASQUERADE_SIMPLE = "simple";
    public static final String MASQUERADE_NETD = "netd";

    private final String upstreamInterface;
    private final String fallbackUpstreamInterface;
    private final String explicitDnsServers;
    private final String masqueradeMode;
    private final boolean dhcpWorkaroundEnabled;
    private final boolean disableIpv6Enabled;

    public VpnHotspotSharingConfig(
            String upstreamInterface,
            String fallbackUpstreamInterface,
            String explicitDnsServers,
            String masqueradeMode,
            boolean dhcpWorkaroundEnabled,
            boolean disableIpv6Enabled
    ) {
        this.upstreamInterface = normalize(upstreamInterface);
        this.fallbackUpstreamInterface = normalize(fallbackUpstreamInterface);
        this.explicitDnsServers = normalize(explicitDnsServers);
        this.masqueradeMode = normalize(masqueradeMode).isEmpty()
                ? MASQUERADE_SIMPLE
                : normalize(masqueradeMode);
        this.dhcpWorkaroundEnabled = dhcpWorkaroundEnabled;
        this.disableIpv6Enabled = disableIpv6Enabled;
    }

    public static VpnHotspotSharingConfig defaults() {
        return new VpnHotspotSharingConfig("", "", "", MASQUERADE_SIMPLE, false, true);
    }

    public String getUpstreamInterface() {
        return upstreamInterface;
    }

    public String getFallbackUpstreamInterface() {
        return fallbackUpstreamInterface;
    }

    public String getExplicitDnsServers() {
        return explicitDnsServers;
    }

    public String getMasqueradeMode() {
        return masqueradeMode;
    }

    public boolean isDhcpWorkaroundEnabled() {
        return dhcpWorkaroundEnabled;
    }

    public boolean isDisableIpv6Enabled() {
        return disableIpv6Enabled;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VpnHotspotSharingConfig)) {
            return false;
        }
        VpnHotspotSharingConfig that = (VpnHotspotSharingConfig) other;
        return dhcpWorkaroundEnabled == that.dhcpWorkaroundEnabled
                && disableIpv6Enabled == that.disableIpv6Enabled
                && Objects.equals(upstreamInterface, that.upstreamInterface)
                && Objects.equals(fallbackUpstreamInterface, that.fallbackUpstreamInterface)
                && Objects.equals(explicitDnsServers, that.explicitDnsServers)
                && Objects.equals(masqueradeMode, that.masqueradeMode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                upstreamInterface,
                fallbackUpstreamInterface,
                explicitDnsServers,
                masqueradeMode,
                dhcpWorkaroundEnabled,
                disableIpv6Enabled
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
