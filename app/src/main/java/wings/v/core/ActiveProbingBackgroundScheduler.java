package wings.v.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import wings.v.receiver.ActiveProbingReceiver;
import wings.v.service.ProxyTunnelService;

public final class ActiveProbingBackgroundScheduler {

    public static final String ACTION_RUN_BACKGROUND_PROBE = "wings.v.action.RUN_BACKGROUND_ACTIVE_PROBE";

    private static final int REQUEST_CODE_BACKGROUND_PROBE = 1004;

    private ActiveProbingBackgroundScheduler() {}

    public static void refresh(@NonNull Context context) {
        ActiveProbingManager.Settings settings = ActiveProbingManager.getSettings(context);
        if (!settings.backgroundEnabled || ProxyTunnelService.isActive()) {
            cancel(context);
            return;
        }
        scheduleNext(context, settings.intervalMs());
    }

    public static void scheduleNext(@NonNull Context context, long delayMs) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        long triggerAt = SystemClock.elapsedRealtime() + Math.max(delayMs, 1_000L);
        PendingIntent pendingIntent = buildPendingIntent(context);
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
    }

    public static void cancel(@NonNull Context context) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        alarmManager.cancel(buildPendingIntent(context));
    }

    @NonNull
    private static PendingIntent buildPendingIntent(@NonNull Context context) {
        Intent intent = new Intent(context, ActiveProbingReceiver.class).setAction(ACTION_RUN_BACKGROUND_PROBE);
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BACKGROUND_PROBE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
