package wings.v.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.TetheringManager;
import android.net.TrafficStats;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.wireguard.android.backend.Backend;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.android.backend.WgQuickBackend;
import com.wireguard.android.util.RootShell;
import com.wireguard.android.util.ToolsInstaller;
import com.wireguard.config.Config;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import wings.v.MainActivity;
import wings.v.CaptchaBrowserActivity;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.AmneziaConfigFactory;
import wings.v.core.BackendType;
import wings.v.core.CaptchaPromptSource;
import wings.v.core.ProxySettings;
import wings.v.core.PublicIpFetcher;
import wings.v.core.RootUtils;
import wings.v.core.TetherType;
import wings.v.core.WireGuardConfigFactory;
import wings.v.core.XrayStore;
import wings.v.qs.QuickSettingsTiles;
import wings.v.root.server.RootProcessResult;
import wings.v.vpnhotspot.bridge.VpnHotspotBridge;
import wings.v.vpnhotspot.bridge.sharing.VpnHotspotSharingConfig;
import wings.v.xray.XrayBridge;
import wings.v.xray.XrayConfigFactory;

public class ProxyTunnelService extends Service {
    public static final String ACTION_START = "wings.v.action.START";
    public static final String ACTION_STOP = "wings.v.action.STOP";
    public static final String ACTION_REFRESH_IP = "wings.v.action.REFRESH_IP";
    public static final String ACTION_RECONNECT = "wings.v.action.RECONNECT";
    public static final String ACTION_REAPPLY_SHARING = "wings.v.action.REAPPLY_SHARING";
    public static final String ACTION_SYNC_RUNTIME = "wings.v.action.SYNC_RUNTIME";
    public static final String ACTION_RESTORE_SHARING_ON_BOOT = "wings.v.action.RESTORE_SHARING_ON_BOOT";
    private static final String NOTIFICATION_CHANNEL_ID = "wingsv_tunnel";
    private static final String CAPTCHA_NOTIFICATION_CHANNEL_ID = "wingsv_captcha";
    private static final int SERVICE_NOTIFICATION_ID = 1;
    private static final int CAPTCHA_NOTIFICATION_ID = 2;
    private static final long NETWORK_READY_TIMEOUT_MS = 8_000L;
    private static final long NETWORK_READY_POLL_MS = 250L;
    private static final long PROXY_START_GRACE_MS = 1_500L;
    private static final long PROXY_WIREGUARD_DELAY_MS = 3_000L;
    private static final long PROXY_RETRY_DELAY_MS = 1_000L;
    private static final long RUNTIME_RECONNECT_DELAY_MS = 1_500L;
    private static final long RUNTIME_SUPERVISOR_INTERVAL_MS = 5_000L;
    private static final long UNDERLYING_NETWORK_RECONNECT_DELAY_MS = 750L;
    private static final long XRAY_HEARTBEAT_TIMEOUT_MS = 7_500L;
    private static final long WAKE_FAST_PATH_EVENT_COOLDOWN_MS = 2_000L;
    private static final long WAKE_FAST_PATH_INITIAL_DELAY_MS = 500L;
    private static final long WAKE_FAST_PATH_RETRY_DELAY_MS = 750L;
    private static final long WAKE_FAST_PATH_PROBE_TIMEOUT_MS = 1_500L;
    private static final long CONNECTIVITY_PROBE_INTERVAL_MS = 20_000L;
    private static final long CONNECTIVITY_PROBE_TIMEOUT_MS = 4_000L;
    private static final long CONNECTIVITY_PROBE_SUCCESS_TTL_MIN_MS = 30_000L;
    private static final long CONNECTIVITY_PROBE_SUCCESS_TTL_MAX_MS = 60_000L;
    private static final String CONNECTIVITY_PROBE_URL = "https://1.1.1.1/";
    private static final int PROXY_START_MAX_ATTEMPTS = 3;
    private static final int MAX_PROXY_LOG_LINES = 600;
    private static final long ROOT_TETHER_SYNC_INTERVAL_MS = 3_000L;
    private static final int TRANSIENT_ERROR_NOTICE_THRESHOLD = 6;
    private static final long TRANSIENT_ERROR_NOTICE_WINDOW_MS = 20_000L;
    private static final long CAPTCHA_NOTIFICATION_COOLDOWN_MS = 2 * 60_000L;
    private static final String NETLINK_PERMISSION_DENIED = "netlinkrib: permission denied";
    private static final String ROOT_TUNNEL_NAME = "wingsv";
    private static final int ROOT_UPSTREAM_TABLE = 54000;
    private static final int ROOT_RULE_PRIORITY_START = 12000;
    private static final int ROOT_RULE_PRIORITY_END = 12127;
    private static final int ROOT_TETHER_RULE_PRIORITY_UPSTREAM = 17800;
    private static final int ROOT_TETHER_RULE_PRIORITY_UPSTREAM_FALLBACK = 17900;
    private static final int ROOT_TETHER_RULE_PRIORITY_BLOCK_SYSTEM = 17980;
    private static final int ROOT_APP_TUNNEL_PRIORITY = 12128;
    private static final int ROOT_DHCP_WORKAROUND_PRIORITY = 11000;
    private static final String ROOT_TETHER_FORWARD_CHAIN = "wingsv_t_fwd";
    private static final String ROOT_TETHER_NAT_CHAIN = "wingsv_t_nat";
    private static final String ROOT_TETHER_DNS_CHAIN = "wingsv_t_dns";
    private static final String ROOT_TETHER_IPV6_CHAIN = "wingsv_t6_fwd";
    private static final String TAG = "WINGSV";
    private static final Pattern ACTIVE_TETHER_DUMPSYS_PATTERN =
            Pattern.compile("^([^\\s]+) - TetheredState - lastError = \\d+$");
    private static final Object PROXY_LOG_LOCK = new Object();
    private static final ArrayDeque<String> sProxyLogLines = new ArrayDeque<>();
    private static final Object RUNTIME_LOG_LOCK = new Object();
    private static final ArrayDeque<String> sRuntimeLogLines = new ArrayDeque<>();

    private enum ServiceState {
        STOPPED,
        CONNECTING,
        RUNNING
    }

    private static volatile ServiceState sServiceState = ServiceState.STOPPED;
    private static volatile boolean sRunning;
    private static volatile long sRxBytes;
    private static volatile long sTxBytes;
    private static volatile long sRxBytesPerSecond;
    private static volatile long sTxBytesPerSecond;
    private static volatile String sPublicIp;
    private static volatile String sPublicCountry;
    private static volatile String sPublicIsp;
    private static volatile String sLastError;
    private static volatile String sVisibleErrorNotice;
    private static volatile long sErrorNoticeSessionId;
    private static volatile long sDismissedErrorNoticeSessionId = -1L;
    private static volatile int sTransientErrorNoticeCount;
    private static volatile long sTransientErrorNoticeStartedAtMs;
    private static volatile long sLastConnectivityProbeSuccessAtElapsedMs;
    private static volatile long sConnectivityProbeSuccessTtlMs;
    private static volatile boolean sPublicIpRefreshInProgress;
    private static volatile long sProxyLogVersion;
    private static volatile long sRuntimeLogVersion;
    private static volatile String sPendingCaptchaUrl;
    private static volatile CaptchaPromptSource sPendingCaptchaSource = CaptchaPromptSource.PRIMARY;
    private static volatile long sLastCaptchaNotificationAtElapsedMs;

    private static final class CaptchaPrompt {
        final String url;
        final CaptchaPromptSource source;

        CaptchaPrompt(String url, CaptchaPromptSource source) {
            this.url = url;
            this.source = source;
        }
    }

    private final Object vpnBackendLock = new Object();
    private final ExecutorService workExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger runtimeGeneration = new AtomicInteger();
    private final AtomicBoolean rootTetherSyncQueued = new AtomicBoolean();
    private final BroadcastReceiver tetherStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            persistActiveTetherTypes(intent);
            requestRootTetherRoutingSync(intent);
        }
    };
    private ScheduledExecutorService statsExecutor;
    private volatile Future<?> activeWorkTask;
    private final AtomicBoolean runtimeReconnectQueued = new AtomicBoolean();
    private final AtomicBoolean connectivityProbeInProgress = new AtomicBoolean();
    private Backend backend;
    private Config currentConfig;
    private Tunnel currentTunnel;
    private org.amnezia.awg.backend.Backend awgBackend;
    private org.amnezia.awg.config.Config awgConfig;
    private org.amnezia.awg.backend.Tunnel awgTunnel;
    private Process proxyProcess;
    private ProxyProtectBridgeServer protectBridgeServer;
    private String protectSocketName;
    private RootShell rootShell;
    private ToolsInstaller toolsInstaller;
    private RootRoutingState rootRoutingState;
    private boolean rootModeActive;
    private BackendType activeBackendType = BackendType.VK_TURN_WIREGUARD;
    private String activeTunnelName = ROOT_TUNNEL_NAME;
    private String appliedTetherUpstreamName;
    private volatile PublicIpFetcher.Request publicIpRequest;
    private volatile int publicIpRequestGeneration;
    private volatile boolean stopping;
    private boolean tetherReceiverRegistered;
    private boolean tetherEventCallbackRegistered;
    private volatile Set<String> activeTetheredInterfaces = Collections.emptySet();
    private long lastRxSample = -1L;
    private long lastTxSample = -1L;
    private WifiManager.WifiLock tunnelWifiLock;
    private WifiManager.WifiLock sharingWifiLock;
    private PowerManager.WakeLock tunnelPowerLock;
    private PowerManager.WakeLock sharingPowerLock;
    private int sharingWifiLockMode = -1;
    private long xrayTrafficBaseRx = -1L;
    private long xrayTrafficBaseTx = -1L;
    private String lastUnderlyingNetworkFingerprint;
    private Boolean lastUnderlyingNetworkUsable;
    private long lastWakeFastPathAtElapsedMs;
    private boolean physicalNetworkCallbackRegistered;
    private boolean screenStateReceiverRegistered;
    private final AtomicBoolean wakeFastPathCheckQueued = new AtomicBoolean();
    private final ConnectivityManager.NetworkCallback physicalNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@Nullable Network network) {
                    handleUnderlyingNetworkEvent("available", network);
                }

                @Override
                public void onLost(@Nullable Network network) {
                    handleUnderlyingNetworkEvent("lost", network);
                }

                @Override
                public void onCapabilitiesChanged(@Nullable Network network, @Nullable NetworkCapabilities networkCapabilities) {
                    handleUnderlyingNetworkEvent("capabilities", network);
                }

                @Override
                public void onLinkPropertiesChanged(@Nullable Network network, @Nullable LinkProperties linkProperties) {
                    handleUnderlyingNetworkEvent("link", network);
                }
            };
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleWakeFastPathEvent(intent != null ? intent.getAction() : null);
        }
    };
    private final TetheringManager.TetheringEventCallback tetheringEventCallback =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? new TetheringManager.TetheringEventCallback() {
                @Override
                public void onTetheredInterfacesChanged(@Nullable Set<android.net.TetheringInterface> interfaces) {
                    LinkedHashSet<String> updated = new LinkedHashSet<>();
                    if (interfaces != null) {
                        for (android.net.TetheringInterface tetheringInterface : interfaces) {
                            String iface = tetheringInterface != null ? tetheringInterface.getInterface() : null;
                            if (!TextUtils.isEmpty(iface)) {
                                updated.add(iface.trim());
                            }
                        }
                    }
                    activeTetheredInterfaces = Collections.unmodifiableSet(updated);
                    persistActiveTetherTypes(updated);
                    Log.i(TAG, "Tethering callback downstreams: " + updated);
                    requestRootTetherRoutingSync(null);
                }
            }
                    : null;
    private volatile boolean pendingSharingRestoreOnBoot;

    public static Intent createStartIntent(Context context) {
        return new Intent(context, ProxyTunnelService.class).setAction(ACTION_START);
    }

    public static Intent createStopIntent(Context context) {
        return new Intent(context, ProxyTunnelService.class).setAction(ACTION_STOP);
    }

    public static void requestStop(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null) {
            return;
        }
        Intent intent = createStopIntent(appContext);
        try {
            appContext.startService(intent);
        } catch (Exception startError) {
            try {
                ContextCompat.startForegroundService(appContext, intent);
            } catch (Exception ignored) {
            }
        }
    }

    public static void requestReconnect(Context context, @Nullable String reason) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null || !isActive()) {
            return;
        }
        if (!TextUtils.isEmpty(reason)) {
            appendRuntimeLogLine(reason);
        }
        Intent intent = createReconnectIntent(appContext);
        try {
            appContext.startService(intent);
        } catch (Exception startError) {
            try {
                ContextCompat.startForegroundService(appContext, intent);
            } catch (Exception ignored) {
            }
        }
    }

    public static void clearPendingCaptchaPrompt(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null) {
            return;
        }
        sPendingCaptchaUrl = null;
        sPendingCaptchaSource = CaptchaPromptSource.PRIMARY;
        NotificationManager notificationManager = appContext.getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            try {
                notificationManager.cancel(CAPTCHA_NOTIFICATION_ID);
            } catch (Exception ignored) {
            }
        }
    }

    public static Intent createRefreshIpIntent(Context context) {
        return new Intent(context, ProxyTunnelService.class).setAction(ACTION_REFRESH_IP);
    }

    public static Intent createReconnectIntent(Context context) {
        return new Intent(context, ProxyTunnelService.class).setAction(ACTION_RECONNECT);
    }

    public static Intent createReapplySharingIntent(Context context) {
        return new Intent(context, ProxyTunnelService.class).setAction(ACTION_REAPPLY_SHARING);
    }

    public static Intent createSyncRuntimeIntent(Context context) {
        return new Intent(context, ProxyTunnelService.class).setAction(ACTION_SYNC_RUNTIME);
    }

    public static Intent createRestoreSharingOnBootIntent(Context context) {
        return new Intent(context, ProxyTunnelService.class).setAction(ACTION_RESTORE_SHARING_ON_BOOT);
    }

    public static void requestRuntimeSyncIfNeeded(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null
                || isActive()
                || !AppPrefs.isRootModeEnabled(appContext)
                || !AppPrefs.hasRootRuntimeHint(appContext)) {
            return;
        }
        try {
            ContextCompat.startForegroundService(appContext, createSyncRuntimeIntent(appContext));
        } catch (Exception ignored) {
        }
    }

    public static boolean isRunning() {
        return sServiceState == ServiceState.RUNNING;
    }

    public static boolean isConnecting() {
        return sServiceState == ServiceState.CONNECTING;
    }

    public static boolean isActive() {
        return sServiceState != ServiceState.STOPPED;
    }

    public static long getRxBytes() {
        return sRxBytes;
    }

    public static long getTxBytes() {
        return sTxBytes;
    }

    public static long getRxBytesPerSecond() {
        return sRxBytesPerSecond;
    }

    public static long getTxBytesPerSecond() {
        return sTxBytesPerSecond;
    }

    public static String getPublicIp() {
        return sPublicIp;
    }

    public static String getPublicCountry() {
        return sPublicCountry;
    }

    public static String getPublicIsp() {
        return sPublicIsp;
    }

    public static String getLastError() {
        return sLastError;
    }

    @Nullable
    public static String getVisibleErrorNotice() {
        if (TextUtils.isEmpty(sVisibleErrorNotice) || sDismissedErrorNoticeSessionId == sErrorNoticeSessionId) {
            return null;
        }
        return sVisibleErrorNotice;
    }

    public static void dismissVisibleErrorNotice() {
        sDismissedErrorNoticeSessionId = sErrorNoticeSessionId;
    }

    public static boolean isPublicIpRefreshInProgress() {
        return sPublicIpRefreshInProgress;
    }

    public static long getProxyLogVersion() {
        return sProxyLogVersion;
    }

    public static String getProxyLogSnapshot() {
        synchronized (PROXY_LOG_LOCK) {
            if (sProxyLogLines.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (String line : sProxyLogLines) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            return builder.toString();
        }
    }

    public static long getRuntimeLogVersion() {
        return sRuntimeLogVersion;
    }

    public static String getRuntimeLogSnapshot() {
        synchronized (RUNTIME_LOG_LOCK) {
            if (sRuntimeLogLines.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (String line : sRuntimeLogLines) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            return builder.toString();
        }
    }

    public static void clearProxyLogs() {
        synchronized (PROXY_LOG_LOCK) {
            sProxyLogLines.clear();
            sProxyLogVersion++;
        }
    }

    public static void clearRuntimeLogs() {
        synchronized (RUNTIME_LOG_LOCK) {
            sRuntimeLogLines.clear();
            sRuntimeLogVersion++;
        }
    }

    private static void appendProxyLogLine(String line) {
        if (TextUtils.isEmpty(line)) {
            return;
        }
        synchronized (PROXY_LOG_LOCK) {
            while (sProxyLogLines.size() >= MAX_PROXY_LOG_LINES) {
                sProxyLogLines.removeFirst();
            }
            sProxyLogLines.addLast(line);
            sProxyLogVersion++;
        }
    }

    private static void appendRuntimeLogLine(String line) {
        if (TextUtils.isEmpty(line)) {
            return;
        }
        synchronized (RUNTIME_LOG_LOCK) {
            while (sRuntimeLogLines.size() >= MAX_PROXY_LOG_LINES) {
                sRuntimeLogLines.removeFirst();
            }
            sRuntimeLogLines.addLast(line);
            sRuntimeLogVersion++;
        }
    }

    public static void applyPublicIpInfo(@Nullable PublicIpFetcher.IpInfo ipInfo) {
        if (ipInfo == null) {
            sPublicIp = null;
            sPublicCountry = null;
            sPublicIsp = null;
            return;
        }
        sPublicIp = TextUtils.isEmpty(ipInfo.ip) ? null : ipInfo.ip;
        sPublicCountry = TextUtils.isEmpty(ipInfo.country) ? null : ipInfo.country;
        sPublicIsp = TextUtils.isEmpty(ipInfo.isp) ? null : ipInfo.isp;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (TextUtils.isEmpty(action)) {
            action = shouldAttemptRootRuntimeRecovery() ? ACTION_SYNC_RUNTIME : ACTION_START;
        }
        if (ACTION_STOP.equals(action)) {
            if (isConnecting()) {
                abortConnectingNow(true);
                return START_NOT_STICKY;
            }
            stopWork(true);
            return START_NOT_STICKY;
        }
        if (ACTION_REAPPLY_SHARING.equals(action)) {
            if (!isActive()) {
                return START_NOT_STICKY;
            }
            workExecutor.execute(() -> {
                if (!rootModeActive) {
                    return;
                }
                try {
                    reapplyRootSharingState();
                    clearLastError();
                } catch (Exception error) {
                    setLastError(error.getMessage());
                    appendRuntimeLogLine("Root sharing reapply failed: " + error.getMessage());
                }
            });
            return START_STICKY;
        }
        if (ACTION_RESTORE_SHARING_ON_BOOT.equals(action)) {
            pendingSharingRestoreOnBoot = true;
            if (isActive()) {
                workExecutor.execute(this::restoreSharingOnBootIfNeeded);
                return START_STICKY;
            }
        }
        if (ACTION_REFRESH_IP.equals(action)) {
            if (!isActive()) {
                return START_NOT_STICKY;
            }
            requestPublicIpRefresh(true);
            return START_STICKY;
        }
        if (ACTION_RECONNECT.equals(action)) {
            if (!isActive()) {
                setServiceState(ServiceState.CONNECTING);
                startForeground(SERVICE_NOTIFICATION_ID, buildNotification());
                startWork();
                return START_STICKY;
            }
            resetRuntimeSnapshot();
            clearLastError();
            setServiceState(ServiceState.CONNECTING);
            startForeground(SERVICE_NOTIFICATION_ID, buildNotification());
            workExecutor.execute(() -> {
                stopWorkInternalForReconnect();
                stopping = false;
                clearLastError();
                setServiceState(ServiceState.CONNECTING);
                try {
                    startForeground(SERVICE_NOTIFICATION_ID, buildNotification());
                } catch (Exception ignored) {
                }
                startWork();
            });
            return START_STICKY;
        }
        if (ACTION_SYNC_RUNTIME.equals(action)) {
            if (!AppPrefs.isRootModeEnabled(getApplicationContext())
                    || !AppPrefs.hasRootRuntimeHint(getApplicationContext())) {
                stopSelf();
                return START_NOT_STICKY;
            }
            setServiceState(ServiceState.CONNECTING);
            startForeground(SERVICE_NOTIFICATION_ID, buildNotification());
            recoverExistingRootRuntime();
            return START_STICKY;
        }

        setServiceState(ServiceState.CONNECTING);
        startForeground(SERVICE_NOTIFICATION_ID, buildNotification());
        startWork();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopWork(false);
        super.onDestroy();
    }

    private void startWork() {
        final int generation = runtimeGeneration.incrementAndGet();
        activeWorkTask = workExecutor.submit(() -> {
            if (proxyProcess != null
                    || backend != null
                    || currentTunnel != null
                    || awgBackend != null
                    || awgTunnel != null) {
                return;
            }

            clearPendingCaptchaPrompt(getApplicationContext());
            beginErrorNoticeSession();
            clearProxyLogs();
            clearRuntimeLogs();
            ProxySettings settings = AppPrefs.getSettings(getApplicationContext());
            String validationError = settings.validate();
            if (!TextUtils.isEmpty(validationError)) {
                setLastError(validationError);
                setServiceState(ServiceState.STOPPED);
                stopSelf();
                return;
            }

            try {
                ensureRuntimeStillWanted(generation);
                stopping = false;
                clearLastError();
                resetRuntimeSnapshot();
                activeBackendType = settings.backendType != null
                        ? settings.backendType
                        : BackendType.VK_TURN_WIREGUARD;
                rootModeActive = settings.rootModeEnabled;
                if (rootModeActive) {
                    String rootUnavailableReason = RootUtils.getRootModeUnavailableReason(
                            getApplicationContext(),
                            activeBackendType,
                            true
                    );
                    if (!TextUtils.isEmpty(rootUnavailableReason)) {
                        throw new IllegalStateException(rootUnavailableReason);
                    }
                } else {
                    clearPersistedRootRuntimeState();
                }

                if (activeBackendType == BackendType.VK_TURN_WIREGUARD
                        && settings.rootModeEnabled
                        && AppPrefs.hasRootRuntimeHint(getApplicationContext())) {
                    if (recoverExistingRootRuntimeInternal(settings, true, generation)) {
                        return;
                    }
                    ensureRuntimeStillWanted(generation);
                    setServiceState(ServiceState.CONNECTING);
                    startForeground(SERVICE_NOTIFICATION_ID, buildNotification());
                }

                ensureRuntimeStillWanted(generation);
                setServiceState(ServiceState.CONNECTING);
                waitForUsablePhysicalNetwork(generation);
                if (activeBackendType == BackendType.XRAY) {
                    startXrayRuntime(settings, generation);
                    return;
                }
                if (activeBackendType == BackendType.AMNEZIAWG) {
                    startAmneziaRuntime(settings, generation);
                    return;
                }
                prepareBackend(settings, null);
                ensureRuntimeStillWanted(generation);
                if (rootModeActive) {
                    terminateMatchingRootProxyProcesses(settings);
                    rootRoutingState = captureRootRoutingState();
                    applyRootRouting(rootRoutingState);
                }
                ensureRuntimeStillWanted(generation);
                long proxyStartedAt = startProxyProcess(settings, generation);
                waitForProxyWarmup(proxyStartedAt, generation);

                ensureRuntimeStillWanted(generation);
                currentTunnel = new LocalTunnel(activeTunnelName);
                currentConfig = WireGuardConfigFactory.build(
                        getApplicationContext(),
                        settings,
                        !rootModeActive
                );
                synchronized (vpnBackendLock) {
                    backend.setState(currentTunnel, Tunnel.State.UP, currentConfig);
                }
                persistRootRuntimeState(readRootProxyPid());
                if (rootModeActive) {
                    syncRootAppTunnelRouting();
                    registerTetherReceiverIfNeeded();
                    registerTetherEventCallbackIfNeeded();
                    syncRootTetherRouting(null);
                }
                setServiceState(ServiceState.RUNNING);
                sRunning = true;
                AppPrefs.setExternalActionTransientLaunchPending(getApplicationContext(), false);
                requestPublicIpRefresh(true);
                restoreSharingOnBootIfNeeded();

                startPolling();
            } catch (Exception error) {
                if (!stopping) {
                    setLastError(error.getMessage());
                }
                stopWorkInternal();
                stopSelf();
            }
        });
    }

    private void stopWork(boolean removeNotification) {
        invalidateRuntimeOperations();
        stopping = true;
        sRunning = false;
        setServiceState(ServiceState.STOPPED);
        if (removeNotification) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Exception ignored) {
            }
        }
        workExecutor.execute(() -> {
            stopWorkInternal();
            if (removeNotification) {
                stopSelf();
            }
        });
    }

    private void abortConnectingNow(boolean removeNotification) {
        invalidateRuntimeOperations();
        stopping = true;
        sRunning = false;
        setServiceState(ServiceState.STOPPED);
        if (removeNotification) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Exception ignored) {
            }
        }
        Thread abortThread = new Thread(() -> {
            stopWorkInternal();
            if (removeNotification) {
                stopSelf();
            }
        }, "wingsv-connect-abort");
        abortThread.setDaemon(true);
        abortThread.start();
    }

    private void startXrayRuntime(ProxySettings settings, int generation) throws Exception {
        ensureRuntimeStillWanted(generation);
        clearPersistedRootRuntimeState();
        AppPrefs.clearRuntimeUpstreamState(getApplicationContext());
        activeTunnelName = "";
        backend = null;
        currentTunnel = null;
        currentConfig = null;
        closeProtectBridge();
        protectSocketName = null;

        XrayVpnService vpnService = XrayVpnService.ensureServiceStarted(getApplicationContext());
        if (vpnService == null) {
            throw new IllegalStateException("Не удалось запустить Xray VPN service");
        }
        int tunFd = vpnService.establishTunnel(settings);
        if (tunFd <= 0) {
            throw new IllegalStateException("Не удалось открыть Xray TUN");
        }
        ensureRuntimeStillWanted(generation);

        if (rootModeActive) {
            VpnHotspotBridge.initializeRootServer(getApplicationContext());
        }

        try {
            XrayBridge.stop();
        } catch (Exception ignored) {
        }

        String remoteDns = settings.xraySettings != null ? settings.xraySettings.remoteDns : null;
        String directDns = settings.xraySettings != null ? settings.xraySettings.directDns : null;
        XrayBridge.prepareRuntime(vpnService, remoteDns, directDns);
        String configJson = XrayConfigFactory.buildConfigJson(getApplicationContext(), settings);
        appendRuntimeLogLine("Starting Xray backend");
        XrayBridge.runFromJson(getApplicationContext(), configJson, tunFd);
        if (!XrayBridge.isRunning()) {
            throw new IllegalStateException("Xray core не перешел в состояние running");
        }

        if (rootModeActive) {
            registerTetherReceiverIfNeeded();
            registerTetherEventCallbackIfNeeded();
            syncRootTetherRouting(null);
        }

        setServiceState(ServiceState.RUNNING);
        sRunning = true;
        AppPrefs.setExternalActionTransientLaunchPending(getApplicationContext(), false);
        requestPublicIpRefresh(true);
        restoreSharingOnBootIfNeeded();
        startPolling();
    }

    private void startAmneziaRuntime(ProxySettings settings, int generation) throws Exception {
        ensureRuntimeStillWanted(generation);
        clearPersistedRootRuntimeState();
        AppPrefs.clearRuntimeUpstreamState(getApplicationContext());
        activeTunnelName = ROOT_TUNNEL_NAME;
        backend = null;
        currentTunnel = null;
        currentConfig = null;
        closeProtectBridge();
        protectSocketName = null;

        ensureProtectBridgeReady();
        ensureRuntimeStillWanted(generation);
        long proxyStartedAt = startProxyProcess(settings, generation);
        waitForProxyWarmup(proxyStartedAt, generation);
        ensureRuntimeStillWanted(generation);

        awgBackend = new org.amnezia.awg.backend.GoBackend(getApplicationContext());
        awgTunnel = new LocalAwgTunnel(activeTunnelName);
        awgConfig = AmneziaConfigFactory.build(getApplicationContext(), settings);
        ensureRuntimeStillWanted(generation);
        synchronized (vpnBackendLock) {
            awgBackend.setState(awgTunnel, org.amnezia.awg.backend.Tunnel.State.UP, awgConfig);
        }

        setServiceState(ServiceState.RUNNING);
        sRunning = true;
        AppPrefs.setExternalActionTransientLaunchPending(getApplicationContext(), false);
        requestPublicIpRefresh(true);
        restoreSharingOnBootIfNeeded();
        startPolling();
    }

    private void stopWorkInternal() {
        stopping = true;
        runtimeReconnectQueued.set(false);
        setServiceState(ServiceState.STOPPED);
        sRunning = false;
        resetRuntimeSnapshot();
        sPublicIpRefreshInProgress = false;
        AppPrefs.setExternalActionTransientLaunchPending(getApplicationContext(), false);
        clearPendingCaptchaPrompt(getApplicationContext());

        cancelPublicIpRefresh();

        if (statsExecutor != null) {
            statsExecutor.shutdownNow();
            statsExecutor = null;
        }

        if (activeBackendType == BackendType.XRAY) {
            XrayVpnService.stopService(getApplicationContext());
            try {
                XrayBridge.stop();
            } catch (Exception ignored) {
            }
        } else {
            shutdownVpnBackendsLocked();
        }
        forceLinkDownActiveTunnelIfNeeded();

        clearRootTetherRouting();
        clearRootAppTunnelRouting();
        unregisterTetherReceiver();
        unregisterTetherEventCallback();
        releaseTunnelWifiLock();
        releaseSharingWifiLocks();
        clearRootRouting();

        if (proxyProcess != null) {
            proxyProcess.destroy();
            proxyProcess = null;
        }
        killPersistedRootProxyIfNeeded();

        protectSocketName = null;
        rootModeActive = false;
        activeBackendType = BackendType.VK_TURN_WIREGUARD;
        activeTunnelName = ROOT_TUNNEL_NAME;
        appliedTetherUpstreamName = null;
        pendingSharingRestoreOnBoot = false;

        closeProtectBridge();
        GoBackendVpnAccess.stopService(getApplicationContext());
        XrayVpnService.stopService(getApplicationContext());
        if (rootShell != null) {
            rootShell.stop();
            rootShell = null;
        }
        try {
            VpnHotspotBridge.closeExistingRootServer();
        } catch (Exception ignored) {
        }
        toolsInstaller = null;
        clearPersistedRootRuntimeState();
    }

    private void stopWorkInternalForReconnect() {
        stopping = true;
        runtimeReconnectQueued.set(false);
        sRunning = false;
        cancelPublicIpRefresh();
        sPublicIpRefreshInProgress = false;
        clearPendingCaptchaPrompt(getApplicationContext());

        if (statsExecutor != null) {
            statsExecutor.shutdownNow();
            statsExecutor = null;
        }

        if (activeBackendType == BackendType.XRAY) {
            XrayVpnService.stopService(getApplicationContext());
            try {
                XrayBridge.stop();
            } catch (Exception ignored) {
            }
        } else {
            shutdownVpnBackendsLocked();
        }
        forceLinkDownActiveTunnelIfNeeded();

        clearRootTetherRouting();
        clearRootAppTunnelRouting();
        unregisterTetherReceiver();
        unregisterTetherEventCallback();
        releaseTunnelWifiLock();
        releaseSharingWifiLocks();
        clearRootRouting();

        if (proxyProcess != null) {
            proxyProcess.destroy();
            proxyProcess = null;
        }
        killPersistedRootProxyIfNeeded();

        protectSocketName = null;
        rootModeActive = false;
        activeBackendType = BackendType.VK_TURN_WIREGUARD;
        activeTunnelName = ROOT_TUNNEL_NAME;
        appliedTetherUpstreamName = null;
        pendingSharingRestoreOnBoot = false;

        closeProtectBridge();
        GoBackendVpnAccess.stopService(getApplicationContext());
        XrayVpnService.stopService(getApplicationContext());
        if (rootShell != null) {
            rootShell.stop();
            rootShell = null;
        }
        try {
            VpnHotspotBridge.closeExistingRootServer();
        } catch (Exception ignored) {
        }
        toolsInstaller = null;
        clearPersistedRootRuntimeState();
    }

    private void abandonRecoveredRuntime(boolean clearPersistedRuntime) {
        stopping = true;
        runtimeReconnectQueued.set(false);
        setServiceState(ServiceState.STOPPED);
        sRunning = false;
        resetRuntimeSnapshot();
        sPublicIpRefreshInProgress = false;
        cancelPublicIpRefresh();
        clearPendingCaptchaPrompt(getApplicationContext());
        if (statsExecutor != null) {
            statsExecutor.shutdownNow();
            statsExecutor = null;
        }
        unregisterTetherReceiver();
        unregisterTetherEventCallback();
        clearRootAppTunnelRouting();
        releaseTunnelWifiLock();
        releaseSharingWifiLocks();
        clearVpnBackendReferences();
        protectSocketName = null;
        rootModeActive = false;
        activeBackendType = BackendType.VK_TURN_WIREGUARD;
        activeTunnelName = ROOT_TUNNEL_NAME;
        appliedTetherUpstreamName = null;
        pendingSharingRestoreOnBoot = false;
        closeProtectBridge();
        GoBackendVpnAccess.stopService(getApplicationContext());
        XrayVpnService.stopService(getApplicationContext());
        if (rootShell != null) {
            rootShell.stop();
            rootShell = null;
        }
        try {
            VpnHotspotBridge.closeExistingRootServer();
        } catch (Exception ignored) {
        }
        toolsInstaller = null;
        if (clearPersistedRuntime) {
            clearPersistedRootRuntimeState();
        }
    }

    private void recoverExistingRootRuntime() {
        final int generation = runtimeGeneration.incrementAndGet();
        activeWorkTask = workExecutor.submit(() -> {
            try {
                ensureRuntimeStillWanted(generation);
                if (proxyProcess != null || backend != null || currentTunnel != null) {
                    return;
                }
                beginErrorNoticeSession();
                clearPendingCaptchaPrompt(getApplicationContext());
                ProxySettings settings = AppPrefs.getSettings(getApplicationContext());
                if (!recoverExistingRootRuntimeInternal(settings, false, generation)) {
                    clearPersistedRootRuntimeState();
                    stopWorkInternal();
                    stopSelf();
                }
            } catch (Exception error) {
                if (!stopping) {
                    setLastError(error.getMessage());
                }
                abandonRecoveredRuntime(!isRecoveredRuntimeAliveNow(
                        AppPrefs.getRootRuntimeTunnelName(getApplicationContext()),
                        AppPrefs.getRootRuntimeProxyPid(getApplicationContext())
                ));
                stopSelf();
            }
        });
    }

    private boolean recoverExistingRootRuntimeInternal(ProxySettings settings,
                                                       boolean allowFallbackToFreshStart,
                                                       int generation) throws Exception {
        if (settings == null || !settings.rootModeEnabled) {
            return false;
        }
        if (!AppPrefs.hasRootRuntimeHint(getApplicationContext())) {
            return false;
        }

        clearProxyLogs();
        String tunnelName = resolveRecoveryTunnelName();
        long proxyPid = AppPrefs.getRootRuntimeProxyPid(getApplicationContext());

        stopping = false;
        clearLastError();
        resetRuntimeSnapshot();

        String rootUnavailableReason = RootUtils.getRootModeUnavailableReason(
                getApplicationContext(),
                true
        );
        if (!TextUtils.isEmpty(rootUnavailableReason)) {
            throw new IllegalStateException(rootUnavailableReason);
        }
        if (TextUtils.isEmpty(tunnelName)) {
            if (allowFallbackToFreshStart) {
                clearPersistedRootRuntimeState();
                return false;
            }
            throw new IllegalStateException("Root runtime marker повреждён");
        }
        if (proxyPid <= 0L) {
            proxyPid = findRunningRootProxyPid(settings);
        }

        ensureRuntimeStillWanted(generation);
        prepareBackend(settings, tunnelName);
        ensureRuntimeStillWanted(generation);
        currentTunnel = new LocalTunnel(activeTunnelName);
        currentConfig = WireGuardConfigFactory.build(
                getApplicationContext(),
                settings,
                false
        );

        boolean recoveredAlive = false;
        try {
            recoveredAlive = backend != null
                    && backend.getState(currentTunnel) == Tunnel.State.UP
                    && isRecoveredRootRuntimeAlive(tunnelName, proxyPid);
        } catch (Exception ignored) {
            recoveredAlive = isRecoveredRootRuntimeAlive(tunnelName, proxyPid);
        }
        if (!recoveredAlive) {
            abandonRecoveredRuntime(true);
            if (allowFallbackToFreshStart) {
                return false;
            }
            throw new IllegalStateException("Root runtime больше не активен");
        }

        ensureRuntimeStillWanted(generation);
        rootRoutingState = captureRootRoutingState();
        persistRootRuntimeState(proxyPid);
        syncRootAppTunnelRouting();
        registerTetherReceiverIfNeeded();
        registerTetherEventCallbackIfNeeded();
        syncRootTetherRouting(null);
        setServiceState(ServiceState.RUNNING);
        sRunning = true;
        AppPrefs.setExternalActionTransientLaunchPending(getApplicationContext(), false);
        requestPublicIpRefresh(true);
        restoreSharingOnBootIfNeeded();
        startPolling();
        return true;
    }

    private boolean isRecoveredRuntimeAliveNow(String tunnelName, long proxyPid) {
        Context appContext = getApplicationContext();
        return RootUtils.isRootAccessGranted(appContext)
                && RootUtils.isRootInterfaceAlive(appContext, tunnelName)
                && (proxyPid <= 0L
                || (RootUtils.isRootProcessAlive(appContext, proxyPid)
                && (rootShell == null || isExpectedRootProxyProcess(proxyPid))));
    }

    private long startProxyProcess(ProxySettings settings, int generation) throws Exception {
        String launchError = null;
        for (int attempt = 1; attempt <= PROXY_START_MAX_ATTEMPTS; attempt++) {
            ensureRuntimeStillWanted(generation);
            AtomicReference<String> launchOutputError = new AtomicReference<>();
            Process launchedProcess = buildProxyProcess(settings);
            long launchedAt = SystemClock.elapsedRealtime();
            startProxyOutputReader(launchedProcess, launchOutputError);

            if (!launchedProcess.waitFor(PROXY_START_GRACE_MS, TimeUnit.MILLISECONDS)) {
                proxyProcess = launchedProcess;
                clearLastError();
                attachProxyWaitThread(launchedProcess);
                return launchedAt;
            }

            int exitCode = launchedProcess.exitValue();
            launchError = firstNonEmpty(
                    launchOutputError.get(),
                    sLastError,
                    "Proxy завершился с кодом " + exitCode
            );
            setLastError(launchError);

            if (shouldRetryProxyLaunch(launchError, attempt)) {
                sleepInterruptibly(PROXY_RETRY_DELAY_MS, generation);
                continue;
            }
            throw new IllegalStateException(launchError);
        }

        throw new IllegalStateException(firstNonEmpty(launchError, "Не удалось запустить proxy"));
    }

    private Process buildProxyProcess(ProxySettings settings) throws Exception {
        File executable = new File(getApplicationInfo().nativeLibraryDir, "libvkturn.so");
        executable.setExecutable(true);

        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.add("-peer");
        command.add(settings.endpoint);
        command.add("-vk-link");
        command.add(settings.vkLink);
        command.add("-listen");
        command.add(settings.localEndpoint);

        if (settings.threads > 0) {
            command.add("-n");
            command.add(String.valueOf(settings.threads));
        }
        if (settings.useUdp) {
            command.add("-udp");
        }
        if (settings.noObfuscation) {
            command.add("-no-dtls");
        }
        if (!TextUtils.isEmpty(settings.turnSessionMode)) {
            command.add("-session-mode");
            command.add(settings.turnSessionMode);
        }
        if (!TextUtils.isEmpty(settings.turnHost)) {
            command.add("-turn");
            command.add(settings.turnHost);
        }
        if (!TextUtils.isEmpty(settings.turnPort)) {
            command.add("-port");
            command.add(settings.turnPort);
        }
        if (!TextUtils.isEmpty(protectSocketName)) {
            command.add("-protect-sock");
            command.add(protectSocketName);
        }

        if (!rootModeActive) {
            return new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        }
        File pidFile = getRootProxyPidFile();
        File parentDir = pidFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        String rootCommand = "umask 022; mkdir -p "
                + RootUtils.shellQuote(parentDir != null ? parentDir.getAbsolutePath() : getFilesDir().getAbsolutePath())
                + "; echo $$ > " + RootUtils.shellQuote(pidFile.getAbsolutePath())
                + "; chmod 0644 " + RootUtils.shellQuote(pidFile.getAbsolutePath())
                + "; exec " + joinShellCommand(command);
        return new ProcessBuilder("su", "-c", rootCommand)
                .redirectErrorStream(true)
                .start();
    }

    private void resetRuntimeSnapshot() {
        sRxBytes = 0L;
        sTxBytes = 0L;
        sRxBytesPerSecond = 0L;
        sTxBytesPerSecond = 0L;
        sPublicIp = null;
        sPublicCountry = null;
        sPublicIsp = null;
        lastRxSample = -1L;
        lastTxSample = -1L;
        xrayTrafficBaseRx = -1L;
        xrayTrafficBaseTx = -1L;
        lastUnderlyingNetworkFingerprint = null;
        lastUnderlyingNetworkUsable = null;
    }

    private static void beginErrorNoticeSession() {
        sErrorNoticeSessionId = Math.max(1L, sErrorNoticeSessionId + 1L);
        sDismissedErrorNoticeSessionId = -1L;
        sVisibleErrorNotice = null;
        sTransientErrorNoticeCount = 0;
        sTransientErrorNoticeStartedAtMs = 0L;
        sLastConnectivityProbeSuccessAtElapsedMs = 0L;
        sConnectivityProbeSuccessTtlMs = 0L;
    }

    private static void clearLastError() {
        sLastError = null;
        sVisibleErrorNotice = null;
        sTransientErrorNoticeCount = 0;
        sTransientErrorNoticeStartedAtMs = 0L;
    }

    private static void setLastError(@Nullable String error) {
        sLastError = TextUtils.isEmpty(error) ? null : error;
        if (TextUtils.isEmpty(sLastError)) {
            sVisibleErrorNotice = null;
            sTransientErrorNoticeCount = 0;
            sTransientErrorNoticeStartedAtMs = 0L;
            return;
        }
        if (isTransientNoticeCandidateError(sLastError)) {
            long now = SystemClock.elapsedRealtime();
            if (sTransientErrorNoticeStartedAtMs <= 0L
                    || now - sTransientErrorNoticeStartedAtMs > TRANSIENT_ERROR_NOTICE_WINDOW_MS) {
                sTransientErrorNoticeStartedAtMs = now;
                sTransientErrorNoticeCount = 1;
            } else {
                sTransientErrorNoticeCount++;
            }
            if (sTransientErrorNoticeCount >= TRANSIENT_ERROR_NOTICE_THRESHOLD
                    && !hasRecentConnectivityProbeSuccess()) {
                sVisibleErrorNotice = sLastError;
            } else {
                sVisibleErrorNotice = null;
            }
            return;
        }
        sTransientErrorNoticeCount = 0;
        sTransientErrorNoticeStartedAtMs = 0L;
        sVisibleErrorNotice = sLastError;
    }

    private static boolean isTransientNoticeCandidateError(@Nullable String error) {
        if (TextUtils.isEmpty(error)) {
            return false;
        }
        String lower = error.toLowerCase(Locale.US);
        return lower.contains("connection refused")
                || lower.contains("err_connection_refused")
                || lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("deadline exceeded")
                || lower.contains("connection reset")
                || lower.contains("broken pipe")
                || lower.contains("network is unreachable")
                || lower.contains("no route to host")
                || lower.contains("temporary failure")
                || lower.contains("no such host")
                || lower.contains("eof");
    }

    private static boolean hasRecentConnectivityProbeSuccess() {
        long successAt = sLastConnectivityProbeSuccessAtElapsedMs;
        long ttl = sConnectivityProbeSuccessTtlMs;
        return successAt > 0L
                && ttl > 0L
                && SystemClock.elapsedRealtime() - successAt <= ttl;
    }

    private File getRootProxyPidFile() {
        return new File(new File(getFilesDir(), "runtime"), "root_proxy.pid");
    }

    private long readRootProxyPid() {
        File pidFile = getRootProxyPidFile();
        for (int attempt = 0; attempt < 5; attempt++) {
            if (pidFile.isFile()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(pidFile))) {
                    String line = reader.readLine();
                    if (!TextUtils.isEmpty(line)) {
                        return Long.parseLong(line.trim());
                    }
                } catch (Exception ignored) {
                }
            }
            if (attempt < 4) {
                SystemClock.sleep(100L);
            }
        }
        return AppPrefs.getRootRuntimeProxyPid(getApplicationContext());
    }

    private void persistRootRuntimeState(long proxyPid) {
        if (!rootModeActive) {
            clearPersistedRootRuntimeState();
            return;
        }
        if (proxyPid <= 0L) {
            proxyPid = readRootProxyPid();
        }
        if (proxyPid <= 0L) {
            proxyPid = findRunningRootProxyPid(AppPrefs.getSettings(getApplicationContext()));
        }
        AppPrefs.setRootRuntimeState(getApplicationContext(), activeTunnelName, proxyPid);
    }

    private void clearPersistedRootRuntimeState() {
        AppPrefs.clearRootRuntimeState(getApplicationContext());
        AppPrefs.clearRuntimeUpstreamState(getApplicationContext());
        File pidFile = getRootProxyPidFile();
        if (pidFile.exists()) {
            pidFile.delete();
        }
    }

    private boolean isRecoveredRootRuntimeAlive(String tunnelName, long proxyPid) {
        Context appContext = getApplicationContext();
        return RootUtils.isRootAccessGranted(appContext)
                && RootUtils.isRootInterfaceAlive(appContext, tunnelName)
                && (proxyPid <= 0L
                || (RootUtils.isRootProcessAlive(appContext, proxyPid)
                && isExpectedRootProxyProcess(proxyPid)));
    }

    private void killPersistedRootProxyIfNeeded() {
        ProxySettings settings = AppPrefs.getSettings(getApplicationContext());
        LinkedHashSet<Long> proxyPids = new LinkedHashSet<>();
        long persistedPid = readRootProxyPid();
        if (persistedPid > 0L && isExpectedRootProxyProcess(persistedPid)) {
            proxyPids.add(persistedPid);
        }
        proxyPids.addAll(findRunningRootProxyPids(settings));
        if (proxyPids.isEmpty()) {
            return;
        }
        String killCommand = buildKillProxyCommand(proxyPids);
        if (TextUtils.isEmpty(killCommand)) {
            return;
        }
        try {
            runOneShotRootCommand(killCommand, 4_000L);
        } catch (Exception ignored) {
        }
    }

    private void terminateMatchingRootProxyProcesses(@Nullable ProxySettings settings) {
        if (!rootModeActive) {
            return;
        }
        List<Long> proxyPids = findRunningRootProxyPids(settings);
        if (proxyPids.isEmpty()) {
            return;
        }
        String killCommand = buildKillProxyCommand(proxyPids);
        if (TextUtils.isEmpty(killCommand)) {
            return;
        }
        try {
            runOneShotRootCommand(killCommand, 4_000L);
        } catch (Exception ignored) {
        }
    }

    private long findRunningRootProxyPid(@Nullable ProxySettings settings) {
        List<Long> pids = findRunningRootProxyPids(settings);
        return pids.isEmpty() ? 0L : pids.get(0);
    }

    private List<Long> findRunningRootProxyPids(@Nullable ProxySettings settings) {
        try {
            String output = runOneShotRootCommand(
                    "for p in /proc/[0-9]*; do "
                            + "pid=${p##*/}; "
                            + "cmd=$(tr '\\000' ' ' <\"$p/cmdline\" 2>/dev/null || true); "
                            + "[ -z \"$cmd\" ] && continue; "
                            + "printf '%s\\t%s\\n' \"$pid\" \"$cmd\"; "
                            + "done",
                    4_000L
            );
            return parseMatchingProxyPids(output, settings);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    @Nullable
    private String resolveRecoveryTunnelName() {
        Context appContext = getApplicationContext();
        String hintedTunnelName = AppPrefs.getRootRuntimeRecoveryTunnelHint(appContext);
        if (!TextUtils.isEmpty(hintedTunnelName)
                && RootUtils.isRootInterfaceAlive(appContext, hintedTunnelName)) {
            return hintedTunnelName;
        }
        List<String> liveTunnelNames = findRunningRootTunnelNames();
        return liveTunnelNames.isEmpty() ? null : liveTunnelNames.get(0);
    }

    private List<String> findRunningRootTunnelNames() {
        try {
            String output = runOneShotRootCommand(
                    "for f in /sys/class/net/*/type; do "
                            + "iface=${f%/type}; iface=${iface##*/}; "
                            + "type=$(cat \"$f\" 2>/dev/null || true); "
                            + "[ \"$type\" = \"65534\" ] || continue; "
                            + "printf '%s\\n' \"$iface\"; "
                            + "done",
                    4_000L
            );
            if (TextUtils.isEmpty(output)) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            String[] lines = output.split("\\R");
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (TextUtils.isEmpty(trimmed) || ROOT_TUNNEL_NAME.equals(trimmed) || !trimmed.startsWith("wgv")) {
                    continue;
                }
                result.add(trimmed);
            }
            return result;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private boolean isExpectedRootProxyProcess(long proxyPid) {
        if (proxyPid <= 0L) {
            return false;
        }
        try {
            String output = runOneShotRootCommand(
                    "tr '\\000' ' ' </proc/" + proxyPid + "/cmdline 2>/dev/null || true",
                    2_000L
            );
            return !TextUtils.isEmpty(output)
                    && output.contains("libvkturn.so")
                    && output.contains(getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<Long> parseMatchingProxyPids(@Nullable String output, @Nullable ProxySettings settings) {
        if (TextUtils.isEmpty(output)) {
            return Collections.emptyList();
        }
        String listenMarker = settings != null && !TextUtils.isEmpty(settings.localEndpoint)
                ? "-listen " + settings.localEndpoint
                : null;
        String peerMarker = settings != null && !TextUtils.isEmpty(settings.endpoint)
                ? "-peer " + settings.endpoint
                : null;
        String packageMarker = getPackageName();
        List<Long> result = new ArrayList<>();
        String[] lines = output.split("\\R");
        for (String line : lines) {
            if (TextUtils.isEmpty(line) || !line.contains("libvkturn.so")) {
                continue;
            }
            int separator = line.indexOf('\t');
            if (separator <= 0 || separator >= line.length() - 1) {
                continue;
            }
            String pidPart = line.substring(0, separator).trim();
            String commandLine = line.substring(separator + 1);
            if (!TextUtils.isEmpty(packageMarker)
                    && commandLine.contains(packageMarker)
                    && (TextUtils.isEmpty(listenMarker) || commandLine.contains(listenMarker))
                    && (TextUtils.isEmpty(peerMarker) || commandLine.contains(peerMarker))) {
                try {
                    result.add(Long.parseLong(pidPart));
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    @Nullable
    private String buildKillProxyCommand(Collection<Long> proxyPids) {
        if (proxyPids == null || proxyPids.isEmpty()) {
            return null;
        }
        StringBuilder command = new StringBuilder();
        for (Long proxyPid : proxyPids) {
            if (proxyPid == null || proxyPid <= 0L) {
                continue;
            }
            command.append("kill ").append(proxyPid).append(" >/dev/null 2>&1 || true; ")
                    .append("sleep 0.2; ")
                    .append("kill -9 ").append(proxyPid).append(" >/dev/null 2>&1 || true; ");
        }
        return command.length() == 0 ? null : command.toString();
    }

    private String runOneShotRootCommand(String command, long timeoutMs) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Thread outputReader = new Thread(() -> copyProcessOutput(process.getInputStream(), outputStream),
                "wingsv-root-cmd");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroy();
            process.waitFor(200L, TimeUnit.MILLISECONDS);
            process.destroyForcibly();
            throw new IOException("Root command timed out");
        }

        try {
            outputReader.join(Math.min(timeoutMs, 500L));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        }

        String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8).trim();
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    TextUtils.isEmpty(output)
                            ? "Root command exited with code " + exitCode
                            : output
            );
        }
        return output;
    }

    private void copyProcessOutput(InputStream inputStream, ByteArrayOutputStream outputStream) {
        try (InputStream stream = inputStream) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
        }
    }

    private void startProxyOutputReader(Process process, AtomicReference<String> processError) {
        Thread outputReader = new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream())
                );
                String line;
                while ((line = reader.readLine()) != null) {
                    appendProxyLogLine(line);
                    handleProxyEventLine(line);
                    if (line.toLowerCase().contains("panic")
                            || line.toLowerCase().contains("failed")
                            || line.toLowerCase().contains("error")) {
                        processError.set(line);
                        setLastError(line);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "wingsv-proxy-output");
        outputReader.setDaemon(true);
        outputReader.start();
    }

    private void handleProxyEventLine(String line) {
        if (TextUtils.isEmpty(line)) {
            return;
        }
        final String captchaPrefix = "CAPTCHA_REQUIRED:";
        final String pendingCaptchaPrefix = "CAPTCHA_PENDING:";
        if (line.startsWith(captchaPrefix)) {
            clearPendingCaptchaPrompt(getApplicationContext());
            CaptchaPrompt prompt = parseCaptchaPrompt(
                    line.substring(captchaPrefix.length()).trim(),
                    CaptchaPromptSource.PRIMARY
            );
            if (prompt == null) {
                return;
            }
            boolean transientExternalFlow = AppPrefs.isExternalActionTransientLaunchPending(
                    getApplicationContext()
            );
            appendRuntimeLogLine(prompt.source == CaptchaPromptSource.POOL
                    ? "VK captcha requested for additional TURN session"
                    : "VK captcha requested for primary TURN session");
            if (transientExternalFlow && prompt.source == CaptchaPromptSource.PRIMARY) {
                showCaptchaNotification(prompt.url, prompt.source, true, false);
            }
            openCaptchaBrowser(prompt.url, prompt.source);
            return;
        }
        if (line.startsWith(pendingCaptchaPrefix)) {
            CaptchaPrompt prompt = parseCaptchaPrompt(
                    line.substring(pendingCaptchaPrefix.length()).trim(),
                    CaptchaPromptSource.POOL
            );
            if (prompt == null) {
                return;
            }
            appendRuntimeLogLine(prompt.source == CaptchaPromptSource.POOL
                    ? "Background VK captcha deferred for additional TURN session"
                    : "Background VK captcha deferred");
            showCaptchaNotification(prompt.url, prompt.source, false, true);
            return;
        }
        if ("CAPTCHA_SOLVED".equals(line)
                || "CAPTCHA_CANCELLED".equals(line)
                || "CAPTCHA_EXPIRED".equals(line)) {
            clearPendingCaptchaPrompt(getApplicationContext());
        }
    }

    private @Nullable CaptchaPrompt parseCaptchaPrompt(String payload,
                                                       CaptchaPromptSource defaultSource) {
        if (TextUtils.isEmpty(payload)) {
            return null;
        }
        String url = null;
        CaptchaPromptSource source = defaultSource;
        String[] parts = payload.trim().split("\\s+");
        for (String part : parts) {
            if (TextUtils.isEmpty(part)) {
                continue;
            }
            if (part.startsWith("source=")) {
                source = CaptchaPromptSource.fromWireValue(part.substring("source=".length()));
                continue;
            }
            if (part.startsWith("url=")) {
                url = part.substring("url=".length()).trim();
                continue;
            }
            if (url == null) {
                url = part.trim();
            }
        }
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        return new CaptchaPrompt(url, source);
    }

    private void openCaptchaBrowser(String url, CaptchaPromptSource source) {
        try {
            boolean transientExternalFlow = AppPrefs.isExternalActionTransientLaunchPending(
                    getApplicationContext()
            );
            Intent intent = CaptchaBrowserActivity.createIntent(
                    getApplicationContext(),
                    url,
                    transientExternalFlow,
                    source,
                    source.stopsConnectionOnCancel()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception error) {
            appendRuntimeLogLine("Failed to open captcha browser: " + error.getMessage());
        }
    }

    private void showCaptchaNotification(String url,
                                         CaptchaPromptSource source,
                                         boolean transientExternalFlow,
                                         boolean allowCooldown) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        sPendingCaptchaUrl = url;
        sPendingCaptchaSource = source;
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean inCooldown = allowCooldown
                && sLastCaptchaNotificationAtElapsedMs > 0
                && nowElapsed - sLastCaptchaNotificationAtElapsedMs < CAPTCHA_NOTIFICATION_COOLDOWN_MS;
        if (inCooldown) {
            return;
        }

        Intent openIntent = CaptchaBrowserActivity.createIntent(
                this,
                url,
                transientExternalFlow,
                source,
                source.stopsConnectionOnCancel()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                201,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CAPTCHA_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_power)
                .setContentTitle(getString(R.string.captcha_notification_title))
                .setContentText(getString(source == CaptchaPromptSource.POOL
                        ? R.string.captcha_notification_text_pool
                        : R.string.captcha_notification_text))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(getString(source == CaptchaPromptSource.POOL
                                ? R.string.captcha_notification_text_pool
                                : R.string.captcha_notification_text)))
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .addAction(0, getString(R.string.captcha_notification_open), openPendingIntent)
                .setOnlyAlertOnce(false)
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        try {
            notificationManager.notify(CAPTCHA_NOTIFICATION_ID, builder.build());
            sLastCaptchaNotificationAtElapsedMs = nowElapsed;
        } catch (Exception ignored) {
        }
    }

    private void attachProxyWaitThread(Process monitoredProcess) {
        Thread waitThread = new Thread(() -> {
            try {
                int exitCode = monitoredProcess.waitFor();
                if (!stopping) {
                    scheduleRuntimeReconnect(
                            "vk-turn-proxy exited unexpectedly with code " + exitCode,
                            RUNTIME_RECONNECT_DELAY_MS
                    );
                }
            } catch (Exception ignored) {
            } finally {
                if (proxyProcess == monitoredProcess) {
                    proxyProcess = null;
                }
            }
        }, "wingsv-proxy-wait");
        waitThread.setDaemon(true);
        waitThread.start();
    }

    private void waitForUsablePhysicalNetwork(int generation) throws InterruptedException {
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return;
        }

        long deadline = SystemClock.elapsedRealtime() + NETWORK_READY_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            ensureRuntimeStillWanted(generation);
            Network network = connectivityManager.getActiveNetwork();
            if (network != null) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (isUsablePhysicalNetwork(capabilities)) {
                    return;
                }
            }
            sleepInterruptibly(NETWORK_READY_POLL_MS, generation);
        }
    }

    private void waitForProxyWarmup(long proxyStartedAt, int generation) throws InterruptedException {
        long elapsed = SystemClock.elapsedRealtime() - proxyStartedAt;
        long remaining = PROXY_WIREGUARD_DELAY_MS - elapsed;
        if (remaining > 0L) {
            sleepInterruptibly(remaining, generation);
        }
    }

    private void prepareBackend(ProxySettings settings, @Nullable String restoredTunnelName) throws Exception {
        if (!settings.rootModeEnabled) {
            rootModeActive = false;
            activeTunnelName = ROOT_TUNNEL_NAME;
            backend = new GoBackend(getApplicationContext());
            ensureProtectBridgeReady();
            return;
        }
        rootModeActive = true;
        activeTunnelName = TextUtils.isEmpty(restoredTunnelName) ? buildTunnelName() : restoredTunnelName;
        rootShell = new RootShell(getApplicationContext());
        rootShell.start();
        VpnHotspotBridge.initializeRootServer(getApplicationContext());
        toolsInstaller = new ToolsInstaller(getApplicationContext(), rootShell);
        toolsInstaller.ensureToolsAvailable();
        WgQuickBackend rootBackend = new WgQuickBackend(getApplicationContext(), rootShell, toolsInstaller);
        rootBackend.setMultipleTunnels(false);
        backend = rootBackend;
        closeProtectBridge();
        protectSocketName = null;
    }

    private void ensureProtectBridgeReady() {
        VpnService vpnService = GoBackendVpnAccess.ensureServiceStarted(getApplicationContext());
        if (vpnService == null) {
            throw new IllegalStateException("Не удалось запустить VPN protect bridge");
        }
        closeProtectBridge();
        protectSocketName = "wingsv_protect_" + UUID.randomUUID().toString().replace("-", "");
        try {
            protectBridgeServer = new ProxyProtectBridgeServer(
                    protectSocketName,
                    GoBackendVpnAccess::getServiceNow
            );
        } catch (Exception error) {
            protectSocketName = null;
            throw new IllegalStateException("Не удалось открыть protect socket: " + error.getMessage(), error);
        }
    }

    private void closeProtectBridge() {
        if (protectBridgeServer != null) {
            protectBridgeServer.close();
            protectBridgeServer = null;
        }
    }

    private void registerTetherReceiverIfNeeded() {
        if (tetherReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(TetherType.ACTION_TETHER_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tetherStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(tetherStateReceiver, filter);
        }
        tetherReceiverRegistered = true;
        persistActiveTetherTypes(getStickyTetherIntent());
    }

    private void registerTetherEventCallbackIfNeeded() {
        if (tetherEventCallbackRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        TetheringManager tetheringManager = getSystemService(TetheringManager.class);
        if (tetheringManager == null || tetheringEventCallback == null) {
            return;
        }
        Executor executor = getMainExecutor();
        tetheringManager.registerTetheringEventCallback(executor, tetheringEventCallback);
        tetherEventCallbackRegistered = true;
    }

    private void unregisterTetherReceiver() {
        if (!tetherReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(tetherStateReceiver);
        } catch (Exception ignored) {
        }
        tetherReceiverRegistered = false;
    }

    private void unregisterTetherEventCallback() {
        if (!tetherEventCallbackRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            activeTetheredInterfaces = Collections.emptySet();
            tetherEventCallbackRegistered = false;
            return;
        }
        TetheringManager tetheringManager = getSystemService(TetheringManager.class);
        if (tetheringManager != null && tetheringEventCallback != null) {
            try {
                tetheringManager.unregisterTetheringEventCallback(tetheringEventCallback);
            } catch (Exception ignored) {
            }
        }
        activeTetheredInterfaces = Collections.emptySet();
        tetherEventCallbackRegistered = false;
    }

    private RootRoutingState captureRootRoutingState() throws Exception {
        String upstreamOverride = AppPrefs.getSharingUpstreamInterface(getApplicationContext());
        String fallbackOverride = AppPrefs.getSharingFallbackUpstreamInterface(getApplicationContext());

        List<String> ipv4MainRoutes = readDefaultRouteLinesAsRoot("ip route show table main");
        List<String> ipv6MainRoutes = readDefaultRouteLinesAsRoot("ip -6 route show table main");
        List<String> ipv4AllRoutes = new ArrayList<>(ipv4MainRoutes);
        List<String> ipv6AllRoutes = new ArrayList<>(ipv6MainRoutes);
        if (ipv4AllRoutes.isEmpty()) {
            ipv4AllRoutes.addAll(readDefaultRouteLinesAsRoot("ip route show"));
        }
        if (ipv6AllRoutes.isEmpty()) {
            ipv6AllRoutes.addAll(readDefaultRouteLinesAsRoot("ip -6 route show"));
        }

        List<String> ipv4Routes = selectPreferredDefaultRoutes(ipv4MainRoutes, ipv4AllRoutes, upstreamOverride, fallbackOverride);
        List<String> ipv6Routes = selectPreferredDefaultRoutes(ipv6MainRoutes, ipv6AllRoutes, upstreamOverride, fallbackOverride);
        if (ipv4Routes.isEmpty() && ipv6Routes.isEmpty()) {
            appendActiveNetworkDefaultRoutes(ipv4Routes, ipv6Routes);
        }
        if (ipv4Routes.isEmpty() && ipv6Routes.isEmpty()) {
            throw new IllegalStateException("Не удалось определить upstream маршрут для root режима: default route не найден");
        }
        return new RootRoutingState(
                ipv4Routes,
                ipv6Routes,
                collectRootBypassUids()
        );
    }

    private void syncRootTetherRouting(@Nullable Intent tetherIntent) {
        if (!rootModeActive) {
            return;
        }
        String upstreamNameForLog;
        if (activeBackendType == BackendType.XRAY) {
            upstreamNameForLog = firstNonEmpty(
                    AppPrefs.getSharingUpstreamInterface(getApplicationContext()),
                    "vpn"
            );
        } else {
            String liveTunnelName = !TextUtils.isEmpty(activeTunnelName)
                    && RootUtils.isRootInterfaceAlive(getApplicationContext(), activeTunnelName)
                    ? activeTunnelName
                    : resolveRecoveryTunnelName();
            if (TextUtils.isEmpty(liveTunnelName)) {
                AppPrefs.clearRuntimeUpstreamState(getApplicationContext());
                appendRuntimeLogLine("Root tether routing skipped: live root tunnel не найден");
                return;
            }
            activeTunnelName = liveTunnelName;
            upstreamNameForLog = liveTunnelName;
        }
        Set<String> tetherInterfaces = readLiveTetheredInterfaces(
                tetherIntent != null ? tetherIntent : getStickyTetherIntent()
        );
        Set<String> configuredInterfaces = new LinkedHashSet<>();
        for (String tetherInterface : tetherInterfaces) {
            if (TextUtils.isEmpty(tetherInterface)
                    || tetherInterface.equals(activeTunnelName)
                    || tetherInterface.startsWith("lo")) {
                continue;
            }
            configuredInterfaces.add(tetherInterface);
        }
        try {
            VpnHotspotBridge.syncSharing(
                    getApplicationContext(),
                    configuredInterfaces,
                    buildSharingConfig()
            );
            syncSharingWifiLocks(configuredInterfaces);
            appliedTetherUpstreamName = upstreamNameForLog;
            String syncMessage = "Root tether routing synced: " + configuredInterfaces + " upstream=" + upstreamNameForLog;
            appendRuntimeLogLine(syncMessage);
            Log.i(TAG, syncMessage);
        } catch (Exception error) {
            releaseSharingWifiLocks();
            appendRuntimeLogLine("Root tether routing sync failed: " + error.getMessage());
            Log.e(TAG, "Root tether routing sync failed", error);
        }
    }

    @Nullable
    private Intent getStickyTetherIntent() {
        IntentFilter filter = new IntentFilter(TetherType.ACTION_TETHER_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        return registerReceiver(null, filter);
    }

    private void clearRootTetherRouting() {
        VpnHotspotBridge.stopSharing(getApplicationContext());
        appliedTetherUpstreamName = null;
        releaseSharingWifiLocks();
    }

    private void requestRootTetherRoutingSync(@Nullable Intent tetherIntent) {
        if (!rootModeActive) {
            return;
        }
        final Intent syncIntent = tetherIntent != null ? new Intent(tetherIntent) : null;
        if (!rootTetherSyncQueued.compareAndSet(false, true)) {
            return;
        }
        workExecutor.execute(() -> {
            try {
                syncRootTetherRouting(syncIntent);
            } finally {
                rootTetherSyncQueued.set(false);
            }
        });
    }

    private Set<String> readLiveTetheredInterfaces(@Nullable Intent tetherIntent) {
        LinkedHashSet<String> interfaces = new LinkedHashSet<>();
        interfaces.addAll(activeTetheredInterfaces);
        interfaces.addAll(TetherType.readEnabledInterfaces(tetherIntent));
        interfaces.addAll(readTetheredInterfacesFromDumpsys());
        persistActiveTetherTypes(interfaces);
        return interfaces;
    }

    private void restoreSharingOnBootIfNeeded() {
        if (!pendingSharingRestoreOnBoot) {
            return;
        }
        pendingSharingRestoreOnBoot = false;
        Context appContext = getApplicationContext();
        if (!rootModeActive || !AppPrefs.isSharingAutoStartOnBootEnabled(appContext)) {
            return;
        }
        Set<TetherType> desiredTypes = AppPrefs.getSharingLastActiveTypes(appContext);
        if (desiredTypes.isEmpty()) {
            appendRuntimeLogLine("Sharing boot restore skipped: no saved tether types");
            return;
        }
        Set<TetherType> currentTypes = TetherType.readEnabledTypes(getStickyTetherIntent());
        String firstError = null;
        boolean changed = false;
        for (TetherType type : desiredTypes) {
            if (type == null || currentTypes.contains(type)) {
                continue;
            }
            try {
                RootUtils.runRootHelper(appContext, "tether", "start", type.commandName);
                changed = true;
                appendRuntimeLogLine("Sharing boot restore started: " + type.commandName);
            } catch (Exception error) {
                if (TextUtils.isEmpty(firstError)) {
                    firstError = error.getMessage();
                }
                appendRuntimeLogLine("Sharing boot restore failed for "
                        + type.commandName + ": " + error.getMessage());
            }
        }
        if (!TextUtils.isEmpty(firstError)) {
            setLastError(firstError);
        }
        if (changed) {
            SystemClock.sleep(1_000L);
            Intent tetherIntent = getStickyTetherIntent();
            persistActiveTetherTypes(tetherIntent);
            requestRootTetherRoutingSync(tetherIntent);
        }
    }

    private void persistActiveTetherTypes(@Nullable Intent tetherIntent) {
        AppPrefs.setSharingLastActiveTypes(
                getApplicationContext(),
                TetherType.readEnabledTypes(tetherIntent)
        );
    }

    private void persistActiveTetherTypes(@Nullable Set<String> interfaceNames) {
        LinkedHashSet<TetherType> types = new LinkedHashSet<>();
        if (interfaceNames != null) {
            for (String interfaceName : interfaceNames) {
                TetherType type = TetherType.detectFromInterfaceName(interfaceName);
                if (type != null) {
                    types.add(type);
                }
            }
        }
        AppPrefs.setSharingLastActiveTypes(getApplicationContext(), types);
    }

    private Set<String> readTetheredInterfacesFromDumpsys() {
        LinkedHashSet<String> interfaces = new LinkedHashSet<>();
        List<String> lines = runRootRoutingCommandLines(
                "dumpsys tethering 2>/dev/null || dumpsys connectivity tethering 2>/dev/null"
        );
        boolean inTetherStateSection = false;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!inTetherStateSection) {
                if ("Tether state:".equals(trimmed)) {
                    inTetherStateSection = true;
                }
                continue;
            }
            if (!line.startsWith("    ")) {
                break;
            }
            Matcher matcher = ACTIVE_TETHER_DUMPSYS_PATTERN.matcher(trimmed);
            if (matcher.matches()) {
                String interfaceName = matcher.group(1);
                if (!TextUtils.isEmpty(interfaceName)) {
                    interfaces.add(interfaceName);
                }
            }
        }
        return interfaces;
    }

    private void appendRootTetherCleanupCommands(StringBuilder script) {
        Set<String> previousTetherInterfaces = rootRoutingState != null
                ? new LinkedHashSet<>(rootRoutingState.tetherInterfaces)
                : new LinkedHashSet<>();
        String previousUpstream = firstNonEmpty(appliedTetherUpstreamName, activeTunnelName);
        String quotedUpstream = RootUtils.shellQuote(previousUpstream);
        script.append("while ip rule del priority ").append(ROOT_TETHER_RULE_PRIORITY_UPSTREAM).append("; do :; done;");
        script.append("while ip rule del priority ").append(ROOT_TETHER_RULE_PRIORITY_UPSTREAM_FALLBACK).append("; do :; done;");
        script.append("while ip rule del priority ").append(ROOT_TETHER_RULE_PRIORITY_BLOCK_SYSTEM).append("; do :; done;");
        script.append("while ip -6 rule del priority ").append(ROOT_TETHER_RULE_PRIORITY_UPSTREAM).append("; do :; done;");
        script.append("while ip -6 rule del priority ").append(ROOT_TETHER_RULE_PRIORITY_UPSTREAM_FALLBACK).append("; do :; done;");
        script.append("while ip -6 rule del priority ").append(ROOT_TETHER_RULE_PRIORITY_BLOCK_SYSTEM).append("; do :; done;");

        for (String tetherInterface : previousTetherInterfaces) {
            String quotedInterface = RootUtils.shellQuote(tetherInterface);
            String requestName = RootUtils.shellQuote("wingsv_" + tetherInterface);
            script.append("ndc nat disable ")
                    .append(quotedInterface)
                    .append(' ')
                    .append(quotedUpstream)
                    .append(" 0 || true;");
            script.append("ndc ipfwd disable ").append(requestName).append(" || true;");
        }
        script.append("iptables -w -D FORWARD -j ").append(ROOT_TETHER_FORWARD_CHAIN).append(" || true;");
        script.append("iptables -w -F ").append(ROOT_TETHER_FORWARD_CHAIN).append(" || true;");
        script.append("iptables -w -X ").append(ROOT_TETHER_FORWARD_CHAIN).append(" || true;");
        script.append("iptables -w -t nat -D PREROUTING -j ").append(ROOT_TETHER_DNS_CHAIN).append(" || true;");
        script.append("iptables -w -t nat -F ").append(ROOT_TETHER_DNS_CHAIN).append(" || true;");
        script.append("iptables -w -t nat -X ").append(ROOT_TETHER_DNS_CHAIN).append(" || true;");
        script.append("iptables -w -t nat -D POSTROUTING -j ").append(ROOT_TETHER_NAT_CHAIN).append(" || true;");
        script.append("iptables -w -t nat -F ").append(ROOT_TETHER_NAT_CHAIN).append(" || true;");
        script.append("iptables -w -t nat -X ").append(ROOT_TETHER_NAT_CHAIN).append(" || true;");
        script.append("ip6tables -w -D FORWARD -j ").append(ROOT_TETHER_IPV6_CHAIN).append(" || true;");
        script.append("ip6tables -w -F ").append(ROOT_TETHER_IPV6_CHAIN).append(" || true;");
        script.append("ip6tables -w -X ").append(ROOT_TETHER_IPV6_CHAIN).append(" || true;");
    }

    private void reapplyRootSharingState() throws Exception {
        clearRootTetherRouting();
        clearRootRouting();
        rootRoutingState = captureRootRoutingState();
        applyRootRouting(rootRoutingState);
        syncRootTetherRouting(null);
        requestPublicIpRefresh(true);
    }

    private String readInterfaceSubnetAsRoot(String interfaceName) {
        if (!rootModeActive || TextUtils.isEmpty(interfaceName)) {
            return null;
        }
        List<String> output = runRootRoutingCommandLines("ip -4 addr show dev " + RootUtils.shellQuote(interfaceName));
        for (String line : output) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.startsWith("inet ")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return null;
    }

    private String readInterfaceIpv4AddressAsRoot(String interfaceName) {
        String subnet = readInterfaceSubnetAsRoot(interfaceName);
        if (TextUtils.isEmpty(subnet)) {
            return null;
        }
        int separator = subnet.indexOf('/');
        return separator > 0 ? subnet.substring(0, separator) : subnet;
    }

    private void appendActiveNetworkDefaultRoutes(List<String> ipv4Routes, List<String> ipv6Routes) {
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return;
        }
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
        if (!isUsablePhysicalNetwork(capabilities)) {
            return;
        }
        LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
        if (linkProperties == null) {
            return;
        }
        String fallbackInterface = linkProperties.getInterfaceName();
        for (RouteInfo routeInfo : linkProperties.getRoutes()) {
            if (routeInfo == null || !routeInfo.isDefaultRoute()) {
                continue;
            }
            String routeLine = buildDefaultRouteLine(routeInfo, fallbackInterface);
            if (TextUtils.isEmpty(routeLine)) {
                continue;
            }
            if (isIpv6Route(routeInfo)) {
                if (!ipv6Routes.contains(routeLine)) {
                    ipv6Routes.add(routeLine);
                }
            } else if (!ipv4Routes.contains(routeLine)) {
                ipv4Routes.add(routeLine);
            }
        }
    }

    private Set<Integer> collectRootBypassUids() {
        if (!AppPrefs.isAppRoutingBypassEnabled(getApplicationContext())) {
            return new LinkedHashSet<>();
        }
        Set<Integer> result = new LinkedHashSet<>();
        Set<String> packages = AppPrefs.getAppRoutingPackages(getApplicationContext());
        for (String packageName : packages) {
            try {
                result.add(getPackageManager().getApplicationInfo(packageName, 0).uid);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private void applyRootRouting(@Nullable RootRoutingState routingState) throws Exception {
        if (!rootModeActive || routingState == null) {
            return;
        }
        try {
            VpnHotspotBridge.setupVpnFirewall(getApplicationContext());
        } catch (Exception error) {
            appendRuntimeLogLine("Root firewall setup warning: " + error.getMessage());
            Log.w(TAG, "Root firewall setup warning", error);
        }
        StringBuilder script = new StringBuilder("set -e;");
        appendRootCleanupCommands(script, null);
        script.append("ndc network protect allow ")
                .append(android.os.Process.myUid())
                .append(" || true;");
        if (AppPrefs.isSharingDhcpWorkaroundEnabled(getApplicationContext())) {
            script.append("ip rule add iif lo uidrange 0-0 lookup 97 priority ")
                    .append(ROOT_DHCP_WORKAROUND_PRIORITY)
                    .append(" || true;");
        }
        for (String route : routingState.ipv4Routes) {
            script.append("ip route add table ")
                    .append(ROOT_UPSTREAM_TABLE)
                    .append(' ')
                    .append(route)
                    .append(';');
        }
        for (String route : routingState.ipv6Routes) {
            script.append("ip -6 route add table ")
                    .append(ROOT_UPSTREAM_TABLE)
                    .append(' ')
                    .append(route)
                    .append(" || true;");
        }

        int priority = ROOT_RULE_PRIORITY_START;
        routingState.rulePriorities.add(priority);
        script.append("ip rule add pref ")
                .append(priority)
                .append(" uidrange 0-0 lookup ")
                .append(ROOT_UPSTREAM_TABLE)
                .append(';');
        script.append("ip -6 rule add pref ")
                .append(priority)
                .append(" uidrange 0-0 lookup ")
                .append(ROOT_UPSTREAM_TABLE)
                .append(" || true;");
        priority++;

        for (Integer uid : routingState.bypassUids) {
            if (uid == null || uid < 0 || priority > ROOT_RULE_PRIORITY_END) {
                continue;
            }
            routingState.rulePriorities.add(priority);
            script.append("ip rule add pref ")
                    .append(priority)
                    .append(" uidrange ")
                    .append(uid)
                    .append('-')
                    .append(uid)
                    .append(" lookup ")
                    .append(ROOT_UPSTREAM_TABLE)
                    .append(';');
            script.append("ip -6 rule add pref ")
                    .append(priority)
                    .append(" uidrange ")
                    .append(uid)
                    .append('-')
                    .append(uid)
                    .append(" lookup ")
                    .append(ROOT_UPSTREAM_TABLE)
                    .append(" || true;");
            priority++;
        }

        runRootRoutingCommand(script.toString());
    }

    private void clearRootRouting() {
        RootRoutingState routingState = rootRoutingState;
        rootRoutingState = null;
        if (!rootModeActive) {
            return;
        }
        try {
            StringBuilder script = new StringBuilder();
            appendRootCleanupCommands(script, routingState);
            runRootRoutingCommand(script.toString());
        } catch (Exception ignored) {
        }
    }

    private void appendRootCleanupCommands(StringBuilder script, @Nullable RootRoutingState routingState) {
        List<Integer> priorities = routingState != null ? routingState.rulePriorities : null;
        if (priorities == null || priorities.isEmpty()) {
            for (int priority = ROOT_RULE_PRIORITY_START; priority <= ROOT_RULE_PRIORITY_END; priority++) {
                script.append("ip rule del pref ").append(priority).append(" || true;");
                script.append("ip -6 rule del pref ").append(priority).append(" || true;");
            }
        } else {
            for (Integer priority : priorities) {
                if (priority == null) {
                    continue;
                }
                script.append("ip rule del pref ").append(priority).append(" || true;");
                script.append("ip -6 rule del pref ").append(priority).append(" || true;");
            }
        }
        script.append("ip route flush table ").append(ROOT_UPSTREAM_TABLE).append(" || true;");
        script.append("ip -6 route flush table ").append(ROOT_UPSTREAM_TABLE).append(" || true;");
        script.append("ip rule del iif lo uidrange 0-0 lookup 97 priority ")
                .append(ROOT_DHCP_WORKAROUND_PRIORITY)
                .append(" || true;");
    }

    private void syncRootAppTunnelRouting() {
        if (!rootModeActive) {
            return;
        }
        String tunnelTableLookup = resolveTunnelTableLookup();
        if (TextUtils.isEmpty(tunnelTableLookup)) {
            appendRuntimeLogLine("Root app tunnel routing skipped: table для " + activeTunnelName + " не найдена");
            return;
        }
        int appUid = getApplicationInfo().uid;
        StringBuilder script = new StringBuilder();
        script.append("ip rule del pref ").append(ROOT_APP_TUNNEL_PRIORITY).append(" || true;");
        script.append("ip -6 rule del pref ").append(ROOT_APP_TUNNEL_PRIORITY).append(" || true;");
        script.append("ip rule add pref ").append(ROOT_APP_TUNNEL_PRIORITY)
                .append(" uidrange ").append(appUid).append('-').append(appUid)
                .append(" lookup ").append(RootUtils.shellQuote(tunnelTableLookup)).append(" || true;");
        script.append("ip -6 rule add pref ").append(ROOT_APP_TUNNEL_PRIORITY)
                .append(" uidrange ").append(appUid).append('-').append(appUid)
                .append(" lookup ").append(RootUtils.shellQuote(tunnelTableLookup)).append(" || true;");
        try {
            runRootRoutingCommand(script.toString());
        } catch (Exception error) {
            appendRuntimeLogLine("Root app tunnel routing failed: " + error.getMessage());
        }
    }

    private void clearRootAppTunnelRouting() {
        if (!rootModeActive && rootShell == null) {
            return;
        }
        try {
            StringBuilder script = new StringBuilder();
            script.append("ip rule del pref ").append(ROOT_APP_TUNNEL_PRIORITY).append(" || true;");
            script.append("ip -6 rule del pref ").append(ROOT_APP_TUNNEL_PRIORITY).append(" || true;");
            runRootRoutingCommand(script.toString());
        } catch (Exception ignored) {
        }
    }

    private VpnHotspotSharingConfig buildSharingConfig() {
        ProxySettings settings = AppPrefs.getSettings(getApplicationContext());
        String upstreamInterface = AppPrefs.getSharingUpstreamInterface(getApplicationContext());
        String explicitDnsServers = settings != null ? settings.wgDns : "";

        if (activeBackendType == BackendType.XRAY) {
            explicitDnsServers = buildXrayExplicitDnsServers(settings);
        } else if (TextUtils.isEmpty(upstreamInterface)) {
            upstreamInterface = activeTunnelName;
            if (rootModeActive && !RootUtils.isRootInterfaceAlive(getApplicationContext(), upstreamInterface)) {
                String recoveredTunnelName = resolveRecoveryTunnelName();
                if (!TextUtils.isEmpty(recoveredTunnelName)) {
                    upstreamInterface = recoveredTunnelName;
                }
            }
        }
        return new VpnHotspotSharingConfig(
                upstreamInterface,
                AppPrefs.getSharingFallbackUpstreamInterface(getApplicationContext()),
                explicitDnsServers,
                AppPrefs.getSharingMasqueradeMode(getApplicationContext()),
                AppPrefs.isSharingDhcpWorkaroundEnabled(getApplicationContext()),
                AppPrefs.isSharingDisableIpv6Enabled(getApplicationContext())
        );
    }

    private String buildXrayExplicitDnsServers(@Nullable ProxySettings settings) {
        if (settings == null || settings.xraySettings == null) {
            return "";
        }
        LinkedHashSet<String> dnsServers = new LinkedHashSet<>();
        String remoteDns = settings.xraySettings.remoteDns;
        String directDns = settings.xraySettings.directDns;
        if (!TextUtils.isEmpty(remoteDns)) {
            dnsServers.add(remoteDns.trim());
        }
        if (!TextUtils.isEmpty(directDns)) {
            dnsServers.add(directDns.trim());
        }
        return TextUtils.join(", ", dnsServers);
    }

    @Nullable
    private String resolveTunnelTableLookup() {
        if (!rootModeActive || TextUtils.isEmpty(activeTunnelName)) {
            return null;
        }
        List<String> routeLines = runRootRoutingCommandLines("ip route show table all");
        String parsed = parseTunnelTableLookup(routeLines, activeTunnelName);
        if (!TextUtils.isEmpty(parsed)) {
            return parsed;
        }
        routeLines = runRootRoutingCommandLines("ip -6 route show table all");
        parsed = parseTunnelTableLookup(routeLines, activeTunnelName);
        if (!TextUtils.isEmpty(parsed)) {
            return parsed;
        }
        return null;
    }

    @Nullable
    private String resolveTunnelRuleLookup(@Nullable String interfaceName) {
        if (!rootModeActive || TextUtils.isEmpty(interfaceName)) {
            return null;
        }
        List<String> ifindexOutput = runRootRoutingCommandLines(
                "cat " + RootUtils.shellQuote("/sys/class/net/" + interfaceName + "/ifindex") + " 2>/dev/null"
        );
        for (String line : ifindexOutput) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.matches("\\d+")) {
                try {
                    int ifindex = Integer.parseInt(trimmed);
                    if (ifindex > 0) {
                        return Integer.toString(1000 + ifindex);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return resolveTunnelTableLookup();
    }

    private String resolveEffectiveMasqueradeMode(String configuredMode) {
        if (!AppPrefs.SHARING_MASQUERADE_SIMPLE.equals(configuredMode)) {
            return configuredMode;
        }
        if (isRootNatTableAvailable()) {
            return configuredMode;
        }
        appendRuntimeLogLine("Root tether NAT fallback: iptables nat недоступен, переключаемся на netd");
        Log.w(TAG, "Root tether NAT fallback: iptables nat unavailable, using netd masquerade");
        return AppPrefs.SHARING_MASQUERADE_NETD;
    }

    private boolean isRootNatTableAvailable() {
        if (!rootModeActive) {
            return false;
        }
        try {
            RootProcessResult result = VpnHotspotBridge.runRootQuiet(
                    getApplicationContext(),
                    "iptables -w -t nat -L >/dev/null 2>&1",
                    false
            );
            return result.getExitCode() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Nullable
    private String parseTunnelTableLookup(@Nullable List<String> routeLines, @Nullable String interfaceName) {
        if (routeLines == null || TextUtils.isEmpty(interfaceName)) {
            return null;
        }
        String marker = " dev " + interfaceName + " table ";
        for (String line : routeLines) {
            String trimmed = line == null ? "" : line.trim();
            int markerIndex = trimmed.indexOf(marker);
            if (markerIndex < 0) {
                continue;
            }
            String tail = trimmed.substring(markerIndex + marker.length()).trim();
            if (tail.isEmpty()) {
                continue;
            }
            String tableLookup = tail.split("\\s+", 2)[0];
            if (!TextUtils.isEmpty(tableLookup)) {
                return tableLookup;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void syncSharingWifiLocks(@Nullable Set<String> tetherInterfaces) {
        boolean wifiSharingActive = false;
        if (tetherInterfaces != null) {
            for (String tetherInterface : tetherInterfaces) {
                if (TetherType.detectFromInterfaceName(tetherInterface) == TetherType.WIFI) {
                    wifiSharingActive = true;
                    break;
                }
            }
        }
        String lockMode = AppPrefs.getSharingWifiLockMode(getApplicationContext());
        if (!wifiSharingActive || AppPrefs.SHARING_WIFI_LOCK_SYSTEM.equals(lockMode)) {
            releaseSharingWifiLocks();
            return;
        }
        WifiManager wifiManager = getSystemService(WifiManager.class);
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (wifiManager == null || powerManager == null) {
            return;
        }
        int wifiLockMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        if (AppPrefs.SHARING_WIFI_LOCK_FULL.equals(lockMode)) {
            wifiLockMode = WifiManager.WIFI_MODE_FULL;
        } else if (AppPrefs.SHARING_WIFI_LOCK_LOW_LATENCY.equals(lockMode)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiLockMode = WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
        }
        if (sharingWifiLock == null || sharingWifiLockMode != wifiLockMode) {
            releaseSharingWifiLocks();
            sharingWifiLock = wifiManager.createWifiLock(wifiLockMode, "wingsv:sharing:wifi");
            sharingWifiLock.setReferenceCounted(false);
            sharingWifiLockMode = wifiLockMode;
        }
        if (!sharingWifiLock.isHeld()) {
            sharingWifiLock.acquire();
        }
        if (sharingPowerLock == null) {
            sharingPowerLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wingsv:sharing:power");
            sharingPowerLock.setReferenceCounted(false);
        }
        if (!sharingPowerLock.isHeld()) {
            sharingPowerLock.acquire();
        }
    }

    private void releaseSharingWifiLocks() {
        if (sharingWifiLock != null && sharingWifiLock.isHeld()) {
            sharingWifiLock.release();
        }
        if (sharingPowerLock != null && sharingPowerLock.isHeld()) {
            sharingPowerLock.release();
        }
        sharingWifiLock = null;
        sharingPowerLock = null;
        sharingWifiLockMode = -1;
    }

    private List<String> readDefaultRouteLinesAsRoot(String command) {
        if (!rootModeActive || TextUtils.isEmpty(command)) {
            return new ArrayList<>();
        }
        List<String> output = runRootRoutingCommandLines(command);
        List<String> routes = new ArrayList<>();
        for (String line : output) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("default ")) {
                routes.add(trimmed);
            }
        }
        return routes;
    }

    private void runRootRoutingCommand(String command) throws Exception {
        if (!rootModeActive || TextUtils.isEmpty(command)) {
            return;
        }
        RootProcessResult result = VpnHotspotBridge.runRootQuiet(getApplicationContext(), command, false);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(result.primaryMessage());
        }
    }

    private List<String> runRootRoutingCommandLines(String command) {
        List<String> lines = new ArrayList<>();
        if (!rootModeActive || TextUtils.isEmpty(command)) {
            return lines;
        }
        try {
            RootProcessResult result = VpnHotspotBridge.runRootQuiet(getApplicationContext(), command, false);
            if (result.getExitCode() != 0) {
                appendRuntimeLogLine("Root routing command failed: " + result.primaryMessage());
                return lines;
            }
            if (TextUtils.isEmpty(result.getStdout())) {
                return lines;
            }
            Collections.addAll(lines, result.getStdout().split("\\r?\\n"));
            return lines;
        } catch (Exception error) {
            appendRuntimeLogLine("Root routing command failed: " + error.getMessage());
            return lines;
        }
    }

    private List<String> selectPreferredDefaultRoutes(List<String> mainRoutes,
                                                      List<String> fallbackRoutes,
                                                      @Nullable String preferredInterface,
                                                      @Nullable String fallbackInterface) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(preferredInterface)) {
            selected.addAll(filterDefaultRoutesByInterface(mainRoutes, preferredInterface));
            if (selected.isEmpty()) {
                selected.addAll(filterDefaultRoutesByInterface(fallbackRoutes, preferredInterface));
            }
        }
        if (selected.isEmpty() && !TextUtils.isEmpty(fallbackInterface)) {
            selected.addAll(filterDefaultRoutesByInterface(mainRoutes, fallbackInterface));
            if (selected.isEmpty()) {
                selected.addAll(filterDefaultRoutesByInterface(fallbackRoutes, fallbackInterface));
            }
        }
        if (selected.isEmpty()) {
            selected.addAll(mainRoutes);
        }
        if (selected.isEmpty()) {
            selected.addAll(fallbackRoutes);
        }
        return new ArrayList<>(selected);
    }

    private List<String> filterDefaultRoutesByInterface(List<String> routes, String interfaceName) {
        List<String> filtered = new ArrayList<>();
        if (routes == null || routes.isEmpty() || TextUtils.isEmpty(interfaceName)) {
            return filtered;
        }
        String marker = " dev " + interfaceName;
        for (String route : routes) {
            if (!TextUtils.isEmpty(route) && route.contains(marker)) {
                filtered.add(route);
            }
        }
        return filtered;
    }

    private List<String> readDefaultRouteLines(String... command) throws IOException, InterruptedException {
        List<String> routes = new ArrayList<>();
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("default ")) {
                    routes.add(trimmed);
                }
            }
        }
        process.waitFor();
        return routes;
    }

    private String buildDefaultRouteLine(RouteInfo routeInfo, @Nullable String fallbackInterface) {
        if (routeInfo == null || !routeInfo.isDefaultRoute()) {
            return null;
        }
        StringBuilder builder = new StringBuilder("default");
        InetAddress gateway = routeInfo.getGateway();
        if (gateway != null && !gateway.isAnyLocalAddress() && !gateway.isLoopbackAddress()) {
            builder.append(" via ").append(normalizeHostAddress(gateway));
        }
        String interfaceName = firstNonEmpty(routeInfo.getInterface(), fallbackInterface);
        if (!TextUtils.isEmpty(interfaceName)) {
            builder.append(" dev ").append(interfaceName);
        }
        return builder.toString();
    }

    private boolean isIpv6Route(RouteInfo routeInfo) {
        if (routeInfo == null || routeInfo.getDestination() == null) {
            return false;
        }
        InetAddress destinationAddress = routeInfo.getDestination().getAddress();
        if (destinationAddress instanceof Inet6Address) {
            return true;
        }
        if (destinationAddress instanceof Inet4Address) {
            return false;
        }
        InetAddress gateway = routeInfo.getGateway();
        return gateway instanceof Inet6Address;
    }

    private String normalizeHostAddress(InetAddress address) {
        if (address == null) {
            return "";
        }
        String hostAddress = address.getHostAddress();
        int scopeSeparator = hostAddress.indexOf('%');
        if (scopeSeparator > 0) {
            return hostAddress.substring(0, scopeSeparator);
        }
        return hostAddress;
    }

    private String joinShellCommand(List<String> command) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < command.size(); index++) {
            if (index > 0) {
                builder.append(' ');
            }
            builder.append(RootUtils.shellQuote(command.get(index)));
        }
        return builder.toString();
    }

    private String buildTunnelName() {
        if (!rootModeActive) {
            return ROOT_TUNNEL_NAME;
        }
        long suffix = SystemClock.elapsedRealtime() & 0xFFFFFFL;
        return "wgv" + Long.toHexString(suffix);
    }

    private void forceLinkDownActiveTunnelIfNeeded() {
        if (activeBackendType == BackendType.XRAY) {
            return;
        }
        Context appContext = getApplicationContext();
        String persistedTunnelName = AppPrefs.getRootRuntimeTunnelName(appContext);
        String tunnelName = !TextUtils.isEmpty(persistedTunnelName) ? persistedTunnelName : activeTunnelName;
        if (TextUtils.isEmpty(tunnelName) || ROOT_TUNNEL_NAME.equals(tunnelName) && !AppPrefs.hasRootRuntimeState(appContext) && !rootModeActive) {
            return;
        }

        RootShell shell = rootShell;
        boolean temporaryShell = false;
        try {
            if (shell == null) {
                shell = new RootShell(appContext);
                shell.start();
                temporaryShell = true;
            }
            shell.run(
                    null,
                    "ip link set dev " + RootUtils.shellQuote(tunnelName) + " down >/dev/null 2>&1 || true; "
                            + "ip link delete dev " + RootUtils.shellQuote(tunnelName) + " >/dev/null 2>&1 || true"
            );
        } catch (Exception ignored) {
        } finally {
            if (temporaryShell) {
                try {
                    shell.stop();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean shouldAttemptRootRuntimeRecovery() {
        Context appContext = getApplicationContext();
        return XrayStore.getBackendType(appContext) == BackendType.VK_TURN_WIREGUARD
                && AppPrefs.isRootModeEnabled(appContext)
                && AppPrefs.hasRootRuntimeHint(appContext);
    }

    private void invalidateRuntimeOperations() {
        stopping = true;
        runtimeGeneration.incrementAndGet();
        Future<?> task = activeWorkTask;
        if (task != null) {
            task.cancel(true);
        }
    }

    private void ensureRuntimeStillWanted(int generation) throws InterruptedException {
        if (generation != runtimeGeneration.get() || stopping || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Runtime start interrupted");
        }
    }

    private void sleepInterruptibly(long durationMs, int generation) throws InterruptedException {
        if (durationMs <= 0L) {
            ensureRuntimeStillWanted(generation);
            return;
        }
        long deadline = SystemClock.elapsedRealtime() + durationMs;
        while (true) {
            ensureRuntimeStillWanted(generation);
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) {
                return;
            }
            Thread.sleep(Math.min(remaining, 250L));
        }
    }

    private String firstNonEmpty(String primary, String fallback) {
        return !TextUtils.isEmpty(primary) ? primary : fallback;
    }

    private boolean isUsablePhysicalNetwork(@Nullable NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return false;
        }
        boolean isPhysicalTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        if (!isPhysicalTransport) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        }
        return true;
    }

    private boolean shouldRetryProxyLaunch(String error, int attempt) {
        if (attempt >= PROXY_START_MAX_ATTEMPTS || TextUtils.isEmpty(error)) {
            return false;
        }
        String normalized = error.toLowerCase(Locale.US);
        return normalized.contains(NETLINK_PERMISSION_DENIED);
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return null;
    }

    private void startPolling() {
        sampleStatisticsNow();
        syncTunnelNetworkLocks();
        lastUnderlyingNetworkFingerprint = captureUnderlyingNetworkFingerprint();
        lastUnderlyingNetworkUsable = !TextUtils.isEmpty(lastUnderlyingNetworkFingerprint);
        requestConnectivityProbe(true);
        statsExecutor = Executors.newSingleThreadScheduledExecutor();
        statsExecutor.scheduleAtFixedRate(() -> {
            if (!sRunning) {
                return;
            }
            sampleStatisticsNow();
        }, 1L, 1L, TimeUnit.SECONDS);

        statsExecutor.scheduleAtFixedRate(() -> {
            if (!sRunning) {
                return;
            }
            syncTunnelNetworkLocks();
        }, 2L, 5L, TimeUnit.SECONDS);

        statsExecutor.scheduleAtFixedRate(() -> {
            if (!sRunning) {
                return;
            }
            requestPublicIpRefresh(false);
        }, 2L, 45L, TimeUnit.SECONDS);

        statsExecutor.scheduleAtFixedRate(() -> {
            if (!sRunning || !rootModeActive) {
                return;
            }
            requestRootTetherRoutingSync(null);
        }, 2L, ROOT_TETHER_SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);

        statsExecutor.scheduleAtFixedRate(() -> {
            if (!sRunning) {
                return;
            }
            requestConnectivityProbe(false);
        }, 4L, CONNECTIVITY_PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        statsExecutor.scheduleAtFixedRate(() -> {
            if (!sRunning) {
                return;
            }
            runRuntimeSupervisorTick();
        }, 5L, RUNTIME_SUPERVISOR_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void sampleStatisticsNow() {
        if (!sRunning) {
            return;
        }
        if (activeBackendType == BackendType.XRAY) {
            InterfaceTrafficSnapshot snapshot = readActiveVpnTrafficSnapshot();
            if (snapshot.isZero()) {
                snapshot = readUidTrafficSnapshot();
            }
            applyTrafficStatsSnapshot(snapshot.rxBytes, snapshot.txBytes);
            return;
        }
        if (activeBackendType == BackendType.AMNEZIAWG) {
            sampleAwgStatisticsNow();
            return;
        }
        sampleWireGuardStatisticsNow();
    }

    private void applyStatisticsSnapshot(@Nullable Statistics statistics) {
        if (statistics == null) {
            return;
        }
        applyTrafficStatsSnapshot(statistics.totalRx(), statistics.totalTx());
    }

    private void applyAwgStatisticsSnapshot(@Nullable org.amnezia.awg.backend.Statistics statistics) {
        if (statistics == null) {
            return;
        }
        applyTrafficStatsSnapshot(statistics.totalRx(), statistics.totalTx());
    }

    private void sampleWireGuardStatisticsNow() {
        synchronized (vpnBackendLock) {
            if (backend == null || currentTunnel == null) {
                return;
            }
            try {
                applyStatisticsSnapshot(backend.getStatistics(currentTunnel));
            } catch (Exception ignored) {
            }
        }
    }

    private void sampleAwgStatisticsNow() {
        synchronized (vpnBackendLock) {
            if (awgBackend == null || awgTunnel == null) {
                return;
            }
            try {
                applyAwgStatisticsSnapshot(awgBackend.getStatistics(awgTunnel));
            } catch (Exception ignored) {
            }
        }
    }

    private void shutdownVpnBackendsLocked() {
        synchronized (vpnBackendLock) {
            if (awgBackend != null && awgTunnel != null && awgConfig != null) {
                try {
                    awgBackend.setState(awgTunnel, org.amnezia.awg.backend.Tunnel.State.DOWN, awgConfig);
                } catch (Exception ignored) {
                }
            }
            if (backend != null && currentTunnel != null && currentConfig != null) {
                try {
                    backend.setState(currentTunnel, Tunnel.State.DOWN, currentConfig);
                } catch (Exception ignored) {
                }
            }
            clearVpnBackendReferences();
        }
    }

    private void clearVpnBackendReferences() {
        synchronized (vpnBackendLock) {
            currentConfig = null;
            currentTunnel = null;
            backend = null;
            awgConfig = null;
            awgTunnel = null;
            awgBackend = null;
        }
    }

    private void applyTrafficStatsSnapshot(long rxTotal, long txTotal) {
        if (rxTotal < 0L) {
            rxTotal = 0L;
        }
        if (txTotal < 0L) {
            txTotal = 0L;
        }
        if (activeBackendType == BackendType.XRAY) {
            if (xrayTrafficBaseRx < 0L || xrayTrafficBaseTx < 0L) {
                xrayTrafficBaseRx = rxTotal;
                xrayTrafficBaseTx = txTotal;
            }
            rxTotal = Math.max(0L, rxTotal - xrayTrafficBaseRx);
            txTotal = Math.max(0L, txTotal - xrayTrafficBaseTx);
        }
        if (lastRxSample >= 0L) {
            sRxBytesPerSecond = Math.max(0L, rxTotal - lastRxSample);
        } else {
            sRxBytesPerSecond = 0L;
        }
        if (lastTxSample >= 0L) {
            sTxBytesPerSecond = Math.max(0L, txTotal - lastTxSample);
        } else {
            sTxBytesPerSecond = 0L;
        }

        lastRxSample = rxTotal;
        lastTxSample = txTotal;
        sRxBytes = rxTotal;
        sTxBytes = txTotal;
    }

    private InterfaceTrafficSnapshot readActiveVpnTrafficSnapshot() {
        String interfaceName = resolveActiveVpnInterfaceName();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/dev"))) {
            java.util.LinkedHashMap<String, InterfaceTrafficSnapshot> snapshots = new java.util.LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String candidate = line.substring(0, separator).trim();
                String[] columns = line.substring(separator + 1).trim().split("\\s+");
                if (columns.length < 9) {
                    continue;
                }
                long rxBytes = parseLong(columns[0]);
                long txBytes = parseLong(columns[8]);
                snapshots.put(candidate, new InterfaceTrafficSnapshot(rxBytes, txBytes));
            }
            if (snapshots.isEmpty()) {
                return InterfaceTrafficSnapshot.ZERO;
            }

            if (!TextUtils.isEmpty(interfaceName)) {
                InterfaceTrafficSnapshot exact = snapshots.get(interfaceName);
                if (exact != null) {
                    return exact;
                }
            }

            InterfaceTrafficSnapshot tun0 = snapshots.get("tun0");
            if (tun0 != null) {
                return tun0;
            }

            String selectedTunName = null;
            InterfaceTrafficSnapshot selectedTun = null;
            long selectedTraffic = -1L;
            for (java.util.Map.Entry<String, InterfaceTrafficSnapshot> entry : snapshots.entrySet()) {
                String candidate = entry.getKey();
                if (!candidate.startsWith("tun") || TextUtils.equals(candidate, "tunl0")) {
                    continue;
                }
                InterfaceTrafficSnapshot snapshot = entry.getValue();
                long totalTraffic = snapshot.rxBytes + snapshot.txBytes;
                if (selectedTun == null || totalTraffic > selectedTraffic) {
                    selectedTunName = candidate;
                    selectedTun = snapshot;
                    selectedTraffic = totalTraffic;
                }
            }
            if (selectedTun != null) {
                return selectedTun;
            }

            if (!TextUtils.isEmpty(interfaceName) && interfaceName.startsWith("wingsv-")) {
                for (java.util.Map.Entry<String, InterfaceTrafficSnapshot> entry : snapshots.entrySet()) {
                    String candidate = entry.getKey();
                    if (candidate.startsWith("tun") && !TextUtils.equals(candidate, "tunl0")) {
                        return entry.getValue();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return InterfaceTrafficSnapshot.ZERO;
    }

    private InterfaceTrafficSnapshot readUidTrafficSnapshot() {
        try {
            int uid = getApplicationInfo().uid;
            long rxBytes = TrafficStats.getUidRxBytes(uid);
            long txBytes = TrafficStats.getUidTxBytes(uid);
            if (rxBytes == TrafficStats.UNSUPPORTED || txBytes == TrafficStats.UNSUPPORTED) {
                return InterfaceTrafficSnapshot.ZERO;
            }
            return new InterfaceTrafficSnapshot(rxBytes, txBytes);
        } catch (Exception ignored) {
            return InterfaceTrafficSnapshot.ZERO;
        }
    }

    @Nullable
    private String resolveActiveVpnInterfaceName() {
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return null;
        }
        try {
            Network[] networks = connectivityManager.getAllNetworks();
            if (networks == null) {
                return null;
            }
            for (Network network : networks) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    continue;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    int ownerUid = capabilities.getOwnerUid();
                    if (ownerUid != android.os.Process.INVALID_UID && ownerUid != getApplicationInfo().uid) {
                        continue;
                    }
                }
                LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
                if (linkProperties == null) {
                    continue;
                }
                String interfaceName = linkProperties.getInterfaceName();
                if (!TextUtils.isEmpty(interfaceName)) {
                    return interfaceName;
                }
            }
        } catch (Exception ignored) {
        }
        return "tun0";
    }

    private static long parseLong(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void requestPublicIpRefresh(boolean forceRestart) {
        if (stopping || sServiceState == ServiceState.STOPPED) {
            return;
        }
        if (publicIpRequest != null) {
            if (!forceRestart) {
                return;
            }
            cancelPublicIpRefresh();
        }

        sPublicIpRefreshInProgress = true;
        final int requestGeneration = ++publicIpRequestGeneration;
        publicIpRequest = PublicIpFetcher.fetchAsyncCancelable(
                getApplicationContext(),
                shouldPreferVpnForPublicIp(),
                result -> {
            if (requestGeneration != publicIpRequestGeneration) {
                return;
            }
            publicIpRequest = null;
            sPublicIpRefreshInProgress = false;
            applyPublicIpInfo(result);
        });
    }

    private boolean shouldPreferVpnForPublicIp() {
        return activeBackendType == BackendType.XRAY || !rootModeActive;
    }

    private void cancelPublicIpRefresh() {
        PublicIpFetcher.Request activeRequest = publicIpRequest;
        if (activeRequest != null) {
            activeRequest.cancel();
            publicIpRequest = null;
        }
        sPublicIpRefreshInProgress = false;
    }

    private void requestConnectivityProbe(boolean force) {
        if (stopping || sServiceState != ServiceState.RUNNING) {
            return;
        }
        if (!force && hasRecentConnectivityProbeSuccess()) {
            return;
        }
        if (!connectivityProbeInProgress.compareAndSet(false, true)) {
            return;
        }
        Thread probeThread = new Thread(() -> {
            try {
                if (runConnectivityProbeNow()) {
                    recordConnectivityProbeSuccess();
                }
            } finally {
                connectivityProbeInProgress.set(false);
            }
        }, "wingsv-connectivity-probe");
        probeThread.setDaemon(true);
        probeThread.start();
    }

    private boolean runConnectivityProbeNow() {
        return runConnectivityProbeNow(CONNECTIVITY_PROBE_TIMEOUT_MS);
    }

    private boolean runConnectivityProbeNow(long timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(CONNECTIVITY_PROBE_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            int timeout = (int) Math.max(500L, timeoutMs);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestProperty("Connection", "close");
            int responseCode = connection.getResponseCode();
            return responseCode > 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void recordConnectivityProbeSuccess() {
        sLastConnectivityProbeSuccessAtElapsedMs = SystemClock.elapsedRealtime();
        sConnectivityProbeSuccessTtlMs = ThreadLocalRandom.current().nextLong(
                CONNECTIVITY_PROBE_SUCCESS_TTL_MIN_MS,
                CONNECTIVITY_PROBE_SUCCESS_TTL_MAX_MS + 1L
        );
        if (isTransientNoticeCandidateError(sVisibleErrorNotice)) {
            sVisibleErrorNotice = null;
            sTransientErrorNoticeCount = 0;
            sTransientErrorNoticeStartedAtMs = 0L;
        }
    }

    private void runRuntimeSupervisorTick() {
        if (stopping || sServiceState != ServiceState.RUNNING) {
            return;
        }
        syncTunnelNetworkLocks();
        String currentUnderlyingFingerprint = captureUnderlyingNetworkFingerprint();
        lastUnderlyingNetworkUsable = !TextUtils.isEmpty(currentUnderlyingFingerprint);
        if (!TextUtils.isEmpty(lastUnderlyingNetworkFingerprint)
                && !TextUtils.isEmpty(currentUnderlyingFingerprint)
                && !TextUtils.equals(lastUnderlyingNetworkFingerprint, currentUnderlyingFingerprint)) {
            scheduleRuntimeReconnect(
                    "Underlying network changed from "
                            + lastUnderlyingNetworkFingerprint
                            + " to "
                            + currentUnderlyingFingerprint,
                    RUNTIME_RECONNECT_DELAY_MS
            );
            lastUnderlyingNetworkFingerprint = currentUnderlyingFingerprint;
            return;
        }
        if (!TextUtils.isEmpty(currentUnderlyingFingerprint)) {
            lastUnderlyingNetworkFingerprint = currentUnderlyingFingerprint;
        }
        if (activeBackendType == BackendType.XRAY) {
            if (!XrayBridge.isRunning()) {
                scheduleRuntimeReconnect("Xray core stopped unexpectedly", RUNTIME_RECONNECT_DELAY_MS);
                return;
            }
            if (!XrayVpnService.hasActiveTunnel()) {
                scheduleRuntimeReconnect("Xray VPN service lost active tunnel", RUNTIME_RECONNECT_DELAY_MS);
                return;
            }
            if (!XrayVpnService.isHeartbeatFresh(XRAY_HEARTBEAT_TIMEOUT_MS)) {
                scheduleRuntimeReconnect("Xray VPN heartbeat timed out", RUNTIME_RECONNECT_DELAY_MS);
            }
            return;
        }

        if (rootModeActive) {
            long proxyPid = readRootProxyPid();
            if (proxyPid <= 0L || !isExpectedRootProxyProcess(proxyPid)) {
                scheduleRuntimeReconnect("Root vk-turn-proxy process disappeared", RUNTIME_RECONNECT_DELAY_MS);
                return;
            }
        } else if (proxyProcess == null || !proxyProcess.isAlive()) {
            scheduleRuntimeReconnect("vk-turn-proxy process disappeared", RUNTIME_RECONNECT_DELAY_MS);
            return;
        }

        if (activeBackendType == BackendType.AMNEZIAWG) {
            if (awgBackend == null || awgTunnel == null || awgConfig == null) {
                scheduleRuntimeReconnect("AmneziaWG runtime lost tunnel state", RUNTIME_RECONNECT_DELAY_MS);
            }
            return;
        }

        if (backend == null || currentTunnel == null) {
            scheduleRuntimeReconnect("WireGuard runtime lost tunnel state", RUNTIME_RECONNECT_DELAY_MS);
        }
    }

    private void scheduleRuntimeReconnect(String reason, long delayMs) {
        final int scheduledGeneration = runtimeGeneration.get();
        if (sServiceState == ServiceState.STOPPED || !runtimeReconnectQueued.compareAndSet(false, true)) {
            return;
        }
        appendRuntimeLogLine("Scheduling runtime reconnect: " + firstNonEmpty(reason, "unknown reason"));
        workExecutor.execute(() -> {
            try {
                if (delayMs > 0L) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (scheduledGeneration != runtimeGeneration.get() || sServiceState == ServiceState.STOPPED) {
                    return;
                }
                resetRuntimeSnapshot();
                clearLastError();
                setServiceState(ServiceState.CONNECTING);
                try {
                    startForeground(SERVICE_NOTIFICATION_ID, buildNotification());
                } catch (Exception ignored) {
                }
                stopWorkInternalForReconnect();
                if (scheduledGeneration != runtimeGeneration.get() || sServiceState == ServiceState.STOPPED) {
                    return;
                }
                stopping = false;
                beginErrorNoticeSession();
                clearLastError();
                setServiceState(ServiceState.CONNECTING);
                try {
                    startForeground(SERVICE_NOTIFICATION_ID, buildNotification());
                } catch (Exception ignored) {
                }
                startWork();
            } finally {
                runtimeReconnectQueued.set(false);
            }
        });
    }

    private NotificationCompat.Builder baseNotificationBuilder() {
        Intent launchIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                100,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = createStopIntent(this);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                101,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(getNotificationStatusRes()))
                .setSmallIcon(R.drawable.ic_power)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(0, getString(R.string.service_off), stopPendingIntent)
                .setOnlyAlertOnce(true);
    }

    private android.app.Notification buildNotification() {
        return baseNotificationBuilder().build();
    }

    private void setServiceState(ServiceState state) {
        sServiceState = state;
        if (state != ServiceState.RUNNING) {
            sRunning = false;
        }
        syncTunnelWakeLock(state != ServiceState.STOPPED);
        syncTunnelNetworkObservers(state != ServiceState.STOPPED);
        syncTunnelNetworkLocks();
        updateNotification();
        QuickSettingsTiles.requestRefresh(getApplicationContext());
    }

    private void syncTunnelNetworkObservers(boolean shouldHold) {
        if (shouldHold) {
            registerPhysicalNetworkCallbackIfNeeded();
            registerScreenStateReceiverIfNeeded();
        } else {
            unregisterPhysicalNetworkCallback();
            unregisterScreenStateReceiver();
        }
    }

    private void syncTunnelWakeLock(boolean shouldHold) {
        if (shouldHold) {
            acquireTunnelWakeLock();
        } else {
            releaseTunnelWakeLock();
        }
    }

    private void acquireTunnelWakeLock() {
        if (tunnelPowerLock != null && tunnelPowerLock.isHeld()) {
            return;
        }
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null) {
            return;
        }
        if (tunnelPowerLock == null) {
            tunnelPowerLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wingsv:tunnel:power");
            tunnelPowerLock.setReferenceCounted(false);
        }
        try {
            tunnelPowerLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseTunnelWakeLock() {
        if (tunnelPowerLock == null || !tunnelPowerLock.isHeld()) {
            return;
        }
        try {
            tunnelPowerLock.release();
        } catch (Exception ignored) {
        }
    }

    private void syncTunnelNetworkLocks() {
        if (sServiceState == ServiceState.STOPPED) {
            releaseTunnelWifiLock();
            return;
        }
        if (isWifiTransportActive()) {
            acquireTunnelWifiLock();
        } else {
            releaseTunnelWifiLock();
        }
    }

    private void acquireTunnelWifiLock() {
        if (tunnelWifiLock != null && tunnelWifiLock.isHeld()) {
            return;
        }
        WifiManager wifiManager = getSystemService(WifiManager.class);
        if (wifiManager == null) {
            return;
        }
        if (tunnelWifiLock == null) {
            tunnelWifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "wingsv:tunnel:wifi"
            );
            tunnelWifiLock.setReferenceCounted(false);
        }
        try {
            tunnelWifiLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseTunnelWifiLock() {
        if (tunnelWifiLock == null || !tunnelWifiLock.isHeld()) {
            return;
        }
        try {
            tunnelWifiLock.release();
        } catch (Exception ignored) {
        }
    }

    private boolean isWifiTransportActive() {
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return false;
        }
        try {
            Network[] networks = connectivityManager.getAllNetworks();
            if (networks == null) {
                return false;
            }
            for (Network network : networks) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    continue;
                }
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void registerPhysicalNetworkCallbackIfNeeded() {
        if (physicalNetworkCallbackRegistered) {
            return;
        }
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return;
        }
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connectivityManager.registerNetworkCallback(request, physicalNetworkCallback);
            physicalNetworkCallbackRegistered = true;
        } catch (Exception error) {
            appendRuntimeLogLine("Failed to register physical network callback: " + error.getMessage());
        }
    }

    private void unregisterPhysicalNetworkCallback() {
        if (!physicalNetworkCallbackRegistered) {
            return;
        }
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            physicalNetworkCallbackRegistered = false;
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(physicalNetworkCallback);
        } catch (Exception ignored) {
        } finally {
            physicalNetworkCallbackRegistered = false;
        }
    }

    private void registerScreenStateReceiverIfNeeded() {
        if (screenStateReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenStateReceiver, filter);
            }
            screenStateReceiverRegistered = true;
        } catch (Exception error) {
            appendRuntimeLogLine("Failed to register wake fast-path receiver: " + error.getMessage());
        }
    }

    private void unregisterScreenStateReceiver() {
        if (!screenStateReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (Exception ignored) {
        } finally {
            screenStateReceiverRegistered = false;
            wakeFastPathCheckQueued.set(false);
        }
    }

    private void handleWakeFastPathEvent(@Nullable String action) {
        if (stopping || sServiceState != ServiceState.RUNNING) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastWakeFastPathAtElapsedMs < WAKE_FAST_PATH_EVENT_COOLDOWN_MS) {
            return;
        }
        lastWakeFastPathAtElapsedMs = now;
        syncTunnelNetworkLocks();
        requestConnectivityProbe(true);
        if (statsExecutor == null || !wakeFastPathCheckQueued.compareAndSet(false, true)) {
            return;
        }
        final int scheduledGeneration = runtimeGeneration.get();
        final String trigger = firstNonEmpty(action, "wake");
        appendRuntimeLogLine("Wake fast-path probe armed by " + trigger);
        statsExecutor.schedule(
                () -> runWakeFastPathHealthCheck(trigger, scheduledGeneration),
                WAKE_FAST_PATH_INITIAL_DELAY_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void runWakeFastPathHealthCheck(@Nullable String trigger, int scheduledGeneration) {
        try {
            if (scheduledGeneration != runtimeGeneration.get() || stopping || sServiceState != ServiceState.RUNNING) {
                return;
            }
            String safeTrigger = firstNonEmpty(trigger, "wake");
            if (runConnectivityProbeNow(WAKE_FAST_PATH_PROBE_TIMEOUT_MS)) {
                appendRuntimeLogLine("Wake fast-path probe succeeded after " + safeTrigger);
                recordConnectivityProbeSuccess();
                return;
            }
            appendRuntimeLogLine("Wake fast-path probe failed after " + safeTrigger + ", retrying");
            try {
                Thread.sleep(WAKE_FAST_PATH_RETRY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (scheduledGeneration != runtimeGeneration.get() || stopping || sServiceState != ServiceState.RUNNING) {
                return;
            }
            if (runConnectivityProbeNow(WAKE_FAST_PATH_PROBE_TIMEOUT_MS)) {
                appendRuntimeLogLine("Wake fast-path retry probe succeeded after " + safeTrigger);
                recordConnectivityProbeSuccess();
                return;
            }
            appendRuntimeLogLine("Wake fast-path probe failed twice after " + safeTrigger);
            String currentUnderlyingFingerprint = captureUnderlyingNetworkFingerprint();
            lastUnderlyingNetworkUsable = !TextUtils.isEmpty(currentUnderlyingFingerprint);
            if (!TextUtils.isEmpty(currentUnderlyingFingerprint)) {
                lastUnderlyingNetworkFingerprint = currentUnderlyingFingerprint;
            }
            scheduleRuntimeReconnect(
                    "Wake fast-path health probe failed after " + safeTrigger,
                    0L
            );
        } finally {
            wakeFastPathCheckQueued.set(false);
        }
    }

    private void handleUnderlyingNetworkEvent(@Nullable String event, @Nullable Network network) {
        String safeEvent = firstNonEmpty(event, "changed");
        workExecutor.execute(() -> processUnderlyingNetworkEvent(safeEvent, network));
    }

    private void processUnderlyingNetworkEvent(@Nullable String event, @Nullable Network network) {
        if (sServiceState == ServiceState.STOPPED) {
            return;
        }
        syncTunnelNetworkLocks();
        String previousFingerprint = lastUnderlyingNetworkFingerprint;
        Boolean previousUsable = lastUnderlyingNetworkUsable;
        String currentFingerprint = captureUnderlyingNetworkFingerprint();
        boolean currentUsable = !TextUtils.isEmpty(currentFingerprint);
        lastUnderlyingNetworkUsable = currentUsable;

        if (TextUtils.isEmpty(previousFingerprint)) {
            lastUnderlyingNetworkFingerprint = currentFingerprint;
            return;
        }
        if (sServiceState != ServiceState.RUNNING) {
            if (!TextUtils.isEmpty(currentFingerprint)) {
                lastUnderlyingNetworkFingerprint = currentFingerprint;
            }
            return;
        }

        String eventTag = firstNonEmpty(event, "changed");
        String networkTag = network != null ? network.toString() : "unknown";
        if (!TextUtils.isEmpty(currentFingerprint)
                && !TextUtils.equals(previousFingerprint, currentFingerprint)) {
            appendRuntimeLogLine(
                    "Underlying network changed on " + eventTag
                            + " (" + networkTag + "): "
                            + previousFingerprint + " -> " + currentFingerprint
            );
            lastUnderlyingNetworkFingerprint = currentFingerprint;
            scheduleRuntimeReconnect(
                    "Underlying network changed from "
                            + previousFingerprint
                            + " to "
                            + currentFingerprint,
                    UNDERLYING_NETWORK_RECONNECT_DELAY_MS
            );
            return;
        }

        if (Boolean.FALSE.equals(previousUsable) && currentUsable) {
            appendRuntimeLogLine(
                    "Underlying network became usable again on " + eventTag
                            + " (" + networkTag + "): "
                            + currentFingerprint
            );
            lastUnderlyingNetworkFingerprint = currentFingerprint;
            scheduleRuntimeReconnect(
                    "Underlying network resumed after temporary loss",
                    UNDERLYING_NETWORK_RECONNECT_DELAY_MS
            );
            return;
        }

        if (!currentUsable && !Boolean.FALSE.equals(previousUsable)) {
            appendRuntimeLogLine("Underlying physical network became unavailable on " + eventTag + " (" + networkTag + ")");
            return;
        }

        if (!TextUtils.isEmpty(currentFingerprint)) {
            lastUnderlyingNetworkFingerprint = currentFingerprint;
        }
    }

    @Nullable
    private String captureUnderlyingNetworkFingerprint() {
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return null;
        }
        try {
            Network[] networks = connectivityManager.getAllNetworks();
            if (networks == null) {
                return null;
            }
            for (Network network : networks) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !isUsablePhysicalNetwork(capabilities)) {
                    continue;
                }
                String transport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        ? "wifi"
                        : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        ? "cellular"
                        : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        ? "ethernet"
                        : "other";
                LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
                String interfaceName = linkProperties != null ? linkProperties.getInterfaceName() : "";
                return transport + ":" + firstNonEmpty(interfaceName, "unknown") + "@" + network;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void updateNotification() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        try {
            if (sServiceState == ServiceState.STOPPED) {
                notificationManager.cancel(SERVICE_NOTIFICATION_ID);
            } else {
                notificationManager.notify(SERVICE_NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception ignored) {
        }
    }

    private int getNotificationStatusRes() {
        if (sServiceState == ServiceState.CONNECTING) {
            return R.string.service_connecting;
        }
        if (sServiceState == ServiceState.RUNNING) {
            return R.string.service_on;
        }
        return R.string.service_off;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WINGS V",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationChannel captchaChannel = new NotificationChannel(
                CAPTCHA_NOTIFICATION_CHANNEL_ID,
                "WINGS V Captcha",
                NotificationManager.IMPORTANCE_HIGH
        );
        captchaChannel.setDescription("Captcha prompts for background TURN identity refresh");
        captchaChannel.enableVibration(true);
        captchaChannel.setVibrationPattern(new long[]{0L, 180L, 80L, 220L});
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
            notificationManager.createNotificationChannel(captchaChannel);
        }
    }

    private static final class RootRoutingState {
        private final List<String> ipv4Routes;
        private final List<String> ipv6Routes;
        private final Set<Integer> bypassUids;
        private final Set<String> tetherInterfaces = new LinkedHashSet<>();
        private final List<Integer> rulePriorities = new ArrayList<>();
        private String tetherTableLookup;
        private String tetherMasqueradeMode;
        private boolean tetherDisableIpv6;
        private boolean tetherUseFallbackUpstream;
        private int tetherDnsUdpPort;
        private int tetherDnsTcpPort;

        private RootRoutingState(List<String> ipv4Routes, List<String> ipv6Routes, Set<Integer> bypassUids) {
            this.ipv4Routes = ipv4Routes != null ? ipv4Routes : new ArrayList<>();
            this.ipv6Routes = ipv6Routes != null ? ipv6Routes : new ArrayList<>();
            this.bypassUids = bypassUids != null ? bypassUids : new LinkedHashSet<>();
        }
    }

    private static final class InterfaceTrafficSnapshot {
        private static final InterfaceTrafficSnapshot ZERO = new InterfaceTrafficSnapshot(0L, 0L);

        private final long rxBytes;
        private final long txBytes;

        private InterfaceTrafficSnapshot(long rxBytes, long txBytes) {
            this.rxBytes = Math.max(rxBytes, 0L);
            this.txBytes = Math.max(txBytes, 0L);
        }

        private boolean isZero() {
            return rxBytes <= 0L && txBytes <= 0L;
        }
    }

    private static final class LocalTunnel implements Tunnel {
        private final String name;

        private LocalTunnel(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void onStateChange(State newState) {
        }
    }

    private static final class LocalAwgTunnel implements org.amnezia.awg.backend.Tunnel {
        private final String name;

        private LocalAwgTunnel(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void onStateChange(org.amnezia.awg.backend.Tunnel.State newState) {
        }
    }
}
