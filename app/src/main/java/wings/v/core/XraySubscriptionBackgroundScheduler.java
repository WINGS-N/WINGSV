package wings.v.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import wings.v.receiver.XraySubscriptionRefreshReceiver;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.LawOfDemeter",
    }
)
public final class XraySubscriptionBackgroundScheduler {

    public static final String ACTION_REFRESH_SUBSCRIPTIONS = "wings.v.action.REFRESH_XRAY_SUBSCRIPTIONS";
    private static final int REQUEST_CODE_REFRESH_SUBSCRIPTIONS = 1004;
    private static final long MIN_SCHEDULE_DELAY_MS = 15_000L;
    private static final long INITIAL_REFRESH_DELAY_MS = 60_000L;
    private static final long MILLIS_PER_MINUTE = 60L * 1000L;

    private XraySubscriptionBackgroundScheduler() {}

    public static void refresh(@NonNull Context context) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = buildPendingIntent(context);
        long nextRefreshAt = resolveNextRefreshAt(context);
        if (nextRefreshAt <= 0L) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
            return;
        }
        long delayMs = Math.max(MIN_SCHEDULE_DELAY_MS, nextRefreshAt - System.currentTimeMillis());
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pendingIntent
        );
    }

    public static void cancel(@NonNull Context context) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = buildPendingIntent(context);
        alarmManager.cancel(pendingIntent);
        pendingIntent.cancel();
    }

    private static long resolveNextRefreshAt(@NonNull Context context) {
        long now = System.currentTimeMillis();
        int defaultRefreshMinutes = Math.max(1, XrayStore.getRefreshIntervalMinutes(context));
        long nextRefreshAt = Long.MAX_VALUE;
        boolean hasAutoUpdateSubscription = false;
        for (XraySubscription subscription : XrayStore.getSubscriptions(context)) {
            if (subscription == null || !subscription.autoUpdate || isEmpty(subscription.url)) {
                continue;
            }
            hasAutoUpdateSubscription = true;
            int refreshMinutes =
                subscription.refreshIntervalMinutes > 0 ? subscription.refreshIntervalMinutes : defaultRefreshMinutes;
            long candidateAt =
                subscription.lastUpdatedAt > 0L
                    ? subscription.lastUpdatedAt + (refreshMinutes * MILLIS_PER_MINUTE)
                    : now + INITIAL_REFRESH_DELAY_MS;
            nextRefreshAt = Math.min(nextRefreshAt, candidateAt);
        }
        return hasAutoUpdateSubscription ? nextRefreshAt : 0L;
    }

    @NonNull
    private static PendingIntent buildPendingIntent(@NonNull Context context) {
        Intent intent = new Intent(context, XraySubscriptionRefreshReceiver.class).setAction(
            ACTION_REFRESH_SUBSCRIPTIONS
        );
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REFRESH_SUBSCRIPTIONS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
