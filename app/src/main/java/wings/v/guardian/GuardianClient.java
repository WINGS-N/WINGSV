package wings.v.guardian;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
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
import wings.v.service.ProxyTunnelService;

/**
 * Maintains a single WSS connection to the Guardian panel. Reconnects with
 * exponential backoff; prefers binding to the physical network so traffic
 * bypasses VPN whitelists, falling back to the default route when phy bind
 * fails (which lets the connection ride through the active tunnel).
 */
public final class GuardianClient {

    private static final String TAG = "GuardianClient";
    private static final int PROTOCOL_VERSION = 1;
    private static final long INITIAL_BACKOFF_MS = 3_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;
    private static final long WATCHDOG_INTERVAL_MS = 5_000L;
    private static final long WATCHDOG_SILENCE_LIMIT_MS = 75_000L;
    // Backoff резетим только после того как WS прожил подольше, чем стартовый
    // backoff. Иначе сценарий "успешный ServerHello -> мгновенный close" гасит
    // backoff в 3с и крутит цикл reconnect каждые 3-3 секунды.
    private static final long BACKOFF_RESET_STABLE_MS = 30_000L;

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
    private Runnable watchdog;
    private ConnectivityManager.NetworkCallback networkCallback;
    private long connectedAtMs;
    private long lastFrameAtMs;

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
            // Disable OkHttp's ping/pong watchdog: WS control frames sometimes
            // get mangled by HTTP/2 ingresses. We rely on the application-level
            // Heartbeat that the server bounces back, watched by watchdog().
            .pingInterval(0, TimeUnit.MILLISECONDS)
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
            cancelWatchdog();
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
        // Cancel any leftover heartbeat/watchdog from the prior socket
        // synchronously, so they don't fire on the brand-new socket before
        // its onOpen has had a chance to send ClientHello.
        cancelHeartbeat();
        cancelWatchdog();
        if (socket != null) {
            socket.cancel();
            socket = null;
        }
        connectedAtMs = 0L;
        lastFrameAtMs = 0L;
        OkHttpClient client = buildClientForAttempt();
        Request request = new Request.Builder().url(wsUrl).build();
        Log.i(TAG, "connecting to " + wsUrl + " (phy=" + phyBindActive + ")");
        ProxyTunnelService.writeRuntimeLogLine("[guardian] connecting to " + wsUrl + " (phy=" + phyBindActive + ")");
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
                evaluateNetworkChange(cm, "default-available");
            }

            @Override
            public void onLost(@NonNull Network network) {
                evaluateNetworkChange(cm, "default-lost");
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull android.net.NetworkCapabilities caps) {
                evaluateNetworkChange(cm, "caps-changed");
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {}
    }

    /**
     * Re-evaluates whether to keep the current connection or fall back/up to a
     * better one. Called from any default-network event:
     * <ul>
     *   <li>Tunnel coming up (default may flip to VPN) — if our current bind
     *       was phy and phy is still reachable, keep going. If we were
     *       routing through tunnel and now phy is back, reconnect via phy.</li>
     *   <li>Tunnel going down — if we were routing through tunnel, the
     *       socket's underlying route just disappeared; force reconnect
     *       through phy.</li>
     *   <li>Wi-Fi → mobile handover, etc.</li>
     * </ul>
     */
    private void evaluateNetworkChange(ConnectivityManager cm, String reason) {
        if (stopped) {
            return;
        }
        Network phy = DirectNetworkConnection.findUsablePhysicalNetwork(appContext);
        boolean phyAvailable = phy != null;
        boolean shouldReconnect = false;
        if (socket == null) {
            // Not connected; let the regular schedule run.
            return;
        }
        if (phyAvailable && !phyBindActive) {
            // We were riding through default (probably tunnel); switch to phy.
            shouldReconnect = true;
        } else if (!phyAvailable && phyBindActive) {
            // We were on phy but phy lost. Fall back to default route (which
            // may still work if tunnel is up).
            shouldReconnect = true;
        }
        if (shouldReconnect) {
            Log.i(TAG, "network change (" + reason + ") triggers reconnect (phy=" + phyAvailable + ")");
            ProxyTunnelService.writeRuntimeLogLine(
                "[guardian] network change (" + reason + "), reconnecting (phy=" + phyAvailable + ")"
            );
            forceReconnect();
        }
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

    /**
     * Гасит backoff только если WS прожил подольше {@link #BACKOFF_RESET_STABLE_MS}.
     * Так не схлопываемся обратно в 3с интервал при коннект-успех -> быстрый close
     * -> reconnect-success -> close цикле (например, когда server-side close по
     * ping-timeout, а после успешного hello клиент думает "всё ок, резет").
     */
    private void maybeResetBackoffOnStable() {
        if (connectedAtMs > 0 && System.currentTimeMillis() - connectedAtMs >= BACKOFF_RESET_STABLE_MS) {
            backoffMs = INITIAL_BACKOFF_MS;
        }
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

    private void scheduleWatchdog() {
        cancelWatchdog();
        watchdog = new Runnable() {
            @Override
            public void run() {
                watchdog = null;
                if (socket == null || stopped) {
                    return;
                }
                long silenceMs = lastFrameAtMs > 0 ? System.currentTimeMillis() - lastFrameAtMs : 0L;
                if (silenceMs > WATCHDOG_SILENCE_LIMIT_MS) {
                    ProxyTunnelService.writeRuntimeLogLine(
                        "[guardian] watchdog: " + (silenceMs / 1000L) + "s silence, forcing reconnect"
                    );
                    forceReconnect();
                    return;
                }
                scheduleWatchdog();
            }
        };
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    private void cancelWatchdog() {
        if (watchdog != null) {
            mainHandler.removeCallbacks(watchdog);
            watchdog = null;
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
            .setLastAppliedConfigVersion(AppPrefs.getGuardianLastAppliedConfigVersion(appContext))
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
            connectedAtMs = System.currentTimeMillis();
            lastFrameAtMs = connectedAtMs;
            Log.i(TAG, "ws open");
            ProxyTunnelService.writeRuntimeLogLine("[guardian] ws open, sending hello (phy=" + phyBindActive + ")");
            sendFrame(GuardianProto.Frame.newBuilder().setClientHello(buildHello()).build());
            scheduleHeartbeat();
            scheduleWatchdog();
            String host = "";
            try {
                host = URI.create(wsUrl).getHost();
            } catch (Exception ignored) {}
            final String resolvedHost = host == null ? "" : host;
            mainHandler.post(() -> listener.onConnected(resolvedHost));
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
            lastFrameAtMs = System.currentTimeMillis();
            maybeResetBackoffOnStable();
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
            ProxyTunnelService.writeRuntimeLogLine(
                "[guardian] ws closed " + code + " " + reason + " " + lifetimeAndIdleSummary()
            );
            handleDisconnected();
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            int code = response == null ? 0 : response.code();
            Log.w(TAG, "ws failure: " + msg + " (http=" + code + ")");
            ProxyTunnelService.writeRuntimeLogLine(
                "[guardian] ws failure: " +
                    classifyFailure(t) +
                    " — " +
                    msg +
                    " (http=" +
                    code +
                    ") " +
                    lifetimeAndIdleSummary()
            );
            handleDisconnected();
        }
    }

    private String lifetimeAndIdleSummary() {
        long now = System.currentTimeMillis();
        long lifetime = connectedAtMs > 0 ? (now - connectedAtMs) / 1000L : 0L;
        long idle = lastFrameAtMs > 0 ? (now - lastFrameAtMs) / 1000L : 0L;
        return "lifetime=" + lifetime + "s idle=" + idle + "s phy=" + phyBindActive;
    }

    private static String classifyFailure(Throwable t) {
        if (t == null) return "unknown";
        String name = t.getClass().getSimpleName();
        String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (t instanceof java.net.SocketTimeoutException || msg.contains("timed out") || msg.contains("timeout")) {
            return "timeout";
        }
        if (msg.contains("ping")) return "ping";
        if (msg.contains("network is unreachable") || msg.contains("ehostunreach")) return "unreachable";
        if (msg.contains("software caused connection abort") || msg.contains("connection reset")) return "reset";
        if (msg.contains("socket closed") || msg.contains("canceled") || msg.contains("cancelled")) return "cancelled";
        return name;
    }

    private void handleInbound(GuardianProto.Frame frame) {
        switch (frame.getPayloadCase()) {
            case SERVER_HELLO:
                if (frame.getServerHello().getAccepted()) {
                    ProxyTunnelService.writeRuntimeLogLine("[guardian] server hello accepted");
                } else {
                    String reason = frame.getServerHello().getErrorMessage();
                    Log.w(TAG, "server hello rejected: " + reason);
                    ProxyTunnelService.writeRuntimeLogLine("[guardian] server hello rejected: " + reason);
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
            case ERROR: {
                String code = frame.getError().getCode();
                Log.w(TAG, "server error: " + code + ": " + frame.getError().getMessage());
                ProxyTunnelService.writeRuntimeLogLine(
                    "[guardian] server error " + code + ": " + frame.getError().getMessage()
                );
                if ("revoked".equalsIgnoreCase(code) || "not_found".equalsIgnoreCase(code)) {
                    mainHandler.post(() -> {
                        wings.v.core.AppPrefs.clearGuardian(appContext);
                        wings.v.guardian.GuardianRunner.stopAll(appContext);
                    });
                }
                break;
            }
            default:
                break;
        }
    }

    private void handleDisconnected() {
        cancelHeartbeat();
        cancelWatchdog();
        socket = null;
        mainHandler.post(listener::onDisconnected);
        if (!stopped) {
            scheduleReconnect();
        }
    }
}
