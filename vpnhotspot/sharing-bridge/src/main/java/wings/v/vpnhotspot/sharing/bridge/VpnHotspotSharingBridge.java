package wings.v.vpnhotspot.sharing.bridge;

import android.content.Context;

import java.util.Set;

import wings.v.vpnhotspot.sharing.runtime.VpnHotspotSharingRuntimeConfig;
import wings.v.vpnhotspot.sharing.runtime.VpnHotspotSharingRuntimeEntry;

// Sharing-only entrypoints. Lives in a dedicated module so callers that need
// only core VPN firewall / root server init never link against tether/hotspot
// code. After the VPNHotspot submodule bump past minSdk 28 we gate access to
// this class behind a runtime SDK check; the boundary stays put either way.
public final class VpnHotspotSharingBridge {
    private VpnHotspotSharingBridge() {
    }

    public static boolean isTetherOffloadEnabled(Context context) {
        return VpnHotspotSharingRuntimeEntry.isTetherOffloadEnabled(context.getApplicationContext());
    }

    public static void setTetherOffloadEnabled(Context context, boolean enabled) throws Exception {
        VpnHotspotSharingRuntimeEntry.setTetherOffloadEnabled(context.getApplicationContext(), enabled);
    }

    public static void syncSharing(Context context, Set<String> activeInterfaces, VpnHotspotSharingConfig config) {
        VpnHotspotSharingRuntimeEntry.syncSharing(
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
        VpnHotspotSharingRuntimeEntry.stopSharing(context.getApplicationContext());
    }
}
