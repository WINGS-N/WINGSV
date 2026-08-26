package wings.v.guardian;

import android.content.Context;
import android.net.Network;
import android.os.Build;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
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

    private static final int GRPC_PORT = 443;

    private GuardianEndpoint() {}

    /** Panel host taken from the configured Guardian URL, empty when unset. */
    @NonNull
    public static String host(@NonNull Context context) {
        String url = AppPrefs.getGuardianWsUrl(context.getApplicationContext());
        if (url.isEmpty()) {
            return "";
        }
        try {
            String parsed = URI.create(url).getHost();
            return parsed == null ? "" : parsed;
        } catch (IllegalArgumentException ignored) {
            return "";
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
        OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress(host(app), GRPC_PORT)
            .useTransportSecurity()
            .keepAliveTime(60L, TimeUnit.SECONDS)
            .keepAliveTimeout(20L, TimeUnit.SECONDS);
        SocketFactory factory = physicalSocketFactory(app, preferPhysical);
        if (factory != null) {
            builder.socketFactory(factory);
        }
        return builder.build();
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
