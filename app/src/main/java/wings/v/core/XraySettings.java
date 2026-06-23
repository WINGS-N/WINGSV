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
    public String localProxyListenAddress;
    public boolean httpProxyEnabled;
    public boolean httpProxyAuthEnabled = true;
    public String httpProxyUsername;
    public String httpProxyPassword;
    public int httpProxyPort;
    public String httpProxyListenAddress;
    public String remoteDns;
    public String directDns;
    public boolean ipv6;
    public boolean sniffingEnabled;
    public boolean proxyQuicEnabled;
    public boolean restartOnNetworkChange;
    public int tunUidLookupTimeoutMs;
    // Unknown-UID router: when enabled the gVisor TUN filter applies tunUnknownUidPolicy
    // ("direct" or "drop") to connections whose UID could not be resolved; when disabled
    // such connections are let into the tunnel unchanged.
    public boolean tunUnknownUidRouter = true;
    public String tunUnknownUidPolicy = "direct";
    public ProxyRuntimeMode runtimeMode = ProxyRuntimeMode.VPN;
    public XrayTransportMode transportMode = XrayTransportMode.DIRECT;
    public String wakeProbeMode = WakeProbeMode.PROCESS;

    public static final class WakeProbeMode {

        public static final String PROCESS = "process";
        public static final String HTTP_PROBE = "http_probe";

        private WakeProbeMode() {}

        public static String normalize(String value) {
            if (HTTP_PROBE.equals(value)) {
                return HTTP_PROBE;
            }
            return PROCESS;
        }
    }

    public XraySettings copy() {
        XraySettings copy = new XraySettings();
        copy.allowLan = allowLan;
        copy.allowInsecure = allowInsecure;
        copy.localProxyEnabled = localProxyEnabled;
        copy.localProxyAuthEnabled = localProxyAuthEnabled;
        copy.localProxyUsername = localProxyUsername;
        copy.localProxyPassword = localProxyPassword;
        copy.localProxyPort = localProxyPort;
        copy.localProxyListenAddress = localProxyListenAddress;
        copy.httpProxyEnabled = httpProxyEnabled;
        copy.httpProxyAuthEnabled = httpProxyAuthEnabled;
        copy.httpProxyUsername = httpProxyUsername;
        copy.httpProxyPassword = httpProxyPassword;
        copy.httpProxyPort = httpProxyPort;
        copy.httpProxyListenAddress = httpProxyListenAddress;
        copy.remoteDns = remoteDns;
        copy.directDns = directDns;
        copy.ipv6 = ipv6;
        copy.sniffingEnabled = sniffingEnabled;
        copy.proxyQuicEnabled = proxyQuicEnabled;
        copy.restartOnNetworkChange = restartOnNetworkChange;
        copy.tunUidLookupTimeoutMs = tunUidLookupTimeoutMs;
        copy.tunUnknownUidRouter = tunUnknownUidRouter;
        copy.tunUnknownUidPolicy = tunUnknownUidPolicy;
        copy.runtimeMode = runtimeMode;
        copy.transportMode = transportMode;
        copy.wakeProbeMode = wakeProbeMode;
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
            httpProxyEnabled == that.httpProxyEnabled &&
            httpProxyAuthEnabled == that.httpProxyAuthEnabled &&
            httpProxyPort == that.httpProxyPort &&
            ipv6 == that.ipv6 &&
            sniffingEnabled == that.sniffingEnabled &&
            proxyQuicEnabled == that.proxyQuicEnabled &&
            restartOnNetworkChange == that.restartOnNetworkChange &&
            tunUidLookupTimeoutMs == that.tunUidLookupTimeoutMs &&
            tunUnknownUidRouter == that.tunUnknownUidRouter &&
            java.util.Objects.equals(tunUnknownUidPolicy, that.tunUnknownUidPolicy) &&
            runtimeMode == that.runtimeMode &&
            transportMode == that.transportMode &&
            Objects.equals(wakeProbeMode, that.wakeProbeMode) &&
            Objects.equals(localProxyUsername, that.localProxyUsername) &&
            Objects.equals(localProxyPassword, that.localProxyPassword) &&
            Objects.equals(localProxyListenAddress, that.localProxyListenAddress) &&
            Objects.equals(httpProxyUsername, that.httpProxyUsername) &&
            Objects.equals(httpProxyPassword, that.httpProxyPassword) &&
            Objects.equals(httpProxyListenAddress, that.httpProxyListenAddress) &&
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
            localProxyListenAddress,
            httpProxyEnabled,
            httpProxyAuthEnabled,
            httpProxyUsername,
            httpProxyPassword,
            httpProxyPort,
            httpProxyListenAddress,
            remoteDns,
            directDns,
            ipv6,
            sniffingEnabled,
            proxyQuicEnabled,
            restartOnNetworkChange,
            tunUidLookupTimeoutMs,
            tunUnknownUidRouter,
            tunUnknownUidPolicy,
            runtimeMode,
            transportMode,
            wakeProbeMode
        );
    }
}
