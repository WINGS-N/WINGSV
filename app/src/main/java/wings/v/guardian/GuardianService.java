package wings.v.guardian;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.proto.GuardianProto;
import wings.v.service.ProxyTunnelService;

/**
 * Foreground service that owns the long-lived Guardian (panel) connection.
 *
 * Runs independently of ProxyTunnelService — the panel can start/stop the
 * tunnel via Guardian commands. Restarted from boot when the user's preference
 * is on.
 */
public final class GuardianService extends Service implements GuardianClient.Listener {

    private static final String TAG = "GuardianService";
    private static final String CHANNEL_ID = "wings.v.guardian";
    private static final int NOTIFICATION_ID = 0xC110;

    private static volatile boolean serviceRunning;
    private static volatile boolean connected;

    public static boolean isServiceRunning() {
        return serviceRunning;
    }

    public static boolean isConnected() {
        return connected;
    }

    public static Intent startIntent(Context context) {
        return new Intent(context, GuardianService.class).setAction("wings.v.guardian.START");
    }

    public static Intent stopIntent(Context context) {
        return new Intent(context, GuardianService.class).setAction("wings.v.guardian.STOP");
    }

    private GuardianClient client;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private final Handler logPumpHandler = new Handler(Looper.getMainLooper());
    private long runtimeLogOffset;
    private long proxyLogOffset;
    private long xrayErrorOffset;
    private long xrayAccessOffset;
    private long runtimeChunkSeq;
    private long proxyChunkSeq;
    private long xrayChunkSeq;
    private static final long LOG_PUMP_INTERVAL_MS = 1_000L;
    private static final int LOG_MAX_BYTES_PER_TICK = 16 * 1024;
    private final Runnable logPump = new Runnable() {
        @Override
        public void run() {
            pumpLogs();
            logPumpHandler.postDelayed(this, LOG_PUMP_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // CRITICAL: any path triggered by startForegroundService() MUST call
        // startForeground() within ~5 seconds, otherwise the OS throws
        // ForegroundServiceDidNotStartInTimeException and the app crashes.
        // We always promote ourselves to foreground first, then evaluate
        // whether we actually want to keep running.
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.guardian_notification_connecting)));

        String action = intent == null ? "" : (intent.getAction() == null ? "" : intent.getAction());
        if ("wings.v.guardian.STOP".equals(action)) {
            tearDown();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!AppPrefs.isGuardianEnabled(this) || !AppPrefs.isGuardianConfigured(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        serviceRunning = true;
        if (client == null) {
            client = new GuardianClient(getApplicationContext(), this);
            client.start();
            registerPrefsListener();
            // Anchor offsets at current EOF so we don't replay an entire
            // historical log on first connect.
            runtimeLogOffset = new java.io.File(getFilesDir(), "wingsv_runtime.log").length();
            proxyLogOffset = new java.io.File(getFilesDir(), "wingsv_proxy.log").length();
            java.io.File xrayLogDir = new java.io.File(getFilesDir(), "xray/log");
            xrayErrorOffset = new java.io.File(xrayLogDir, "error.log").length();
            xrayAccessOffset = new java.io.File(xrayLogDir, "access.log").length();
            logPumpHandler.postDelayed(logPump, LOG_PUMP_INTERVAL_MS);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        tearDown();
        super.onDestroy();
    }

    private void tearDown() {
        logPumpHandler.removeCallbacks(logPump);
        if (prefListener != null) {
            try {
                AppPrefs.defaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(prefListener);
            } catch (RuntimeException ignored) {}
            prefListener = null;
        }
        if (client != null) {
            client.stop();
            client = null;
        }
        serviceRunning = false;
        connected = false;
        GuardianStateBroadcast.send(this, false, "");
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.guardian_notification_channel),
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.guardian_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void registerPrefsListener() {
        prefListener = (sharedPreferences, key) -> {
            if (key == null || client == null) {
                return;
            }
            // Any settings change from UI or import = re-snapshot to panel.
            client.sendFrame(buildStateReport());
        };
        try {
            AppPrefs.defaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(prefListener);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onConnected(String host) {
        connected = true;
        updateNotification(getString(R.string.guardian_notification_connected, host));
        GuardianStateBroadcast.send(this, true, host);
        ProxyTunnelService.writeRuntimeLogLine("[guardian] connected to " + host);
        if (client != null) {
            client.sendFrame(buildStateReport());
        }
        sendInstalledAppsAsync();
    }

    private void sendInstalledAppsAsync() {
        Context appContext = getApplicationContext();
        new Thread(
            () -> {
                try {
                    GuardianProto.InstalledApps inventory = wings.v.guardian.InstalledAppsBuilder.build(appContext);
                    if (client != null && inventory != null) {
                        client.sendFrame(GuardianProto.Frame.newBuilder().setInstalledApps(inventory).build());
                    }
                } catch (Throwable error) {
                    ProxyTunnelService.writeRuntimeLogLine(
                        "[guardian] installed apps inventory failed: " + error.getMessage()
                    );
                }
            },
            "guardian-apps-inventory"
        )
            .start();
    }

    @Override
    public void onDisconnected() {
        if (connected) {
            ProxyTunnelService.writeRuntimeLogLine("[guardian] disconnected");
        }
        connected = false;
        updateNotification(getString(R.string.guardian_notification_disconnected));
        GuardianStateBroadcast.send(this, false, "");
    }

    @Override
    public void onCommand(GuardianProto.Command command) {
        Log.i(TAG, "command=" + command.getType().name() + " id=" + command.getId());
        ProxyTunnelService.writeRuntimeLogLine(
            "[guardian] command=" + command.getType().name() + " id=" + command.getId()
        );
        Context ctx = getApplicationContext();
        boolean ok = true;
        String error = "";
        try {
            switch (command.getType()) {
                case COMMAND_TYPE_START_TUNNEL:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(ProxyTunnelService.createStartIntent(ctx));
                    } else {
                        ctx.startService(ProxyTunnelService.createStartIntent(ctx));
                    }
                    break;
                case COMMAND_TYPE_STOP_TUNNEL:
                    ProxyTunnelService.requestStop(ctx);
                    break;
                case COMMAND_TYPE_RECONNECT:
                    ProxyTunnelService.requestReconnect(ctx, "guardian-command");
                    break;
                case COMMAND_TYPE_REPORT_NOW:
                    if (client != null) {
                        client.sendFrame(buildStateReport());
                    }
                    break;
                case COMMAND_TYPE_REFRESH_SUBSCRIPTION:
                case COMMAND_TYPE_REFRESH_ALL_SUBSCRIPTIONS:
                    runSubscriptionRefresh(ctx);
                    break;
                case COMMAND_TYPE_REFRESH_INSTALLED_APPS:
                    sendInstalledAppsAsync();
                    break;
                case COMMAND_TYPE_GENERATE_VK_LINK:
                    handleGenerateVkLinkCommand(command.getId());
                    return;
                default:
                    ok = false;
                    error = "unknown command";
            }
        } catch (Throwable t) {
            ok = false;
            error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
        if (client != null) {
            client.sendFrame(
                GuardianProto.Frame.newBuilder()
                    .setCommandAck(
                        GuardianProto.CommandAck.newBuilder()
                            .setId(command.getId())
                            .setOk(ok)
                            .setErrorMessage(error == null ? "" : error)
                    )
                    .build()
            );
        }
    }

    @Override
    public void onConfigPush(GuardianProto.ConfigPush push) {
        Log.i(TAG, "config push revision=" + push.getRevision());
        ProxyTunnelService.writeRuntimeLogLine("[guardian] config push revision=" + push.getRevision());
        GuardianCommandHandler.applyConfigPush(getApplicationContext(), push);
        if (client != null) {
            client.sendFrame(buildStateReport());
        }
    }

    @Override
    public void onLogControl(GuardianProto.LogControl control) {
        AppPrefs.setGuardianLogControl(
            this,
            control.getRuntimeEnabled(),
            control.getProxyEnabled(),
            control.getXrayEnabled()
        );
        ProxyTunnelService.writeRuntimeLogLine(
            "[guardian] log-control runtime=" +
                control.getRuntimeEnabled() +
                " proxy=" +
                control.getProxyEnabled() +
                " xray=" +
                control.getXrayEnabled()
        );
    }

    @Override
    public void requestStateReport() {
        if (client != null) {
            client.sendFrame(buildStateReport());
        }
    }

    private void handleGenerateVkLinkCommand(String commandId) {
        Context ctx = getApplicationContext();
        new Thread(
            () -> {
                boolean ok = true;
                String error = "";
                try {
                    if (!wings.v.vk.VkOAuthAuth.isClientConfigured()) {
                        ok = false;
                        error = "VK OAuth client not configured";
                    } else if (!wings.v.vk.VkOAuthAuth.isAuthorized(ctx)) {
                        ok = false;
                        error = "VK OAuth token missing";
                    } else {
                        String joinLink = wings.v.vk.VkCallsApi.generateJoinLink(ctx);
                        java.util.List<String> existing = new java.util.ArrayList<>(AppPrefs.getVkLinks(ctx));
                        if (!existing.contains(joinLink)) {
                            existing.add(joinLink);
                            AppPrefs.setVkLinks(ctx, existing);
                        }
                        ProxyTunnelService.writeRuntimeLogLine("[guardian] generate VK link ok");
                    }
                } catch (Exception err) {
                    ok = false;
                    error = err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
                    ProxyTunnelService.writeRuntimeLogLine("[guardian] generate VK link failed: " + error);
                }
                if (client != null) {
                    client.sendFrame(
                        GuardianProto.Frame.newBuilder()
                            .setCommandAck(
                                GuardianProto.CommandAck.newBuilder()
                                    .setId(commandId)
                                    .setOk(ok)
                                    .setErrorMessage(error == null ? "" : error)
                            )
                            .build()
                    );
                    if (ok) {
                        // Push fresh snapshot so the panel sees the new link
                        // without waiting for the next regular report.
                        client.sendFrame(buildStateReport());
                    }
                }
            },
            "guardian-vk-link-gen"
        )
            .start();
    }

    private void runSubscriptionRefresh(Context ctx) {
        new Thread(
            () -> {
                try {
                    wings.v.core.XraySubscriptionUpdater.refreshAll(ctx);
                    ProxyTunnelService.writeRuntimeLogLine("[guardian] subscription refresh ok");
                    if (client != null) {
                        client.sendFrame(buildStateReport());
                    }
                } catch (Exception error) {
                    ProxyTunnelService.writeRuntimeLogLine(
                        "[guardian] subscription refresh failed: " + error.getMessage()
                    );
                }
            },
            "guardian-sub-refresh"
        )
            .start();
    }

    private void pumpLogs() {
        if (client == null || !connected) {
            return;
        }
        Context ctx = getApplicationContext();
        runtimeLogOffset = drainLogFile(
            new java.io.File(getFilesDir(), "wingsv_runtime.log"),
            runtimeLogOffset,
            AppPrefs.isGuardianLogRuntimeAllowed(ctx),
            "",
            GuardianProto.LogStream.LOG_STREAM_RUNTIME,
            runtimeChunkSeqInc()
        );
        proxyLogOffset = drainLogFile(
            new java.io.File(getFilesDir(), "wingsv_proxy.log"),
            proxyLogOffset,
            AppPrefs.isGuardianLogProxyAllowed(ctx),
            "",
            GuardianProto.LogStream.LOG_STREAM_PROXY,
            proxyChunkSeqInc()
        );
        java.io.File xrayDir = new java.io.File(getFilesDir(), "xray/log");
        boolean xrayAllowed = AppPrefs.isGuardianLogXRayAllowed(ctx);
        xrayErrorOffset = drainLogFile(
            new java.io.File(xrayDir, "error.log"),
            xrayErrorOffset,
            xrayAllowed,
            "[xray:error] ",
            GuardianProto.LogStream.LOG_STREAM_XRAY,
            xrayChunkSeqInc()
        );
        xrayAccessOffset = drainLogFile(
            new java.io.File(xrayDir, "access.log"),
            xrayAccessOffset,
            xrayAllowed,
            "[xray:access] ",
            GuardianProto.LogStream.LOG_STREAM_XRAY,
            xrayChunkSeqInc()
        );
    }

    @FunctionalInterface
    private interface SeqAllocator {
        long next();
    }

    private SeqAllocator runtimeChunkSeqInc() {
        return () -> ++runtimeChunkSeq;
    }

    private SeqAllocator proxyChunkSeqInc() {
        return () -> ++proxyChunkSeq;
    }

    private SeqAllocator xrayChunkSeqInc() {
        return () -> ++xrayChunkSeq;
    }

    /** Returns the new byte offset after draining (rotated files reset to 0). */
    private long drainLogFile(
        java.io.File file,
        long lastOffset,
        boolean allowed,
        String prefix,
        GuardianProto.LogStream stream,
        SeqAllocator seq
    ) {
        if (!file.exists()) {
            return 0L;
        }
        long size = file.length();
        if (size < lastOffset) {
            return size;
        }
        if (size == lastOffset) {
            return lastOffset;
        }
        if (size - lastOffset > LOG_MAX_BYTES_PER_TICK) {
            lastOffset = size - LOG_MAX_BYTES_PER_TICK;
        }
        if (!allowed) {
            return size;
        }
        java.util.List<ProxyTunnelService.RuntimeLogEntry> entries = new java.util.ArrayList<>();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            raf.seek(lastOffset);
            int remaining = (int) Math.min(LOG_MAX_BYTES_PER_TICK, size - lastOffset);
            byte[] buf = new byte[remaining];
            raf.readFully(buf);
            String text = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            for (String line : text.split("\\r?\\n")) {
                if (line.isEmpty()) continue;
                entries.add(new ProxyTunnelService.RuntimeLogEntry(seq.next(), prefix + line));
            }
        } catch (java.io.IOException error) {
            Log.w(TAG, "log read failed for " + file.getName() + ": " + error.getMessage());
            return lastOffset;
        }
        sendLogChunk(stream, entries);
        return size;
    }

    private void sendLogChunk(
        GuardianProto.LogStream stream,
        java.util.List<ProxyTunnelService.RuntimeLogEntry> entries
    ) {
        if (entries.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        GuardianProto.LogChunk.Builder chunk = GuardianProto.LogChunk.newBuilder()
            .setStream(stream)
            .setFirstSeq(entries.get(0).seq);
        for (ProxyTunnelService.RuntimeLogEntry e : entries) {
            chunk.addLines(GuardianProto.LogLine.newBuilder().setTsMs(now).setText(e.text == null ? "" : e.text));
        }
        client.sendFrame(GuardianProto.Frame.newBuilder().setLogChunk(chunk).build());
    }

    private GuardianProto.Frame buildStateReport() {
        Context ctx = getApplicationContext();
        boolean vkAuthorized = wings.v.vk.VkOAuthAuth.isClientConfigured() && wings.v.vk.VkOAuthAuth.isAuthorized(ctx);
        GuardianProto.RuntimeState runtime = GuardianProto.RuntimeState.newBuilder()
            .setTunnelActive(ProxyTunnelService.isActive())
            .setPhase(GuardianProto.TunnelPhase.TUNNEL_PHASE_UNSPECIFIED)
            .setHasRootAccess(wings.v.core.RootUtils.isRootAccessGranted(ctx))
            .setVkOauthAuthorized(vkAuthorized)
            .build();
        GuardianProto.StateReport.Builder report = GuardianProto.StateReport.newBuilder().setRuntime(runtime);
        try {
            wings.v.proto.WingsvProto.Config snapshot = wings.v.core.WingsImportParser.buildGuardianSnapshotProto(
                getApplicationContext()
            );
            // Strip Guardian credentials from the snapshot we send back —
            // they're already known to the panel and including them would only
            // leak the token through StateReport echoes.
            wings.v.proto.WingsvProto.Config sanitised = snapshot.toBuilder().clearGuardian().build();
            report.setSnapshot(sanitised);
        } catch (Exception error) {
            Log.w(TAG, "snapshot build failed: " + error.getMessage());
        }
        return GuardianProto.Frame.newBuilder().setStateReport(report).build();
    }
}
