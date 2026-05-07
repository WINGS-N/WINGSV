package wings.v.guardian;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        CountDownLatch done = new CountDownLatch(1);
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
                    GuardianCommandHandler.handle(app, command, null);
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
        client.start();
        try {
            done.await(QUICK_SYNC_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            client.stop();
        }
        return Result.success();
    }
}
