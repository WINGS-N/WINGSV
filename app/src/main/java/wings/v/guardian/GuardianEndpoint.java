package wings.v.guardian;

import android.content.Context;
import android.net.Network;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import wings.v.BuildConfig;
import wings.v.core.AppPrefs;
import wings.v.core.DirectNetworkConnection;
import wings.v.proto.GuardianProto;

/**
 * Shared panel addressing and identity for every Guardian transport. The gRPC
 * channel reuses the WebSocket endpoint's host, so a device that was provisioned
 * with a wingsv:// link needs no new setting to reach the gRPC service.
 */
public final class GuardianEndpoint {

    public static final int PROTOCOL_VERSION = 1;

    private static final String TAG = "GuardianEndpoint";

    private static final int DEFAULT_GRPC_PORT = 443;

    private GuardianEndpoint() {}

    /** Panel host taken from the configured Guardian URL, empty when unset. */
    @NonNull
    public static String host(@NonNull Context context) {
        URI url = panelUri(context);
        if (url == null) {
            return "";
        }
        String parsed = url.getHost();
        return parsed == null ? "" : parsed;
    }

    /**
     * Panel gRPC port. The panel serves the Guardian service on the same address
     * as its web UI, so a deployment on a non-standard port (which the standalone
     * installer offers) needs no separate setting - the enrollment URL already
     * carries it.
     */
    public static int port(@NonNull Context context) {
        URI url = panelUri(context);
        if (url == null || url.getPort() <= 0) {
            return DEFAULT_GRPC_PORT;
        }
        return url.getPort();
    }

    @Nullable
    private static URI panelUri(@NonNull Context context) {
        String url = AppPrefs.getGuardianWsUrl(context.getApplicationContext());
        if (url.isEmpty()) {
            return null;
        }
        try {
            return URI.create(url);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Opens a channel to the panel's Guardian service. Binding the socket to the
     * physical network keeps the control channel outside the tunnel it manages,
     * which is what lets the panel still reach a device whose VPN is wedged; when
     * no physical network is usable the default route is used instead, and the
     * channel simply rides the tunnel.
     */
    @NonNull
    public static ManagedChannel openChannel(@NonNull Context context, boolean preferPhysical) {
        Context app = context.getApplicationContext();
        OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress(host(app), port(app))
            .useTransportSecurity()
            .keepAliveTime(60L, TimeUnit.SECONDS)
            .keepAliveTimeout(20L, TimeUnit.SECONDS);
        SocketFactory factory = physicalSocketFactory(app, preferPhysical);
        if (factory != null) {
            builder.socketFactory(factory);
        }
        applyPinnedTrust(app, builder);
        return builder.build();
    }

    /**
     * Pins the panel CA when the enrollment link carried one. This is what makes a
     * domain-less panel usable: no public CA will issue for a bare IP, so the
     * system trust store can never verify it and the pin is the only anchor. With
     * no pins stored the channel keeps the default system trust.
     */
    private static void applyPinnedTrust(@NonNull Context context, @NonNull OkHttpChannelBuilder builder) {
        java.util.List<byte[]> pins = AppPrefs.getGuardianCaPins(context);
        if (pins.isEmpty()) {
            return;
        }
        try {
            SSLSocketFactory pinned = GuardianPinnedTrust.socketFactory(pins);
            if (pinned != null) {
                builder.sslSocketFactory(pinned);
            }
        } catch (GeneralSecurityException error) {
            // Leaving the default trust in place would silently drop the pin, so the
            // channel is left to fail against the system store instead.
            Log.w(TAG, "pinned trust unavailable: " + error.getMessage());
        }
    }

    @Nullable
    private static SocketFactory physicalSocketFactory(@NonNull Context context, boolean preferPhysical) {
        if (!preferPhysical) {
            return null;
        }
        Network physical = DirectNetworkConnection.findUsablePhysicalNetwork(context);
        if (physical == null) {
            return null;
        }
        try {
            return physical.getSocketFactory();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @NonNull
    public static GuardianProto.ClientHello buildHello(@NonNull Context context) {
        Context app = context.getApplicationContext();
        byte[] tokenBytes;
        try {
            tokenBytes = Base64.decode(AppPrefs.getGuardianClientTokenB64(app), Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (IllegalArgumentException ignored) {
            tokenBytes = new byte[0];
        }
        wings.v.core.SubscriptionHwidStore.Payload hwid = wings.v.core.SubscriptionHwidStore.getAutomaticPayload(app);
        return GuardianProto.ClientHello.newBuilder()
            .setClientId(AppPrefs.getGuardianClientId(app))
            .setClientToken(ByteString.copyFrom(tokenBytes))
            .setProtocolVersion(PROTOCOL_VERSION)
            .setAppVersion(BuildConfig.VERSION_NAME)
            .setDeviceName(safe(Build.MODEL))
            .setDeviceModel(safe(hwid != null ? hwid.deviceModel : Build.MODEL))
            .setOsVersion(safe(hwid != null ? hwid.verOs : Build.VERSION.RELEASE))
            .setHwid(safe(hwid != null ? hwid.hwid : ""))
            .setLastAppliedConfigVersion(AppPrefs.getGuardianLastAppliedConfigVersion(app))
            .build();
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
