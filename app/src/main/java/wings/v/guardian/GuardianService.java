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
    private long lastRuntimeLogVersion;
    private long lastProxyLogVersion;
    private static final long LOG_PUMP_INTERVAL_MS = 1_000L;
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
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.guardian_notification_connecting)));
        serviceRunning = true;
        if (client == null) {
            client = new GuardianClient(getApplicationContext(), this);
            client.start();
            registerPrefsListener();
            lastRuntimeLogVersion = ProxyTunnelService.getRuntimeLogVersion();
            lastProxyLogVersion = ProxyTunnelService.getProxyLogVersion();
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
                getSharedPreferences(
                    "wings.v.app_prefs",
                    Context.MODE_PRIVATE
                ).unregisterOnSharedPreferenceChangeListener(prefListener);
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
            getSharedPreferences("wings.v.app_prefs", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(
                prefListener
            );
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onConnected(String host) {
        connected = true;
        updateNotification(getString(R.string.guardian_notification_connected, host));
        GuardianStateBroadcast.send(this, true, host);
        if (client != null) {
            client.sendFrame(buildStateReport());
        }
    }

    @Override
    public void onDisconnected() {
        connected = false;
        updateNotification(getString(R.string.guardian_notification_disconnected));
        GuardianStateBroadcast.send(this, false, "");
    }

    @Override
    public void onCommand(GuardianProto.Command command) {
        Log.i(TAG, "command=" + command.getType().name() + " id=" + command.getId());
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
        if (push.getConfig() != null) {
            try {
                wings.v.core.WingsImportParser.ImportedConfig imported =
                    wings.v.core.WingsImportParser.parseProtoConfig(push.getConfig());
                // The panel is allowed to push every other setting, but never
                // its own credentials — we don't want a runtime ConfigPush to
                // accidentally rebind us to a different panel.
                imported.hasGuardian = false;
                imported.guardianWsUrl = null;
                imported.guardianClientId = null;
                imported.guardianClientToken = null;
                imported.guardianClientName = null;
                wings.v.core.AppPrefs.applyImportedConfig(getApplicationContext(), imported);
            } catch (Exception error) {
                Log.w(TAG, "config push apply failed: " + error.getMessage());
            }
        }
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
    }

    @Override
    public void requestStateReport() {
        if (client != null) {
            client.sendFrame(buildStateReport());
        }
    }

    private void pumpLogs() {
        if (client == null || !connected) {
            return;
        }
        Context ctx = getApplicationContext();
        long currentRuntime = ProxyTunnelService.getRuntimeLogVersion();
        if (AppPrefs.isGuardianLogRuntimeAllowed(ctx) && currentRuntime > lastRuntimeLogVersion) {
            java.util.List<ProxyTunnelService.RuntimeLogEntry> entries =
                ProxyTunnelService.snapshotRuntimeLogLinesSince(lastRuntimeLogVersion);
            sendLogChunk(GuardianProto.LogStream.LOG_STREAM_RUNTIME, entries);
        }
        // Always advance the bookmark, even when disabled, so re-enabling
        // doesn't replay history.
        lastRuntimeLogVersion = currentRuntime;

        long currentProxy = ProxyTunnelService.getProxyLogVersion();
        if (AppPrefs.isGuardianLogProxyAllowed(ctx) && currentProxy > lastProxyLogVersion) {
            java.util.List<ProxyTunnelService.RuntimeLogEntry> entries = ProxyTunnelService.snapshotProxyLogLinesSince(
                lastProxyLogVersion
            );
            sendLogChunk(GuardianProto.LogStream.LOG_STREAM_PROXY, entries);
        }
        lastProxyLogVersion = currentProxy;
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
        GuardianProto.RuntimeState runtime = GuardianProto.RuntimeState.newBuilder()
            .setTunnelActive(ProxyTunnelService.isActive())
            .setPhase(GuardianProto.TunnelPhase.TUNNEL_PHASE_UNSPECIFIED)
            .build();
        GuardianProto.StateReport.Builder report = GuardianProto.StateReport.newBuilder().setRuntime(runtime);
        try {
            wings.v.proto.WingsvProto.Config snapshot = wings.v.core.WingsImportParser.buildAllSettingsProto(
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
