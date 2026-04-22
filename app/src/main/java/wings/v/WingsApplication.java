package wings.v;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import wings.v.core.ActiveProbingBackgroundScheduler;
import wings.v.core.AppPrefs;
import wings.v.core.AppUpdateBackgroundScheduler;
import wings.v.core.ThemeModeController;
import wings.v.core.XraySubscriptionBackgroundScheduler;
import wings.v.service.ProxyTunnelService;
import wings.v.service.RuntimeStateStore;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor" })
public class WingsApplication extends Application {

    private static final AtomicInteger STARTED_ACTIVITY_COUNT = new AtomicInteger(0);

    public static boolean isUiForeground() {
        return STARTED_ACTIVITY_COUNT.get() > 0;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RuntimeStateStore.initialize(this);
        if (!isMainProcess()) {
            return;
        }
        registerActivityLifecycleCallbacks(
            new ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {}

                @Override
                public void onActivityStarted(@NonNull Activity activity) {
                    STARTED_ACTIVITY_COUNT.incrementAndGet();
                }

                @Override
                public void onActivityResumed(@NonNull Activity activity) {}

                @Override
                public void onActivityPaused(@NonNull Activity activity) {}

                @Override
                public void onActivityStopped(@NonNull Activity activity) {
                    int value = STARTED_ACTIVITY_COUNT.decrementAndGet();
                    if (value < 0) {
                        STARTED_ACTIVITY_COUNT.set(0);
                    }
                }

                @Override
                public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {}

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {}
            }
        );
        AppPrefs.ensureDefaults(this);
        ProxyTunnelService.reconcilePersistedRuntimeStateOnAppStart(this);
        ThemeModeController.apply(this);
        AppUpdateBackgroundScheduler.schedule(this);
        ActiveProbingBackgroundScheduler.refresh(this);
        XraySubscriptionBackgroundScheduler.refresh(this);
    }

    private boolean isMainProcess() {
        String processName = getCurrentProcessName();
        return TextUtils.isEmpty(processName) || getPackageName().equals(processName);
    }

    @SuppressWarnings("PMD.AvoidFileStream")
    private String getCurrentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        byte[] buffer = new byte[256];
        try (FileInputStream input = new FileInputStream("/proc/self/cmdline")) {
            int read = input.read(buffer);
            if (read <= 0) {
                return null;
            }
            int length = 0;
            while (length < read && buffer[length] != 0) {
                length++;
            }
            return new String(buffer, 0, length, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }
}
