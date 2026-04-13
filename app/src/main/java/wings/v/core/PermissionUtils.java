package wings.v.core;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.PowerManager;
import androidx.core.content.ContextCompat;
import java.util.Locale;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LawOfDemeter",
        "PMD.OnlyOneReturn",
        "PMD.UselessParentheses",
        "PMD.SimplifyBooleanReturns",
        "PMD.LongVariable",
    }
)
public final class PermissionUtils {

    private PermissionUtils() {}

    public static boolean isNotificationGranted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        );
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (isIgnoringBatteryOptimizationsSystem(context)) {
            return true;
        }
        if (!AppPrefs.isBatteryOptimizationAcknowledged(context)) {
            return false;
        }
        if (isXiaomiBatteryOptimizationCheckBrokenDevice()) {
            return true;
        }
        return !isBackgroundRestricted(context);
    }

    public static boolean isIgnoringBatteryOptimizationsSystem(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static boolean isBackgroundRestricted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return activityManager != null && activityManager.isBackgroundRestricted();
    }

    private static boolean isXiaomiBatteryOptimizationCheckBrokenDevice() {
        String manufacturer = trim(Build.MANUFACTURER).toLowerCase(Locale.ROOT);
        String brand = trim(Build.BRAND).toLowerCase(Locale.ROOT);
        String fingerprint = trim(Build.FINGERPRINT).toLowerCase(Locale.ROOT);
        return (
            manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco") ||
            brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco") ||
            fingerprint.contains("xiaomi/") ||
            fingerprint.contains("redmi/") ||
            fingerprint.contains("poco/")
        );
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public static boolean isVpnPermissionGranted(Context context) {
        return VpnService.prepare(context) == null;
    }

    public static boolean isRootPermissionGranted(Context context) {
        return AppPrefs.isRootAccessGranted(context);
    }

    public static boolean areBasePermissionsGranted(Context context) {
        return isNotificationGranted(context) && isIgnoringBatteryOptimizations(context);
    }

    public static boolean areCorePermissionsGranted(Context context) {
        BackendType backendType = XrayStore.getBackendType(context);
        boolean rootModeEnabled = AppPrefs.isRootModeEnabled(context);
        boolean kernelWireGuardEnabled = AppPrefs.isKernelWireGuardEnabled(context);
        if (!rootModeEnabled) {
            return areBasePermissionsGranted(context) && isVpnPermissionGranted(context);
        }

        if (backendType != null && backendType.supportsKernelWireGuard() && kernelWireGuardEnabled) {
            return areBasePermissionsGranted(context) && isRootPermissionGranted(context);
        }
        return (
            areBasePermissionsGranted(context) && isRootPermissionGranted(context) && isVpnPermissionGranted(context)
        );
    }

    public static boolean shouldShowOnboarding(Context context) {
        return !AppPrefs.isOnboardingSeen(context) && !areCorePermissionsGranted(context);
    }
}
