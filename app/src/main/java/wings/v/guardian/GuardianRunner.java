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
        // Goes by the real foreground state, so a setting flipped with the app open
        // brings the foreground channel up right away, and a boot trigger still lands on
        // the periodic worker
        reapply(context);
    }

    public static void reapply(Context context) {
        if (context == null) return;
        if (wings.v.WingsApplication.isUiForeground()) {
            onAppForeground(context);
        } else {
            onAppBackground(context);
        }
    }

    /**
     * @param activityForeground хотя бы одна Activity в started. Важно для
     * FOREGROUND_ONLY и PERIODIC: пока юзер в приложении, канал держит
     * {@link GuardianForegroundClient}, а на onStop он закрывается и синхронизацию
     * подхватывает periodic-воркер
     */
    public static void applyMode(Context context, boolean activityForeground) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!AppPrefs.isGuardianEnabled(app) || !AppPrefs.isGuardianConfigured(app)) {
            stopAll(app);
            return;
        }
        String mode = AppPrefs.getGuardianSyncMode(app);
        switch (mode) {
            case AppPrefs.GUARDIAN_SYNC_MODE_PERIODIC:
            case AppPrefs.GUARDIAN_SYNC_MODE_FOREGROUND_ONLY:
                stopForegroundService(app);
                if (activityForeground) {
                    cancelPeriodic(app);
                } else {
                    schedulePeriodic(app);
                }
                break;
            case AppPrefs.GUARDIAN_SYNC_MODE_ALWAYS:
            default:
                cancelPeriodic(app);
                startForegroundService(app);
                break;
        }
    }

    /**
     * The whole app came to the foreground, not just MainActivity: in
     * FOREGROUND_ONLY/PERIODIC that hands the channel to
     * {@link GuardianForegroundClient} and cancels the periodic worker. Per-app scope is
     * the point - otherwise the connection drops on every screen change
     */
    public static void onAppForeground(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        applyMode(app, true);
        if (!AppPrefs.isGuardianEnabled(app) || !AppPrefs.isGuardianConfigured(app)) {
            return;
        }
        String mode = AppPrefs.getGuardianSyncMode(app);
        if (
            AppPrefs.GUARDIAN_SYNC_MODE_FOREGROUND_ONLY.equals(mode) ||
            AppPrefs.GUARDIAN_SYNC_MODE_PERIODIC.equals(mode)
        ) {
            GuardianForegroundClient.start(app);
        }
    }

    /**
     * The last Activity stopped: drop the foreground channel and let the periodic worker
     * take over in the modes that have one
     */
    public static void onAppBackground(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        GuardianForegroundClient.stop();
        applyMode(app, false);
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
