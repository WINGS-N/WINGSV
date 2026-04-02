package wings.v.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import wings.v.core.AppPrefs;
import wings.v.core.PermissionUtils;
import wings.v.core.RootUtils;
import wings.v.service.ProxyTunnelService;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        boolean autoStartConnection = AppPrefs.isAutoStartOnBootEnabled(context);
        boolean autoStartSharing = AppPrefs.isSharingAutoStartOnBootEnabled(context)
                && AppPrefs.isRootModeEnabled(context);
        if (!autoStartConnection && !autoStartSharing) {
            return;
        }
        if (AppPrefs.isRootModeEnabled(context) && !RootUtils.refreshRootAccessState(context)) {
            AppPrefs.clearRootRuntimeState(context);
            return;
        }
        if (!PermissionUtils.areCorePermissionsGranted(context)) {
            return;
        }
        if (AppPrefs.isRootModeEnabled(context)
                && !RootUtils.isRootModeSupported(context)) {
            return;
        }
        try {
            ContextCompat.startForegroundService(
                    context,
                    autoStartSharing
                            ? ProxyTunnelService.createRestoreSharingOnBootIntent(context)
                            : ProxyTunnelService.createStartIntent(context)
            );
        } catch (Exception ignored) {
        }
    }
}
