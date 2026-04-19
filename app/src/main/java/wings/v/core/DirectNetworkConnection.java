package wings.v.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
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
        Network network = findUsablePhysicalNetwork(context);
        if (network == null) {
            throw new IOException("No usable physical network");
        }
        return (HttpURLConnection) network.openConnection(url);
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
