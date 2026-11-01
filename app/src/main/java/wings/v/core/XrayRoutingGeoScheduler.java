package wings.v.core;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;
import wings.v.worker.XrayRoutingGeoRefreshWorker;

@SuppressWarnings(
    { "PMD.CommentRequired", "PMD.MethodArgumentCouldBeFinal", "PMD.LocalVariableCouldBeFinal", "PMD.LawOfDemeter" }
)
public final class XrayRoutingGeoScheduler {

    private static final String UNIQUE_WORK_NAME = "xray_routing_geo_refresh";
    private static final long REFRESH_INTERVAL_HOURS = 12L;

    private XrayRoutingGeoScheduler() {}

    /**
     * Ставит суточное обновление баз geoip и geosite. Тикер чаще самого срока: в
     * дорогу укладывается то, что телефон спал или сидел без сети, и база всё
     * равно не перекачивается раньше своего срока.
     */
    public static void refresh(@NonNull Context context) {
        WorkManager workManager = WorkManager.getInstance(context);
        if (!XrayRoutingStore.isGeoAutoUpdateEnabled(context)) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME);
            return;
        }
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            XrayRoutingGeoRefreshWorker.class,
            REFRESH_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }
}
