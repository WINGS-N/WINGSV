package wings.v.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AppPrefs;
import wings.v.core.XposedModulePrefs;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings({ "PMD.DoNotUseThreads", "PMD.AvoidCatchingGenericException" })
public class RecommendedBypassAppsReceiver extends BroadcastReceiver {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
            return;
        }
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return;
        }
        Uri data = intent.getData();
        String packageName = data != null ? data.getSchemeSpecificPart() : null;
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        PendingResult pendingResult = goAsync();
        EXECUTOR.execute(() -> {
            try {
                boolean bypassChanged = AppPrefs.maybeAutoEnableRecommendedAppRoutingPackage(appContext, packageName);
                XposedModulePrefs.maybeAutoEnableRecommendedPackage(
                    appContext,
                    XposedModulePrefs.KEY_TARGET_PACKAGES,
                    packageName
                );
                XposedModulePrefs.maybeAutoEnableRecommendedPackage(
                    appContext,
                    XposedModulePrefs.KEY_HIDDEN_VPN_PACKAGES,
                    packageName
                );
                if (bypassChanged) {
                    ProxyTunnelService.requestReconnect(appContext, "Recommended bypass app installed");
                }
            } catch (Exception ignored) {
            } finally {
                pendingResult.finish();
            }
        });
    }
}
