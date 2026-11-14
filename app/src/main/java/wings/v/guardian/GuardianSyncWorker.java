package wings.v.guardian;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import wings.v.core.AppPrefs;

/**
 * Guardian sync for the PERIODIC mode: one gRPC round trip, no socket to keep
 * alive and no fixed budget to burn through
 */
public final class GuardianSyncWorker extends Worker {

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
        // While the app is open the foreground client owns a live channel, and syncing
        // underneath it makes the panel watch two sessions replace each other under one
        // client id
        if (GuardianForegroundClient.isActive()) {
            return Result.success();
        }
        if (isStopped()) {
            return Result.success();
        }
        if (GuardianSyncClient.sync(app)) {
            GuardianStateBroadcast.send(app, false, "");
            return Result.success();
        }
        // Retry on WorkManager's backoff rather than sitting out the whole period
        GuardianStateBroadcast.send(app, false, "");
        return Result.retry();
    }
}
