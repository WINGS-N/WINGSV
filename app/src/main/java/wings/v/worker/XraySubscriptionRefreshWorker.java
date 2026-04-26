package wings.v.worker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import wings.v.core.BackendType;
import wings.v.core.XrayStore;
import wings.v.core.XraySubscriptionBackgroundScheduler;
import wings.v.core.XraySubscriptionUpdater;
import wings.v.service.ProxyTunnelService;

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
            XraySubscriptionUpdater.RefreshResult result = XraySubscriptionUpdater.refreshDue(context);
            if (result != null && result.activeProfileChanged) {
                BackendType backendType = XrayStore.getBackendType(context);
                if (backendType != null && backendType.usesXrayCore() && ProxyTunnelService.isActive()) {
                    ProxyTunnelService.requestReconnect(
                        context,
                        "Xray subscription background refresh updated active profile",
                        null,
                        result.activeProfileId
                    );
                }
            }
        } catch (Exception ignored) {
        } finally {
            XraySubscriptionBackgroundScheduler.refresh(context);
        }
        return Result.success();
    }
}
