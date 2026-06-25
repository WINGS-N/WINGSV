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
    private static volatile android.content.Context sAppContext;

    public static boolean isUiForeground() {
        return STARTED_ACTIVITY_COUNT.get() > 0;
    }

    public static android.content.Context appContext() {
        return sAppContext;
    }

    public static String getStringSafe(int resId, Object... args) {
        android.content.Context context = sAppContext;
        if (context == null) {
            return "";
        }
        return args.length == 0 ? context.getString(resId) : context.getString(resId, args);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = getApplicationContext();
        applyWebViewDataDirectorySuffix();
        wings.v.core.MmkvPrefs.ensureInitialized(this);
        RuntimeStateStore.initialize(this);
        if (!isMainProcess()) {
            return;
        }
        wings.v.core.AppPrefs.runMigrationsIfNeeded(this);
        registerActivityLifecycleCallbacks(
            new ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {}

                @Override
                public void onActivityStarted(@NonNull Activity activity) {
                    if (STARTED_ACTIVITY_COUNT.incrementAndGet() == 1) {
                        // App entered foreground (any activity). Hold a live guardian
                        // WS across the whole app, not just MainActivity, so it does
                        // not drop when navigating into other screens.
                        wings.v.guardian.GuardianRunner.onAppForeground(activity.getApplicationContext());
                    }
                }

                @Override
                public void onActivityResumed(@NonNull Activity activity) {}

                @Override
                public void onActivityPaused(@NonNull Activity activity) {}

                @Override
                public void onActivityStopped(@NonNull Activity activity) {
                    int value = STARTED_ACTIVITY_COUNT.decrementAndGet();
                    if (value <= 0) {
                        STARTED_ACTIVITY_COUNT.set(0);
                        // App left foreground: drop the foreground WS and fall back
                        // to the periodic worker for FOREGROUND_ONLY/PERIODIC.
                        wings.v.guardian.GuardianRunner.onAppBackground(activity.getApplicationContext());
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

    // Android forbids a WebView in two processes sharing one data directory
    // (https://crbug.com/558377). All real WebView work runs in the main
    // process, but give every process its own suffix as a defensive guard so a
    // stray WebView init in :tunnel (or any helper process) can never take the
    // main process data-dir lock and crash it.
    private void applyWebViewDataDirectorySuffix() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        try {
            String processName = getCurrentProcessName();
            String suffix;
            if (TextUtils.isEmpty(processName) || getPackageName().equals(processName)) {
                suffix = "main";
            } else {
                int colon = processName.indexOf(':');
                String tail = colon >= 0 ? processName.substring(colon + 1) : processName;
                suffix = TextUtils.isEmpty(tail) ? "proc" : tail.replaceAll("[^A-Za-z0-9_]", "_");
            }
            android.webkit.WebView.setDataDirectorySuffix(suffix);
        } catch (Exception ignored) {
            // Best effort; never let this crash app startup.
        }
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
