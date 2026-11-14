package wings.v.guardian;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import wings.v.core.AppPrefs;
import wings.v.core.DirectNetworkConnection;
import wings.v.proto.GuardianGrpc;
import wings.v.proto.GuardianProto;
import wings.v.service.ProxyTunnelService;

/**
 * Live Guardian channel: one bidirectional gRPC stream to the panel, same Frame envelope
 * both ways.
 *
 * <p>Reconnects with exponential backoff and prefers a socket bound to the physical
 * network - the channel that manages the tunnel must not depend on that tunnel being
 * healthy. When phy bind keeps failing it falls back to the default route for a while and
 * lets the connection ride the tunnel instead.
 *
 * <p>No application-level heartbeat or watchdog: HTTP/2 keepalive proves the channel and
 * a dead stream arrives as onError, so there is nothing left for a frame-silence timer to
 * catch
 */
public final class GuardianClient {

    private static final String TAG = "GuardianClient";
    private static final long INITIAL_BACKOFF_MS = 3_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    // Backoff resets only after a stream outlives the starting delay. Otherwise a
    // "ServerHello then immediate close" loop collapses it back to 3s forever and we
    // reconnect every few seconds
    private static final long BACKOFF_RESET_STABLE_MS = 30_000L;
    // Consecutive phy-bound failures before we start going out over the default route.
    // With VK TURN + WireGuard a phy-bound socket sometimes hangs in the operator's core
    // filter, and then the tunnel itself is the only path that works
    private static final int PHY_FAILURES_BEFORE_TUNNEL_FALLBACK = 2;
    // How long we keep starting on the default route after phy proved broken, instead of
    // paying for more phy timeouts. Re-evaluated on each network change and on the first
    // stable stream
    private static final long TUNNEL_FALLBACK_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final long SHUTDOWN_WAIT_SECONDS = 2L;

    private final Context appContext;
    private final Handler mainHandler;
    private final Listener listener;

    private ManagedChannel channel;
    private StreamObserver<GuardianProto.Frame> outbound;
    // Frames are dropped until the panel accepts the hello: the stream is ordered, so
    // anything sent earlier arrives ahead of the ServerHello and gets rejected as
    // unexpected
    private volatile boolean accepted;
    private boolean phyBindActive;
    private int phyFailureStreak;
    private boolean tunnelFallbackActive;
    private long backoffMs = INITIAL_BACKOFF_MS;
    private boolean stopped;
    private Runnable scheduledConnect;
    private ConnectivityManager.NetworkCallback networkCallback;
    private long connectedAtMs;

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
    }

    public void start() {
        mainHandler.post(() -> {
            stopped = false;
            // The hint survives process death, so a freshly spawned client does not have
            // to learn again that phy bind is broken. Only meaningful while a VPN is up -
            // without one the default route is phy anyway
            long until = AppPrefs.getGuardianTunnelFallbackUntilMs(appContext);
            boolean hintFresh = until > 0L && System.currentTimeMillis() < until;
            if (hintFresh && isDefaultRouteVpn()) {
                tunnelFallbackActive = true;
            } else if (until > 0L) {
                AppPrefs.setGuardianTunnelFallbackUntilMs(appContext, 0L);
            }
            attemptConnect(0L);
            registerNetworkCallback();
        });
    }

    public void stop() {
        mainHandler.post(() -> {
            stopped = true;
            cancelScheduledConnect();
            closeStream();
            unregisterNetworkCallback();
            listener.onDisconnected();
        });
    }

    public void sendFrame(@NonNull GuardianProto.Frame frame) {
        StreamObserver<GuardianProto.Frame> stream = outbound;
        if (stream == null || !accepted) {
            return;
        }
        try {
            synchronized (this) {
                stream.onNext(frame);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "send failed: " + error.getMessage());
        }
    }

    private void connectNow() {
        String host = GuardianEndpoint.host(appContext);
        if (host.isEmpty()) {
            return;
        }
        closeStream();
        connectedAtMs = 0L;
        accepted = false;
        phyBindActive = !tunnelFallbackActive && DirectNetworkConnection.findUsablePhysicalNetwork(appContext) != null;
        Log.i(TAG, "connecting to " + host + " (phy=" + phyBindActive + ")");
        ProxyTunnelService.writeRuntimeLogLine("[guardian] connecting to " + host + " (phy=" + phyBindActive + ")");

        ManagedChannel open = GuardianEndpoint.openChannel(appContext, !tunnelFallbackActive);
        channel = open;
        StreamObserver<GuardianProto.Frame> stream = GuardianGrpc.newStub(open).session(new SessionObserver(host));
        outbound = stream;
        try {
            synchronized (this) {
                stream.onNext(
                    GuardianProto.Frame.newBuilder().setClientHello(GuardianEndpoint.buildHello(appContext)).build()
                );
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "hello failed: " + error.getMessage());
            onStreamClosed("hello failed: " + error.getMessage());
        }
    }

    private void closeStream() {
        StreamObserver<GuardianProto.Frame> stream = outbound;
        outbound = null;
        accepted = false;
        if (stream != null) {
            try {
                synchronized (this) {
                    stream.onCompleted();
                }
            } catch (RuntimeException ignored) {
                // Already torn down by the transport
            }
        }
        ManagedChannel open = channel;
        channel = null;
        if (open != null) {
            open.shutdownNow();
            try {
                open.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Called on any stream end; schedules the next attempt unless we are stopping */
    private void onStreamClosed(@Nullable String reason) {
        mainHandler.post(() -> {
            boolean wasAccepted = accepted;
            closeStream();
            if (stopped) {
                return;
            }
            if (wasAccepted) {
                listener.onDisconnected();
            }
            noteAttemptOutcome(wasAccepted);
            maybeResetBackoffOnStable();
            scheduleReconnect(reason);
        });
    }

    /**
     * A phy-bound attempt that never got accepted counts against phy. Enough in a row and
     * the next attempts go out over the default route
     */
    private void noteAttemptOutcome(boolean wasAccepted) {
        if (wasAccepted || !phyBindActive) {
            return;
        }
        phyFailureStreak++;
        if (phyFailureStreak < PHY_FAILURES_BEFORE_TUNNEL_FALLBACK || tunnelFallbackActive) {
            return;
        }
        tunnelFallbackActive = true;
        AppPrefs.setGuardianTunnelFallbackUntilMs(appContext, System.currentTimeMillis() + TUNNEL_FALLBACK_TTL_MS);
        Log.i(TAG, "phy bind failed " + phyFailureStreak + " times, falling back to the default route");
        ProxyTunnelService.writeRuntimeLogLine("[guardian] phy bind failing, falling back to the default route");
    }

    private void maybeResetBackoffOnStable() {
        if (connectedAtMs <= 0L || System.currentTimeMillis() - connectedAtMs < BACKOFF_RESET_STABLE_MS) {
            return;
        }
        backoffMs = INITIAL_BACKOFF_MS;
        phyFailureStreak = 0;
        // A stream that lasted on phy proves phy bind works again, hint included
        if (!tunnelFallbackActive && AppPrefs.getGuardianTunnelFallbackUntilMs(appContext) > 0L) {
            AppPrefs.setGuardianTunnelFallbackUntilMs(appContext, 0L);
        }
    }

    private void scheduleReconnect(@Nullable String reason) {
        long jitter = (long) (Math.random() * 1_000L);
        long delay = Math.max(1_000L, backoffMs + jitter);
        Log.i(TAG, "reconnect in " + delay + "ms (" + reason + ")");
        attemptConnect(delay);
        backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
    }

    private void attemptConnect(long delayMs) {
        cancelScheduledConnect();
        scheduledConnect = () -> {
            scheduledConnect = null;
            if (!stopped) {
                connectNow();
            }
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

    private boolean isDefaultRouteVpn() {
        ConnectivityManager manager = appContext.getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return false;
        }
        try {
            Network active = manager.getActiveNetwork();
            if (active == null) {
                return false;
            }
            NetworkCapabilities caps = manager.getNetworkCapabilities(active);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager manager = appContext.getSystemService(ConnectivityManager.class);
        if (manager == null || networkCallback != null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                mainHandler.post(() -> evaluateNetworkChange("available"));
            }

            @Override
            public void onLost(@NonNull Network network) {
                mainHandler.post(() -> evaluateNetworkChange("lost"));
            }
        };
        try {
            manager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            networkCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager manager = appContext.getSystemService(ConnectivityManager.class);
        if (manager == null || networkCallback == null) {
            return;
        }
        try {
            manager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // Already gone
        }
        networkCallback = null;
    }

    /**
     * A real network change is a fresh chance for phy: wipe the fallback memory and
     * rebuild the stream whenever the route we are on is no longer the route we would pick
     */
    private void evaluateNetworkChange(String reason) {
        if (stopped) {
            return;
        }
        boolean phyAvailable = DirectNetworkConnection.findUsablePhysicalNetwork(appContext) != null;
        if (tunnelFallbackActive || phyFailureStreak > 0) {
            tunnelFallbackActive = false;
            phyFailureStreak = 0;
            if (AppPrefs.getGuardianTunnelFallbackUntilMs(appContext) > 0L) {
                AppPrefs.setGuardianTunnelFallbackUntilMs(appContext, 0L);
            }
        }
        if (outbound == null || phyAvailable == phyBindActive) {
            return;
        }
        Log.i(TAG, "network change (" + reason + ") triggers reconnect (phy=" + phyAvailable + ")");
        ProxyTunnelService.writeRuntimeLogLine(
            "[guardian] network change (" + reason + "), reconnecting (phy=" + phyAvailable + ")"
        );
        backoffMs = INITIAL_BACKOFF_MS;
        closeStream();
        attemptConnect(0L);
    }

    private final class SessionObserver implements StreamObserver<GuardianProto.Frame> {

        private final String host;

        SessionObserver(String host) {
            this.host = host;
        }

        @Override
        public void onNext(GuardianProto.Frame frame) {
            mainHandler.post(() -> dispatch(frame));
        }

        @Override
        public void onError(Throwable error) {
            onStreamClosed(error.getMessage());
        }

        @Override
        public void onCompleted() {
            onStreamClosed("server closed the stream");
        }

        private void dispatch(GuardianProto.Frame frame) {
            switch (frame.getPayloadCase()) {
                case SERVER_HELLO:
                    if (!frame.getServerHello().getAccepted()) {
                        Log.w(TAG, "panel rejected hello: " + frame.getServerHello().getErrorMessage());
                        ProxyTunnelService.writeRuntimeLogLine(
                            "[guardian] panel rejected hello: " + frame.getServerHello().getErrorMessage()
                        );
                        onStreamClosed("rejected");
                        return;
                    }
                    accepted = true;
                    connectedAtMs = System.currentTimeMillis();
                    phyFailureStreak = 0;
                    listener.onConnected(host);
                    listener.requestStateReport();
                    break;
                case COMMAND:
                    listener.onCommand(frame.getCommand());
                    break;
                case CONFIG_PUSH:
                    listener.onConfigPush(frame.getConfigPush());
                    break;
                case LOG_CONTROL:
                    listener.onLogControl(frame.getLogControl());
                    break;
                default:
                    // Heartbeat and anything else we do not act on
                    break;
            }
        }
    }
}
