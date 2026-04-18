package wings.v.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import androidx.core.content.ContextCompat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.ActiveProbingBackgroundScheduler;
import wings.v.core.ActiveProbingManager;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.ProxySettings;
import wings.v.core.XrayStore;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings({ "PMD.DoNotUseThreads", "PMD.AvoidCatchingGenericException" })
public class ActiveProbingReceiver extends BroadcastReceiver {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!ActiveProbingBackgroundScheduler.ACTION_RUN_BACKGROUND_PROBE.equals(intent.getAction())) {
            return;
        }
        Context appContext = context.getApplicationContext();
        PendingResult pendingResult = goAsync();
        EXECUTOR.execute(() -> {
            boolean triggered = false;
            try {
                ActiveProbingManager.Settings settings = ActiveProbingManager.getSettings(appContext);
                if (!settings.backgroundEnabled || ProxyTunnelService.isActive()) {
                    return;
                }
                ActiveProbingManager.ProbeResult result = ActiveProbingManager.runDirectProbes(appContext, settings);
                if (!result.shouldFallback()) {
                    return;
                }
                BackendType fallbackBackend = ActiveProbingManager.normalizeXrayFallbackBackend(
                    settings.xrayFallbackBackend
                );
                ProxySettings fallbackSettings = AppPrefs.getSettings(appContext);
                fallbackSettings.backendType = fallbackBackend;
                if (!TextUtils.isEmpty(fallbackSettings.validate())) {
                    return;
                }
                BackendType restoreBackend = XrayStore.getBackendType(appContext);
                if (restoreBackend == null || !restoreBackend.usesXrayCore()) {
                    restoreBackend = BackendType.XRAY;
                }
                triggered = true;
                ActiveProbingManager.showBackgroundFallbackNotification(appContext, result, fallbackBackend);
                ActiveProbingManager.setRestoreBackend(appContext, restoreBackend);
                XrayStore.setBackendType(appContext, fallbackBackend);
                ContextCompat.startForegroundService(
                    appContext,
                    ProxyTunnelService.createStartIntent(appContext, fallbackBackend)
                );
                ActiveProbingBackgroundScheduler.cancel(appContext);
            } catch (Exception ignored) {
            } finally {
                if (!triggered) {
                    ActiveProbingBackgroundScheduler.refresh(appContext);
                }
                pendingResult.finish();
            }
        });
    }
}
