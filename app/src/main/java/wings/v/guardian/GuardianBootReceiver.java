package wings.v.guardian;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import wings.v.core.AppPrefs;

/** Brings GuardianService back up after boot when the user opted in. */
public final class GuardianBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        if (
            !AppPrefs.isGuardianEnabled(context) ||
            !AppPrefs.isGuardianAutoStartOnBootEnabled(context) ||
            !AppPrefs.isGuardianConfigured(context)
        ) {
            return;
        }
        GuardianRunner.applyMode(context);
    }
}
