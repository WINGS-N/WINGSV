package wings.v.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.XraySubscriptionBackgroundScheduler;
import wings.v.core.XraySubscriptionUpdater;

@SuppressWarnings({ "PMD.DoNotUseThreads", "PMD.AvoidCatchingGenericException" })
public class XraySubscriptionRefreshReceiver extends BroadcastReceiver {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!XraySubscriptionBackgroundScheduler.ACTION_REFRESH_SUBSCRIPTIONS.equals(intent.getAction())) {
            return;
        }
        Context appContext = context.getApplicationContext();
        PendingResult pendingResult = goAsync();
        EXECUTOR.execute(() -> {
            try {
                XraySubscriptionUpdater.refreshDue(appContext);
            } catch (Exception ignored) {
            } finally {
                XraySubscriptionBackgroundScheduler.refresh(appContext);
                pendingResult.finish();
            }
        });
    }
}
