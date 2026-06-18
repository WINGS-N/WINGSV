package wings.v.vpnhotspot.bridge;

import android.content.Context;

import wings.v.root.server.RootProcessResult;
import wings.v.root.server.RootServerBridge;
import wings.v.vpnhotspot.runtime.VpnHotspotUpstreamRuntime;

// Core bridge: root server lifecycle and VPN firewall setup. Available on
// every supported Android level. Sharing/hotspot entrypoints live in
// VpnHotspotSharingBridge so callers that do not need them can avoid linking
// against tether/hotspot code paths.
public final class VpnHotspotBridge {
    private VpnHotspotBridge() {
    }

    public static void initializeRootServer(Context context) {
        VpnHotspotUpstreamRuntime.initialize(context.getApplicationContext());
        RootServerBridge.initialize(context.getApplicationContext());
    }

    public static void closeExistingRootServer() throws Exception {
        RootServerBridge.closeExisting();
    }

    public static RootProcessResult runRootQuiet(Context context, String command, boolean redirect) throws Exception {
        return RootServerBridge.runQuiet(context.getApplicationContext(), command, redirect);
    }

    public static void setupVpnFirewall(Context context) throws Exception {
        VpnHotspotUpstreamRuntime.setupVpnFirewall(context.getApplicationContext());
    }
}
