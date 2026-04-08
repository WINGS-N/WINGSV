package wings.v.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import wings.v.receiver.AppUpdateCheckReceiver;

public final class AppUpdateBackgroundScheduler {

    public static final String ACTION_CHECK_UPDATES = "wings.v.action.CHECK_UPDATES";
    public static final String UPDATE_NOTIFICATION_CHANNEL_ID = "wingsv_updates";
    public static final int UPDATE_NOTIFICATION_ID = 3;
    private static final int REQUEST_CODE_UPDATE_CHECK = 1003;
    private static final long UPDATE_CHECK_INTERVAL_MS = 4L * 60L * 60L * 1000L;

    private AppUpdateBackgroundScheduler() {}

    public static void schedule(@NonNull Context context) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = buildPendingIntent(context);
        long firstTriggerAt = SystemClock.elapsedRealtime() + UPDATE_CHECK_INTERVAL_MS;
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            firstTriggerAt,
            UPDATE_CHECK_INTERVAL_MS,
            pendingIntent
        );
    }

    @NonNull
    private static PendingIntent buildPendingIntent(@NonNull Context context) {
        Intent intent = new Intent(context, AppUpdateCheckReceiver.class).setAction(ACTION_CHECK_UPDATES);
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_UPDATE_CHECK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
