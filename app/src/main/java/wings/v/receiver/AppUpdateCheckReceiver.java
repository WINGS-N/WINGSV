package wings.v.receiver;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.AboutAppActivity;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.AppUpdateBackgroundScheduler;
import wings.v.core.AppUpdateManager;
import wings.v.core.PermissionUtils;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.CyclomaticComplexity",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class AppUpdateCheckReceiver extends BroadcastReceiver {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!AppUpdateBackgroundScheduler.ACTION_CHECK_UPDATES.equals(intent.getAction())) {
            return;
        }
        PendingResult pendingResult = goAsync();
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                AppUpdateManager appUpdateManager = AppUpdateManager.getInstance(appContext);
                AppUpdateManager.UpdateState state = appUpdateManager.queryLatestStateBlocking();
                appUpdateManager.applyBackgroundState(state);
                maybeNotifyAboutUpdate(appContext, state);
            } finally {
                pendingResult.finish();
            }
        });
    }

    private static void maybeNotifyAboutUpdate(Context context, AppUpdateManager.UpdateState state) {
        if (state == null || state.releaseInfo == null) {
            return;
        }
        if (
            state.status != AppUpdateManager.Status.UPDATE_AVAILABLE &&
            state.status != AppUpdateManager.Status.DOWNLOADED
        ) {
            return;
        }
        if (!PermissionUtils.isNotificationGranted(context)) {
            return;
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return;
        }
        String tagName = state.releaseInfo.tagName;
        if (TextUtils.isEmpty(tagName)) {
            return;
        }
        if (TextUtils.equals(tagName, AppPrefs.getLastUpdateNotifiedTag(context))) {
            return;
        }

        createNotificationChannel(context);

        Intent aboutIntent = AboutAppActivity.createIntent(context).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        PendingIntent contentIntent = PendingIntent.getActivity(
            context,
            0,
            aboutIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String versionName = state.releaseInfo.versionName;
        String title =
            state.status == AppUpdateManager.Status.DOWNLOADED
                ? context.getString(R.string.update_notification_downloaded_title, versionName)
                : context.getString(R.string.update_notification_available_title, versionName);
        String text =
            state.status == AppUpdateManager.Status.DOWNLOADED
                ? context.getString(R.string.update_notification_downloaded_text)
                : context.getString(R.string.update_notification_available_text);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
            context,
            AppUpdateBackgroundScheduler.UPDATE_NOTIFICATION_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_arrow_down)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION);

        NotificationManagerCompat.from(context).notify(
            AppUpdateBackgroundScheduler.UPDATE_NOTIFICATION_ID,
            builder.build()
        );
        AppPrefs.setLastUpdateNotifiedTag(context, tagName);
    }

    private static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            AppUpdateBackgroundScheduler.UPDATE_NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.update_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(R.string.update_notification_channel_description));
        notificationManager.createNotificationChannel(channel);
    }
}
