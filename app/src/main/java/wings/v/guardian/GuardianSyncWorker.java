package wings.v.guardian;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import wings.v.core.AppPrefs;

/**
 * Lightweight Guardian sync used by the PERIODIC mode. Connects, exchanges
 * frames for ~30 seconds, then disconnects — no permanent foreground service,
 * no persistent notification.
 */
public final class GuardianSyncWorker extends Worker {

    private static final long QUICK_SYNC_BUDGET_MS = 30_000L;

    public GuardianSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        if (!AppPrefs.isGuardianEnabled(app) || !AppPrefs.isGuardianConfigured(app)) {
            return Result.success();
        }
        if (!AppPrefs.GUARDIAN_SYNC_MODE_PERIODIC.equals(AppPrefs.getGuardianSyncMode(app))) {
            return Result.success();
        }
        // The in-app foreground client owns a stable connection while the app is
        // open. Opening a second socket here would make the panel replace one with
        // the other (same client id) and then drop it after the 30s budget - the
        // churn the user sees in PERIODIC mode while inside the app. Skip entirely.
        if (GuardianForegroundClient.isActive()) {
            return Result.success();
        }
        CountDownLatch done = new CountDownLatch(1);
        // The listener fires command callbacks before doWork() finishes wiring
        // up the client reference, so route responses through a holder that the
        // client assignment below populates. Without a live responder here the
        // worker only ever shipped the ClientHello and silently dropped every
        // command ack / REPORT_NOW snapshot in PERIODIC background sync.
        AtomicReference<GuardianClient> clientRef = new AtomicReference<>();
        GuardianCommandHandler.Responder responder = frame -> {
            GuardianClient live = clientRef.get();
            if (live != null) {
                live.sendFrame(frame);
            }
        };
        GuardianClient client = new GuardianClient(
            app,
            new GuardianClient.Listener() {
                @Override
                public void onConnected(String host) {
                    GuardianStateBroadcast.send(app, true, host);
                }

                @Override
                public void onDisconnected() {
                    GuardianStateBroadcast.send(app, false, "");
                }

                @Override
                public void onCommand(wings.v.proto.GuardianProto.Command command) {
                    GuardianCommandHandler.handle(app, command, responder);
                }

                @Override
                public void onConfigPush(wings.v.proto.GuardianProto.ConfigPush push) {
                    GuardianCommandHandler.applyConfigPush(app, push);
                }

                @Override
                public void onLogControl(wings.v.proto.GuardianProto.LogControl control) {
                    AppPrefs.setGuardianLogControl(
                        app,
                        control.getRuntimeEnabled(),
                        control.getProxyEnabled(),
                        control.getXrayEnabled()
                    );
                }

                @Override
                public void requestStateReport() {}
            }
        );
        clientRef.set(client);
        client.start();
        try {
            long deadline = System.currentTimeMillis() + QUICK_SYNC_BUDGET_MS;
            // Poll the budget instead of one long await so we release the socket
            // promptly if the work is cancelled or the app comes to the foreground
            // (its foreground client takes over), avoiding two live connections.
            while (System.currentTimeMillis() < deadline && !isStopped() && !GuardianForegroundClient.isActive()) {
                if (done.await(1_000L, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            client.stop();
        }
        return Result.success();
    }
}
