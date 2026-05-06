package wings.v.guardian;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.protobuf.InvalidProtocolBufferException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import wings.v.BuildConfig;
import wings.v.core.AppPrefs;
import wings.v.core.DirectNetworkConnection;
import wings.v.core.SubscriptionHwidStore;
import wings.v.proto.GuardianProto;
import wings.v.proto.WingsvProto;

/**
 * Maintains a single WSS connection to the Guardian panel. Reconnects with
 * exponential backoff; prefers binding to the physical network so traffic
 * bypasses VPN whitelists, falling back to the default route when phy bind
 * fails (which lets the connection ride through the active tunnel).
 */
public final class GuardianClient {

    private static final String TAG = "GuardianClient";
    private static final int PROTOCOL_VERSION = 1;
    private static final long INITIAL_BACKOFF_MS = 5_000L;
    private static final long MAX_BACKOFF_MS = 300_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;

    private final Context appContext;
    private final Handler mainHandler;
    private final OkHttpClient defaultClient;
    private final SecureRandom random = new SecureRandom();
    private final Listener listener;

    private OkHttpClient currentClient;
    private WebSocket socket;
    private boolean phyBindActive;
    private long backoffMs = INITIAL_BACKOFF_MS;
    private boolean stopped;
    private Runnable scheduledConnect;
    private Runnable heartbeat;
    private ConnectivityManager.NetworkCallback networkCallback;

    public interface Listener {
        void onConnected(String host);

        void onDisconnected();

        void onCommand(GuardianProto.Command command);

        void onConfigPush(GuardianProto.ConfigPush push);

        void onLogControl(GuardianProto.LogControl control);

        void requestStateReport();
    }

    public GuardianClient(@NonNull Context context, @NonNull Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.defaultClient = new OkHttpClient.Builder()
            .pingInterval(HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build();
    }

    public void start() {
        mainHandler.post(() -> {
            stopped = false;
            attemptConnect(0L);
            registerNetworkCallback();
        });
    }

    public void stop() {
        mainHandler.post(() -> {
            stopped = true;
            cancelScheduledConnect();
            cancelHeartbeat();
            if (socket != null) {
                socket.cancel();
                socket = null;
            }
            unregisterNetworkCallback();
            listener.onDisconnected();
        });
    }

    public void sendFrame(@NonNull GuardianProto.Frame frame) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        ws.send(ByteString.of(frame.toByteArray()));
    }

    private void attemptConnect(long delayMs) {
        cancelScheduledConnect();
        scheduledConnect = () -> {
            scheduledConnect = null;
            if (stopped) {
                return;
            }
            connectNow();
        };
        if (delayMs <= 0L) {
            mainHandler.post(scheduledConnect);
        } else {
            mainHandler.postDelayed(scheduledConnect, delayMs);
        }
    }

    private void cancelScheduledConnect() {
        if (scheduledConnect != null) {
            mainHandler.removeCallbacks(scheduledConnect);
            scheduledConnect = null;
        }
    }

    private void connectNow() {
        String wsUrl = AppPrefs.getGuardianWsUrl(appContext);
        if (wsUrl.isEmpty()) {
            return;
        }
        OkHttpClient client = buildClientForAttempt();
        Request request = new Request.Builder().url(wsUrl).build();
        Log.i(TAG, "connecting to " + wsUrl + " (phy=" + phyBindActive + ")");
        currentClient = client;
        socket = client.newWebSocket(request, new GuardianListener(wsUrl));
    }

    private OkHttpClient buildClientForAttempt() {
        Network phy = DirectNetworkConnection.findUsablePhysicalNetwork(appContext);
        if (phy != null) {
            try {
                phyBindActive = true;
                return defaultClient.newBuilder().socketFactory(phy.getSocketFactory()).build();
            } catch (Exception ignored) {
                phyBindActive = false;
            }
        }
        phyBindActive = false;
        return defaultClient;
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = appContext.getSystemService(ConnectivityManager.class);
        if (cm == null || networkCallback != null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && cm.getNetworkCapabilities(network) != null) {
                    if (!phyBindActive && socket != null) {
                        Log.i(TAG, "physical network became available, reconnecting");
                        forceReconnect();
                    }
                }
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {}
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager cm = appContext.getSystemService(ConnectivityManager.class);
        if (cm != null && networkCallback != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {}
        }
        networkCallback = null;
    }

    private void forceReconnect() {
        if (socket != null) {
            socket.cancel();
            socket = null;
        }
        attemptConnect(0L);
    }

    private void scheduleReconnect() {
        long jitter = (long) (backoffMs * 0.2 * (random.nextDouble() - 0.5));
        long delay = Math.max(1_000L, backoffMs + jitter);
        attemptConnect(delay);
        backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
    }

    private void resetBackoff() {
        backoffMs = INITIAL_BACKOFF_MS;
    }

    private void scheduleHeartbeat() {
        cancelHeartbeat();
        heartbeat = new Runnable() {
            @Override
            public void run() {
                heartbeat = null;
                if (socket == null || stopped) {
                    return;
                }
                sendFrame(
                    GuardianProto.Frame.newBuilder()
                        .setHeartbeat(GuardianProto.Heartbeat.newBuilder().setTsMs(System.currentTimeMillis()))
                        .build()
                );
                scheduleHeartbeat();
            }
        };
        mainHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS);
    }

    private void cancelHeartbeat() {
        if (heartbeat != null) {
            mainHandler.removeCallbacks(heartbeat);
            heartbeat = null;
        }
    }

    private GuardianProto.ClientHello buildHello() {
        byte[] tokenBytes;
        try {
            tokenBytes = Base64.decode(
                AppPrefs.getGuardianClientTokenB64(appContext),
                Base64.NO_WRAP | Base64.URL_SAFE
            );
        } catch (IllegalArgumentException ignored) {
            tokenBytes = new byte[0];
        }
        SubscriptionHwidStore.Payload hwidPayload = SubscriptionHwidStore.getAutomaticPayload(appContext);
        return GuardianProto.ClientHello.newBuilder()
            .setClientId(AppPrefs.getGuardianClientId(appContext))
            .setClientToken(com.google.protobuf.ByteString.copyFrom(tokenBytes))
            .setProtocolVersion(PROTOCOL_VERSION)
            .setAppVersion(BuildConfig.VERSION_NAME)
            .setDeviceName(safe(Build.MODEL))
            .setDeviceModel(safe(hwidPayload != null ? hwidPayload.deviceModel : Build.MODEL))
            .setOsVersion(safe(hwidPayload != null ? hwidPayload.verOs : Build.VERSION.RELEASE))
            .setHwid(safe(hwidPayload != null ? hwidPayload.hwid : ""))
            .build();
    }

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    private final class GuardianListener extends WebSocketListener {

        private final String wsUrl;

        GuardianListener(String wsUrl) {
            this.wsUrl = wsUrl;
        }

        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            Log.i(TAG, "ws open");
            sendFrame(GuardianProto.Frame.newBuilder().setClientHello(buildHello()).build());
            scheduleHeartbeat();
            String host = "";
            try {
                host = URI.create(wsUrl).getHost();
            } catch (Exception ignored) {}
            final String resolvedHost = host == null ? "" : host;
            mainHandler.post(() -> listener.onConnected(resolvedHost));
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
            try {
                GuardianProto.Frame frame = GuardianProto.Frame.parseFrom(bytes.toByteArray());
                handleInbound(frame);
            } catch (InvalidProtocolBufferException error) {
                Log.w(TAG, "ws bad frame: " + error.getMessage());
            }
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            Log.i(TAG, "ws closed " + code + " " + reason);
            handleDisconnected();
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
            Log.w(TAG, "ws failure: " + t.getMessage());
            handleDisconnected();
        }
    }

    private void handleInbound(GuardianProto.Frame frame) {
        switch (frame.getPayloadCase()) {
            case SERVER_HELLO:
                if (frame.getServerHello().getAccepted()) {
                    resetBackoff();
                } else {
                    Log.w(TAG, "server hello rejected: " + frame.getServerHello().getErrorMessage());
                    if (socket != null) {
                        socket.cancel();
                    }
                }
                break;
            case HEARTBEAT:
                // pong handled by server's heartbeat reply; no-op locally.
                break;
            case CONFIG_PUSH:
                mainHandler.post(() -> listener.onConfigPush(frame.getConfigPush()));
                break;
            case LOG_CONTROL:
                mainHandler.post(() -> listener.onLogControl(frame.getLogControl()));
                break;
            case COMMAND:
                mainHandler.post(() -> listener.onCommand(frame.getCommand()));
                break;
            case ERROR:
                Log.w(TAG, "server error: " + frame.getError().getCode() + ": " + frame.getError().getMessage());
                break;
            default:
                break;
        }
    }

    private void handleDisconnected() {
        cancelHeartbeat();
        socket = null;
        mainHandler.post(listener::onDisconnected);
        if (!stopped) {
            scheduleReconnect();
        }
    }
}
