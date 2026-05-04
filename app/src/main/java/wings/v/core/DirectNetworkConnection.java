package wings.v.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import wings.v.service.ProxyTunnelService;

/**
 * Opens app control-plane HTTP connections outside the app-owned VPN.
 */
@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.LongVariable",
        "PMD.LawOfDemeter",
        "PMD.UselessParentheses",
    }
)
public final class DirectNetworkConnection {

    private static final String TAG = "DirectNetwork";

    private DirectNetworkConnection() {}

    @NonNull
    public static HttpURLConnection openHttpConnection(@NonNull Context context, @NonNull URL url) throws IOException {
        return openHttpConnection(context, url, false);
    }

    @NonNull
    public static HttpURLConnection openHttpConnection(
        @NonNull Context context,
        @NonNull URL url,
        boolean useTunnelWhenActive
    ) throws IOException {
        if (useTunnelWhenActive && ProxyTunnelService.isActive()) {
            return (HttpURLConnection) url.openConnection();
        }
        String host = url.getHost();
        if (host != null && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"))) {
            return (HttpURLConnection) url.openConnection();
        }
        Network network = findUsablePhysicalNetwork(context);
        if (network == null) {
            // No usable physical Network candidate (rare; e.g., during transient
            // disconnects). The default route still resolves through the kernel,
            // and the VpnService provider's own UID auto-bypasses its own tunnel.
            return (HttpURLConnection) url.openConnection();
        }
        try {
            return (HttpURLConnection) network.openConnection(url);
        } catch (IOException error) {
            // Network.openConnection() internally calls bindSocketToNetwork(),
            // which can throw "Binding socket to network N failed: EPERM" when:
            //   - WINGSV is itself the active VpnService provider and the kernel
            //     restricts explicit binds to underlying physical netIds for VPN
            //     UIDs (would normally need VpnService.protect(fd), unavailable on
            //     a HttpURLConnection-managed socket);
            //   - Background restrictions (Data Saver / app standby) deny binding
            //     to specific networks for non-foreground UIDs;
            //   - Android 12+ refuses binds to networks the app didn't request via
            //     NetworkRequest.
            // The default route is fine in all of these cases — the VPN provider's
            // own UID auto-bypasses its own tunnel at the kernel level, and external
            // VPNs without lockdown also let WINGSV's own UID through.
            Log.w(
                TAG,
                "network.openConnection() failed (" + error.getMessage() + "); " + "falling back to default route"
            );
            return (HttpURLConnection) url.openConnection();
        }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    public static Network findUsablePhysicalNetwork(@NonNull Context context) {
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return null;
        }
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (isUsablePhysicalNetwork(connectivityManager, activeNetwork)) {
                return activeNetwork;
            }
            Network[] networks = connectivityManager.getAllNetworks();
            if (networks == null) {
                return null;
            }
            for (Network network : networks) {
                if (isUsablePhysicalNetwork(connectivityManager, network)) {
                    return network;
                }
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    private static boolean isUsablePhysicalNetwork(
        @NonNull ConnectivityManager connectivityManager,
        @Nullable Network network
    ) {
        if (network == null) {
            return false;
        }
        try {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return false;
            }
            boolean physicalTransport =
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            if (!physicalTransport || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return false;
            }
            return (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            );
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
