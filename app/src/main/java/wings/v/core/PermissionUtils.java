package wings.v.core;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.PowerManager;
import androidx.core.content.ContextCompat;

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
        return AppPrefs.isBatteryOptimizationAcknowledged(context) && !isBackgroundRestricted(context);
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
