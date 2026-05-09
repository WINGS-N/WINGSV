package wings.v.guardian;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;
import wings.v.core.AppPrefs;

public final class GuardianRunner {

    public static final String PERIODIC_WORK_TAG = "wings.v.guardian.periodic";

    private GuardianRunner() {}

    public static void applyMode(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!AppPrefs.isGuardianEnabled(app) || !AppPrefs.isGuardianConfigured(app)) {
            stopAll(app);
            return;
        }
        String mode = AppPrefs.getGuardianSyncMode(app);
        switch (mode) {
            case AppPrefs.GUARDIAN_SYNC_MODE_PERIODIC:
                stopForegroundService(app);
                schedulePeriodic(app);
                break;
            case AppPrefs.GUARDIAN_SYNC_MODE_FOREGROUND_ONLY:
                stopForegroundService(app);
                cancelPeriodic(app);
                break;
            case AppPrefs.GUARDIAN_SYNC_MODE_ALWAYS:
            default:
                cancelPeriodic(app);
                startForegroundService(app);
                break;
        }
    }

    public static void stopAll(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        stopForegroundService(app);
        cancelPeriodic(app);
    }

    private static void startForegroundService(Context app) {
        Intent start = GuardianService.startIntent(app);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(start);
            } else {
                app.startService(start);
            }
        } catch (RuntimeException ignored) {}
    }

    private static void stopForegroundService(Context app) {
        try {
            app.stopService(new Intent(app, GuardianService.class));
        } catch (RuntimeException ignored) {}
    }

    private static void schedulePeriodic(Context app) {
        long intervalMinutes = Math.max(
            AppPrefs.GUARDIAN_PERIODIC_MIN_MINUTES,
            AppPrefs.getGuardianPeriodicIntervalMinutes(app)
        );
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            GuardianSyncWorker.class,
            intervalMinutes,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(PERIODIC_WORK_TAG)
            .build();
        try {
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                PERIODIC_WORK_TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            );
        } catch (RuntimeException ignored) {}
    }

    private static void cancelPeriodic(Context app) {
        try {
            WorkManager.getInstance(app).cancelUniqueWork(PERIODIC_WORK_TAG);
        } catch (RuntimeException ignored) {}
    }
}
