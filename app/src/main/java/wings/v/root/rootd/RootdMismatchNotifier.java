package wings.v.root.rootd;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.AppUpdateBackgroundScheduler;
import wings.v.core.PermissionUtils;
import wings.v.proto.RootdProto;

/**
 * Tells the user when the root helper module and the app no longer speak the same
 * protocol.
 *
 * <p>This is worth interrupting for: nothing breaks - the app silently goes back to the
 * su path - so the only symptom is that a module the user deliberately installed has
 * quietly stopped doing anything. Without a notification they would have no way to find
 * out short of opening settings.
 *
 * <p>Deduped per version pair the way update notices are deduped per tag, so a mismatch
 * is announced once rather than on every probe.
 */
public final class RootdMismatchNotifier {

    private static final int NOTIFICATION_ID = 4;

    private RootdMismatchNotifier() {}

    public static void notifyIfMismatched(Context context) {
        if (RootExecutor.lastKnownStatus() != RootExecutor.Status.VERSION_MISMATCH) {
            return;
        }
        RootdProto.HelloReply hello = RootExecutor.lastHello();
        if (hello == null) {
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

        String tag = RootdClient.PROTOCOL_VERSION + ":" + hello.getProtocolVersion();
        if (TextUtils.equals(tag, AppPrefs.getLastRootdMismatchNotifiedTag(context))) {
            return;
        }

        // Naming the stale side is the whole value of the message: "versions differ"
        // leaves the user with nothing to do.
        boolean moduleIsOlder = hello.getProtocolVersion() < RootdClient.PROTOCOL_VERSION;
        String text = context.getString(
            moduleIsOlder ? R.string.rootd_mismatch_update_module : R.string.rootd_mismatch_update_app
        );

        createNotificationChannel(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
            context,
            AppUpdateBackgroundScheduler.UPDATE_NOTIFICATION_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_arrow_down)
            .setContentTitle(context.getString(R.string.rootd_mismatch_title))
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS);

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
        AppPrefs.setLastRootdMismatchNotifiedTag(context, tag);
    }

    private static void createNotificationChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (manager.getNotificationChannel(AppUpdateBackgroundScheduler.UPDATE_NOTIFICATION_CHANNEL_ID) != null) {
            return;
        }
        manager.createNotificationChannel(
            new NotificationChannel(
                AppUpdateBackgroundScheduler.UPDATE_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        );
    }
}
