package wings.v.vpnhotspot.bridge;

import android.content.Context;

import java.util.Set;

import wings.v.vpnhotspot.bridge.sharing.VpnHotspotSharingConfig;
import wings.v.vpnhotspot.runtime.VpnHotspotSharingRuntimeConfig;
import wings.v.vpnhotspot.runtime.VpnHotspotUpstreamRuntime;

// Sharing-only entrypoints split out from VpnHotspotBridge so callers that do
// not need hotspot/tether functionality (e.g. core VPN firewall and root
// server init) can avoid linking against this class. When we later bump the
// VPNHotspot submodule past minSdk 28, sharing will move into its own module
// gated by a runtime SDK check; this class becomes the boundary at which the
// move happens, instead of touching every consumer again.
public final class VpnHotspotSharingBridge {
    private VpnHotspotSharingBridge() {
    }

    public static boolean isTetherOffloadEnabled(Context context) {
        return VpnHotspotUpstreamRuntime.isTetherOffloadEnabled(context.getApplicationContext());
    }

    public static void setTetherOffloadEnabled(Context context, boolean enabled) throws Exception {
        VpnHotspotUpstreamRuntime.setTetherOffloadEnabled(context.getApplicationContext(), enabled);
    }

    public static void syncSharing(Context context, Set<String> activeInterfaces, VpnHotspotSharingConfig config) {
        VpnHotspotUpstreamRuntime.syncSharing(
                context.getApplicationContext(),
                activeInterfaces,
                new VpnHotspotSharingRuntimeConfig(
                        config.getUpstreamInterface(),
                        config.getFallbackUpstreamInterface(),
                        config.getExplicitDnsServers(),
                        config.getMasqueradeMode(),
                        config.isDisableIpv6Enabled(),
                        config.isDhcpWorkaroundEnabled()
                )
        );
    }

    public static void stopSharing(Context context) {
        VpnHotspotUpstreamRuntime.stopSharing(context.getApplicationContext());
    }
}
