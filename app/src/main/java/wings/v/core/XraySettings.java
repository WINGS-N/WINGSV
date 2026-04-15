package wings.v.core;

import java.util.Objects;

public final class XraySettings {

    public boolean allowLan;
    public boolean allowInsecure;
    public boolean localProxyEnabled;
    public boolean localProxyAuthEnabled = true;
    public String localProxyUsername;
    public String localProxyPassword;
    public int localProxyPort;
    public String remoteDns;
    public String directDns;
    public boolean ipv6;
    public boolean sniffingEnabled;
    public boolean proxyQuicEnabled;
    public boolean restartOnNetworkChange;
    public XrayTransportMode transportMode = XrayTransportMode.DIRECT;

    public XraySettings copy() {
        XraySettings copy = new XraySettings();
        copy.allowLan = allowLan;
        copy.allowInsecure = allowInsecure;
        copy.localProxyEnabled = localProxyEnabled;
        copy.localProxyAuthEnabled = localProxyAuthEnabled;
        copy.localProxyUsername = localProxyUsername;
        copy.localProxyPassword = localProxyPassword;
        copy.localProxyPort = localProxyPort;
        copy.remoteDns = remoteDns;
        copy.directDns = directDns;
        copy.ipv6 = ipv6;
        copy.sniffingEnabled = sniffingEnabled;
        copy.proxyQuicEnabled = proxyQuicEnabled;
        copy.restartOnNetworkChange = restartOnNetworkChange;
        copy.transportMode = transportMode;
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof XraySettings)) {
            return false;
        }
        XraySettings that = (XraySettings) other;
        return (
            allowLan == that.allowLan &&
            allowInsecure == that.allowInsecure &&
            localProxyEnabled == that.localProxyEnabled &&
            localProxyAuthEnabled == that.localProxyAuthEnabled &&
            localProxyPort == that.localProxyPort &&
            ipv6 == that.ipv6 &&
            sniffingEnabled == that.sniffingEnabled &&
            proxyQuicEnabled == that.proxyQuicEnabled &&
            restartOnNetworkChange == that.restartOnNetworkChange &&
            transportMode == that.transportMode &&
            Objects.equals(localProxyUsername, that.localProxyUsername) &&
            Objects.equals(localProxyPassword, that.localProxyPassword) &&
            Objects.equals(remoteDns, that.remoteDns) &&
            Objects.equals(directDns, that.directDns)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            allowLan,
            allowInsecure,
            localProxyEnabled,
            localProxyAuthEnabled,
            localProxyUsername,
            localProxyPassword,
            localProxyPort,
            remoteDns,
            directDns,
            ipv6,
            sniffingEnabled,
            proxyQuicEnabled,
            restartOnNetworkChange,
            transportMode
        );
    }
}
