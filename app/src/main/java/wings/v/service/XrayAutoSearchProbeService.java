package wings.v.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AutoSearchProbeKernel;
import wings.v.core.AutoSearchProbeRequest;
import wings.v.core.AutoSearchProbeResult;

@SuppressWarnings({ "PMD.DoNotUseThreads", "PMD.CommentRequired", "PMD.AvoidCatchingGenericException" })
public abstract class XrayAutoSearchProbeService extends Service {

    public static final int RESULT_DELIVERED = 1;
    public static final int RESULT_PROGRESS = 2;
    private static final int WORKER_COUNT = 6;
    private static final String ACTION_PROBE = "wings.v.intent.action.AUTO_SEARCH_PROBE";
    private static final String EXTRA_REQUEST = "request";
    private static final String EXTRA_RECEIVER = "receiver";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static int workerCount() {
        return WORKER_COUNT;
    }

    public static boolean startProbe(
        @NonNull Context context,
        int workerIndex,
        @NonNull AutoSearchProbeRequest request,
        @NonNull ResultReceiver receiver
    ) {
        Intent intent = new Intent(context, workerClass(workerIndex)).setAction(ACTION_PROBE);
        intent.putExtra(EXTRA_REQUEST, request.toBundle());
        intent.putExtra(EXTRA_RECEIVER, receiver);
        try {
            return context.startService(intent) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void stopProbe(@NonNull Context context, int workerIndex) {
        Intent intent = new Intent(context, workerClass(workerIndex));
        try {
            context.stopService(intent);
        } catch (RuntimeException ignored) {}
    }

    private static Class<? extends XrayAutoSearchProbeService> workerClass(int workerIndex) {
        switch (Math.floorMod(workerIndex, WORKER_COUNT)) {
            case 1:
                return XrayAutoSearchProbeWorker1Service.class;
            case 2:
                return XrayAutoSearchProbeWorker2Service.class;
            case 3:
                return XrayAutoSearchProbeWorker3Service.class;
            case 4:
                return XrayAutoSearchProbeWorker4Service.class;
            case 5:
                return XrayAutoSearchProbeWorker5Service.class;
            case 0:
            default:
                return XrayAutoSearchProbeWorker0Service.class;
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_PROBE.equals(intent.getAction())) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        Bundle requestBundle = intent.getBundleExtra(EXTRA_REQUEST);
        ResultReceiver receiver = intent.getParcelableExtra(EXTRA_RECEIVER);
        if (requestBundle == null || receiver == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        AutoSearchProbeRequest request = AutoSearchProbeRequest.fromBundle(requestBundle);
        executor.execute(() -> {
            AutoSearchProbeResult result;
            try {
                result = AutoSearchProbeKernel.run(getApplicationContext(), request, progressBundle -> {
                    try {
                        receiver.send(RESULT_PROGRESS, progressBundle);
                    } catch (RuntimeException ignored) {}
                });
            } catch (Throwable error) {
                result = AutoSearchProbeResult.failure("kernel crash: " + error.getMessage());
            }
            try {
                receiver.send(RESULT_DELIVERED, result.toBundle());
            } catch (RuntimeException ignored) {}
            stopSelfResult(startId);
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
