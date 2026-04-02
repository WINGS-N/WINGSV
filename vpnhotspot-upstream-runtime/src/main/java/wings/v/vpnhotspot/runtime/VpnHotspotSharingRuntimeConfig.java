package wings.v.vpnhotspot.runtime;

import java.util.Objects;

public final class VpnHotspotSharingRuntimeConfig {
    private final String upstreamInterface;
    private final String fallbackUpstreamInterface;
    private final String explicitDnsServers;
    private final String masqueradeMode;
    private final boolean disableIpv6;
    private final boolean dhcpWorkaroundEnabled;

    public VpnHotspotSharingRuntimeConfig(String upstreamInterface,
                                          String fallbackUpstreamInterface,
                                          String explicitDnsServers,
                                          String masqueradeMode,
                                          boolean disableIpv6,
                                          boolean dhcpWorkaroundEnabled) {
        this.upstreamInterface = upstreamInterface;
        this.fallbackUpstreamInterface = fallbackUpstreamInterface;
        this.explicitDnsServers = explicitDnsServers;
        this.masqueradeMode = masqueradeMode;
        this.disableIpv6 = disableIpv6;
        this.dhcpWorkaroundEnabled = dhcpWorkaroundEnabled;
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

    public boolean isDisableIpv6() {
        return disableIpv6;
    }

    public boolean isDhcpWorkaroundEnabled() {
        return dhcpWorkaroundEnabled;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VpnHotspotSharingRuntimeConfig)) {
            return false;
        }
        VpnHotspotSharingRuntimeConfig that = (VpnHotspotSharingRuntimeConfig) other;
        return disableIpv6 == that.disableIpv6
                && dhcpWorkaroundEnabled == that.dhcpWorkaroundEnabled
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
                disableIpv6,
                dhcpWorkaroundEnabled
        );
    }
}
