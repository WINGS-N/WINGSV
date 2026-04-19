package wings.v.vpnhotspot.bridge;

import android.content.Context;

import wings.v.root.server.RootProcessResult;
import wings.v.root.server.RootServerBridge;
import wings.v.vpnhotspot.bridge.sharing.VpnHotspotSharingConfig;
import wings.v.vpnhotspot.runtime.VpnHotspotUpstreamRuntime;
import wings.v.vpnhotspot.runtime.VpnHotspotSharingRuntimeConfig;

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

    public static boolean isTetherOffloadEnabled(Context context) {
        return VpnHotspotUpstreamRuntime.isTetherOffloadEnabled(context.getApplicationContext());
    }

    public static void setTetherOffloadEnabled(Context context, boolean enabled) throws Exception {
        VpnHotspotUpstreamRuntime.setTetherOffloadEnabled(context.getApplicationContext(), enabled);
    }

    public static void syncSharing(Context context, java.util.Set<String> activeInterfaces, VpnHotspotSharingConfig config) {
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
