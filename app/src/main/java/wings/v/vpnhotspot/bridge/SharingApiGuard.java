package wings.v.vpnhotspot.bridge;

import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import wings.v.vpnhotspot.sharing.bridge.VpnHotspotSharingBridge;
import wings.v.vpnhotspot.sharing.bridge.VpnHotspotSharingConfig;
import wings.v.vpnhotspot.sharing.runtime.VpnHotspotTrafficCounter;

// All call sites in app/* go through this guard. Direct references to
// VpnHotspotSharingBridge stay confined to this class; ART class verifier on
// Android 8/9 never loads it because the guarded entry points are never hit
// when SDK_INT < Q.
public final class SharingApiGuard {

    private SharingApiGuard() {}

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    public static boolean isTetherOffloadEnabled(@NonNull Context context) {
        return VpnHotspotSharingBridge.isTetherOffloadEnabled(context);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    public static void setTetherOffloadEnabled(@NonNull Context context, boolean enabled) throws Exception {
        VpnHotspotSharingBridge.setTetherOffloadEnabled(context, enabled);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    public static void syncSharing(
        @NonNull Context context,
        @NonNull Set<String> activeInterfaces,
        @NonNull VpnHotspotSharingConfig config
    ) {
        VpnHotspotSharingBridge.syncSharing(context, activeInterfaces, config);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    public static void stopSharing(@NonNull Context context) {
        VpnHotspotSharingBridge.stopSharing(context);
    }

    @NonNull
    @RequiresApi(Build.VERSION_CODES.Q)
    public static List<VpnHotspotTrafficCounter> readTrafficCounters(@NonNull Context context) {
        return VpnHotspotSharingBridge.readTrafficCounters(context);
    }

    @NonNull
    public static List<VpnHotspotTrafficCounter> readTrafficCountersOrEmpty(@NonNull Context context) {
        if (!isSupported()) return Collections.emptyList();
        return readTrafficCounters(context);
    }
}
