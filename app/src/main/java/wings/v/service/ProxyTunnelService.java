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
import android.net.RouteInfo;
import android.net.TetheringManager;
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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import wings.v.MainActivity;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.ProxySettings;
import wings.v.core.PublicIpFetcher;
import wings.v.core.RootUtils;
import wings.v.core.TetherType;
import wings.v.core.WireGuardConfigFactory;
import wings.v.root.server.RootProcessResult;
import wings.v.vpnhotspot.bridge.VpnHotspotBridge;
import wings.v.vpnhotspot.bridge.sharing.VpnHotspotSharingConfig;

public class ProxyTunnelService extends Service {
    public static final String ACTION_START = "wings.v.action.START";
    public static final String ACTION_STOP = "wings.v.action.STOP";
    public static final String ACTION_REFRESH_IP = "wings.v.action.REFRESH_IP";
    public static final String ACTION_REAPPLY_SHARING = "wings.v.action.REAPPLY_SHARING";
    public static final String ACTION_SYNC_RUNTIME = "wings.v.action.SYNC_RUNTIME";
    public static final String ACTION_RESTORE_SHARING_ON_BOOT = "wings.v.action.RESTORE_SHARING_ON_BOOT";
    private static final String NOTIFICATION_CHANNEL_ID = "wingsv_tunnel";
    private static final long NETWORK_READY_TIMEOUT_MS = 8_000L;
    private static final long NETWORK_READY_POLL_MS = 250L;
    private static final long PROXY_START_GRACE_MS = 1_500L;
    private static final long PROXY_WIREGUARD_DELAY_MS = 3_000L;
    private static final long PROXY_RETRY_DELAY_MS = 1_000L;
    private static final int PROXY_START_MAX_ATTEMPTS = 3;
    private static final int MAX_PROXY_LOG_LINES = 600;
    private static final long ROOT_TETHER_SYNC_INTERVAL_MS = 3_000L;
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
    private static volatile boolean sPublicIpRefreshInProgress;
    private static volatile long sProxyLogVersion;

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
    private Backend backend;
    private Config currentConfig;
    private Tunnel currentTunnel;
    private Process proxyProcess;
    private ProxyProtectBridgeServer protectBridgeServer;
    private String protectSocketName;
    private RootShell rootShell;
    private ToolsInstaller toolsInstaller;
    private RootRoutingState rootRoutingState;
    private boolean rootModeActive;
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
    private WifiManager.WifiLock sharingWifiLock;
    private PowerManager.WakeLock sharingPowerLock;
    private int sharingWifiLockMode = -1;
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

    public static Intent createRefreshIpIntent(Context context) {
        return new Intent(context, ProxyTunnelService.class).setAction(ACTION_REFRESH_IP);
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

    public static void clearProxyLogs() {
        synchronized (PROXY_LOG_LOCK) {
            sProxyLogLines.clear();
            sProxyLogVersion++;
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

    public static void applyPublicIpInfo(@Nullable PublicIpFetcher.IpInfo ipInfo) {
        if (ipInfo == null) {
            return;
        }
        if (!TextUtils.isEmpty(ipInfo.ip)) {
            sPublicIp = ipInfo.ip;
        }
        if (!TextUtils.isEmpty(ipInfo.country)) {
            sPublicCountry = ipInfo.country;
        }
        if (!TextUtils.isEmpty(ipInfo.isp)) {
            sPublicIsp = ipInfo.isp;
        }
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
                    sLastError = null;
                } catch (Exception error) {
                    sLastError = error.getMessage();
                    appendProxyLogLine("Root sharing reapply failed: " + error.getMessage());
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
        if (ACTION_SYNC_RUNTIME.equals(action)) {
            if (!AppPrefs.isRootModeEnabled(getApplicationContext())
                    || !AppPrefs.hasRootRuntimeHint(getApplicationContext())) {
                stopSelf();
                return START_NOT_STICKY;
            }
            setServiceState(ServiceState.CONNECTING);
            startForeground(1, buildNotification());
            recoverExistingRootRuntime();
            return START_STICKY;
        }

        setServiceState(ServiceState.CONNECTING);
        startForeground(1, buildNotification());
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
            if (proxyProcess != null || backend != null || currentTunnel != null) {
                return;
            }

            clearProxyLogs();
            ProxySettings settings = AppPrefs.getSettings(getApplicationContext());
            String validationError = settings.validate();
            if (!TextUtils.isEmpty(validationError)) {
                sLastError = validationError;
                setServiceState(ServiceState.STOPPED);
                stopSelf();
                return;
            }

            try {
                ensureRuntimeStillWanted(generation);
                stopping = false;
                sLastError = null;
                resetRuntimeSnapshot();
                rootModeActive = settings.rootModeEnabled;
                if (rootModeActive) {
                    String rootUnavailableReason = RootUtils.getRootModeUnavailableReason(
                            getApplicationContext(),
                            true
                    );
                    if (!TextUtils.isEmpty(rootUnavailableReason)) {
                        throw new IllegalStateException(rootUnavailableReason);
                    }
                } else {
                    clearPersistedRootRuntimeState();
                }

                if (settings.rootModeEnabled && AppPrefs.hasRootRuntimeHint(getApplicationContext())) {
                    if (recoverExistingRootRuntimeInternal(settings, true, generation)) {
                        return;
                    }
                    ensureRuntimeStillWanted(generation);
                    setServiceState(ServiceState.CONNECTING);
                    startForeground(1, buildNotification());
                }

                ensureRuntimeStillWanted(generation);
                setServiceState(ServiceState.CONNECTING);
                waitForUsablePhysicalNetwork(generation);
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
                backend.setState(currentTunnel, Tunnel.State.UP, currentConfig);
                persistRootRuntimeState(readRootProxyPid());
                if (rootModeActive) {
                    syncRootAppTunnelRouting();
                    registerTetherReceiverIfNeeded();
                    registerTetherEventCallbackIfNeeded();
                    syncRootTetherRouting(null);
                }
                setServiceState(ServiceState.RUNNING);
                sRunning = true;
                requestPublicIpRefresh(true);
                restoreSharingOnBootIfNeeded();

                startPolling();
            } catch (Exception error) {
                if (!stopping) {
                    sLastError = error.getMessage();
                }
                stopWorkInternal();
                stopSelf();
            }
        });
    }

    private void stopWork(boolean removeNotification) {
        invalidateRuntimeOperations();
        workExecutor.execute(() -> {
            stopWorkInternal();
            if (removeNotification) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
    }

    private void abortConnectingNow(boolean removeNotification) {
        invalidateRuntimeOperations();
        Thread abortThread = new Thread(() -> {
            stopWorkInternal();
            if (removeNotification) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "wingsv-connect-abort");
        abortThread.setDaemon(true);
        abortThread.start();
    }

    private void stopWorkInternal() {
        stopping = true;
        setServiceState(ServiceState.STOPPED);
        sRunning = false;
        resetRuntimeSnapshot();
        sPublicIpRefreshInProgress = false;

        cancelPublicIpRefresh();

        if (statsExecutor != null) {
            statsExecutor.shutdownNow();
            statsExecutor = null;
        }

        if (backend != null && currentTunnel != null && currentConfig != null) {
            try {
                backend.setState(currentTunnel, Tunnel.State.DOWN, currentConfig);
            } catch (Exception ignored) {
            }
        }
        forceLinkDownActiveTunnelIfNeeded();

        clearRootTetherRouting();
        clearRootAppTunnelRouting();
        unregisterTetherReceiver();
        unregisterTetherEventCallback();
        releaseSharingWifiLocks();
        clearRootRouting();

        if (proxyProcess != null) {
            proxyProcess.destroy();
            proxyProcess = null;
        }
        killPersistedRootProxyIfNeeded();

        currentConfig = null;
        currentTunnel = null;
        backend = null;
        protectSocketName = null;
        rootModeActive = false;
        activeTunnelName = ROOT_TUNNEL_NAME;
        appliedTetherUpstreamName = null;
        pendingSharingRestoreOnBoot = false;

        closeProtectBridge();
        GoBackendVpnAccess.stopService(getApplicationContext());
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
        setServiceState(ServiceState.STOPPED);
        sRunning = false;
        resetRuntimeSnapshot();
        sPublicIpRefreshInProgress = false;
        cancelPublicIpRefresh();
        if (statsExecutor != null) {
            statsExecutor.shutdownNow();
            statsExecutor = null;
        }
        unregisterTetherReceiver();
        unregisterTetherEventCallback();
        clearRootAppTunnelRouting();
        releaseSharingWifiLocks();
        currentConfig = null;
        currentTunnel = null;
        backend = null;
        protectSocketName = null;
        rootModeActive = false;
        activeTunnelName = ROOT_TUNNEL_NAME;
        appliedTetherUpstreamName = null;
        pendingSharingRestoreOnBoot = false;
        closeProtectBridge();
        GoBackendVpnAccess.stopService(getApplicationContext());
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
                ProxySettings settings = AppPrefs.getSettings(getApplicationContext());
                if (!recoverExistingRootRuntimeInternal(settings, false, generation)) {
                    clearPersistedRootRuntimeState();
                    stopWorkInternal();
                    stopSelf();
                }
            } catch (Exception error) {
                if (!stopping) {
                    sLastError = error.getMessage();
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
        sLastError = null;
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
                sLastError = null;
                attachProxyWaitThread(launchedProcess);
                return launchedAt;
            }

            int exitCode = launchedProcess.exitValue();
            launchError = firstNonEmpty(
                    launchOutputError.get(),
                    sLastError,
                    "Proxy завершился с кодом " + exitCode
            );
            sLastError = launchError;

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
                    if (line.toLowerCase().contains("panic")
                            || line.toLowerCase().contains("failed")
                            || line.toLowerCase().contains("error")) {
                        processError.set(line);
                        sLastError = line;
                    }
                }
            } catch (Exception ignored) {
            }
        }, "wingsv-proxy-output");
        outputReader.setDaemon(true);
        outputReader.start();
    }

    private void attachProxyWaitThread(Process monitoredProcess) {
        Thread waitThread = new Thread(() -> {
            try {
                int exitCode = monitoredProcess.waitFor();
                if (!stopping && exitCode != 0) {
                    if (TextUtils.isEmpty(sLastError)) {
                        sLastError = "Proxy завершился с кодом " + exitCode;
                    }
                    setServiceState(ServiceState.STOPPED);
                    stopWork(true);
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
        if (!rootModeActive || rootShell == null) {
            return;
        }
        String liveTunnelName = !TextUtils.isEmpty(activeTunnelName)
                && RootUtils.isRootInterfaceAlive(getApplicationContext(), activeTunnelName)
                ? activeTunnelName
                : resolveRecoveryTunnelName();
        if (TextUtils.isEmpty(liveTunnelName)) {
            AppPrefs.clearRuntimeUpstreamState(getApplicationContext());
            appendProxyLogLine("Root tether routing skipped: live root tunnel не найден");
            return;
        }
        activeTunnelName = liveTunnelName;
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
            appliedTetherUpstreamName = liveTunnelName;
            String syncMessage = "Root tether routing synced: " + configuredInterfaces + " upstream=" + liveTunnelName;
            appendProxyLogLine(syncMessage);
            Log.i(TAG, syncMessage);
        } catch (Exception error) {
            releaseSharingWifiLocks();
            appendProxyLogLine("Root tether routing sync failed: " + error.getMessage());
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
            appendProxyLogLine("Sharing boot restore skipped: no saved tether types");
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
                appendProxyLogLine("Sharing boot restore started: " + type.commandName);
            } catch (Exception error) {
                if (TextUtils.isEmpty(firstError)) {
                    firstError = error.getMessage();
                }
                appendProxyLogLine("Sharing boot restore failed for "
                        + type.commandName + ": " + error.getMessage());
            }
        }
        if (!TextUtils.isEmpty(firstError)) {
            sLastError = firstError;
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
            appendProxyLogLine("Root firewall setup warning: " + error.getMessage());
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
            appendProxyLogLine("Root app tunnel routing skipped: table для " + activeTunnelName + " не найдена");
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
            appendProxyLogLine("Root app tunnel routing failed: " + error.getMessage());
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
        String upstreamInterface = activeTunnelName;
        if (rootModeActive && !RootUtils.isRootInterfaceAlive(getApplicationContext(), upstreamInterface)) {
            String recoveredTunnelName = resolveRecoveryTunnelName();
            if (!TextUtils.isEmpty(recoveredTunnelName)) {
                upstreamInterface = recoveredTunnelName;
            }
        }
        return new VpnHotspotSharingConfig(
                upstreamInterface,
                AppPrefs.getSharingFallbackUpstreamInterface(getApplicationContext()),
                settings != null ? settings.wgDns : "",
                AppPrefs.getSharingMasqueradeMode(getApplicationContext()),
                AppPrefs.isSharingDhcpWorkaroundEnabled(getApplicationContext()),
                AppPrefs.isSharingDisableIpv6Enabled(getApplicationContext())
        );
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
        appendProxyLogLine("Root tether NAT fallback: iptables nat недоступен, переключаемся на netd");
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
                appendProxyLogLine("Root routing command failed: " + result.primaryMessage());
                return lines;
            }
            if (TextUtils.isEmpty(result.getStdout())) {
                return lines;
            }
            Collections.addAll(lines, result.getStdout().split("\\r?\\n"));
            return lines;
        } catch (Exception error) {
            appendProxyLogLine("Root routing command failed: " + error.getMessage());
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
        return AppPrefs.isRootModeEnabled(appContext) && AppPrefs.hasRootRuntimeHint(appContext);
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
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
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
        statsExecutor = Executors.newSingleThreadScheduledExecutor();
        statsExecutor.scheduleAtFixedRate(() -> {
            if (!sRunning || backend == null || currentTunnel == null) {
                return;
            }
            try {
                applyStatisticsSnapshot(backend.getStatistics(currentTunnel));
            } catch (Exception ignored) {
            }
        }, 1L, 1L, TimeUnit.SECONDS);

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
    }

    private void sampleStatisticsNow() {
        if (!sRunning || backend == null || currentTunnel == null) {
            return;
        }
        try {
            applyStatisticsSnapshot(backend.getStatistics(currentTunnel));
        } catch (Exception ignored) {
        }
    }

    private void applyStatisticsSnapshot(@Nullable Statistics statistics) {
        if (statistics == null) {
            return;
        }
        long rxTotal = statistics.totalRx();
        long txTotal = statistics.totalTx();

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
                !rootModeActive,
                result -> {
            if (requestGeneration != publicIpRequestGeneration) {
                return;
            }
            publicIpRequest = null;
            sPublicIpRefreshInProgress = false;
            applyPublicIpInfo(result);
        });
    }

    private void cancelPublicIpRefresh() {
        PublicIpFetcher.Request activeRequest = publicIpRequest;
        if (activeRequest != null) {
            activeRequest.cancel();
            publicIpRequest = null;
        }
        sPublicIpRefreshInProgress = false;
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
        updateNotification();
    }

    private void updateNotification() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        try {
            if (sServiceState == ServiceState.STOPPED) {
                notificationManager.cancel(1);
            } else {
                notificationManager.notify(1, buildNotification());
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
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
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
}
