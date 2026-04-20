package wings.v.worker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import wings.v.core.XraySubscriptionBackgroundScheduler;
import wings.v.core.XraySubscriptionUpdater;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AvoidCatchingGenericException" })
public final class XraySubscriptionRefreshWorker extends Worker {

    public XraySubscriptionRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            XraySubscriptionUpdater.refreshDue(context);
        } catch (Exception ignored) {
        } finally {
            XraySubscriptionBackgroundScheduler.refresh(context);
        }
        return Result.success();
    }
}
