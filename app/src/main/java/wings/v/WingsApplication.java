package wings.v;

import android.app.Application;
import android.os.Build;
import android.text.TextUtils;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import wings.v.core.ActiveProbingBackgroundScheduler;
import wings.v.core.AppPrefs;
import wings.v.core.AppUpdateBackgroundScheduler;
import wings.v.core.ThemeModeController;
import wings.v.core.XraySubscriptionBackgroundScheduler;
import wings.v.service.ProxyTunnelService;
import wings.v.service.RuntimeStateStore;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor" })
public class WingsApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        RuntimeStateStore.initialize(this);
        if (!isMainProcess()) {
            return;
        }
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
