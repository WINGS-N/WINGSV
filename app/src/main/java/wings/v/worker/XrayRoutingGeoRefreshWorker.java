package wings.v.worker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import wings.v.core.XrayRoutingStore;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AvoidCatchingGenericException" })
public final class XrayRoutingGeoRefreshWorker extends Worker {

    public XrayRoutingGeoRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            XrayRoutingStore.refreshGeoFilesIfStale(getApplicationContext());
        } catch (Exception ignored) {}
        return Result.success();
    }
}
