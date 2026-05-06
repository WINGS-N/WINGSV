package wings.v.core;

import android.app.Activity;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import wings.v.R;
import wings.v.WarningConfirmActivity;

/**
 * Single source of truth for the 5-second confirmation a wingsv:// import
 * needs when it carries a Guardian block. Applied consistently across the
 * main-screen import flow and the first-launch fragments — there is no other
 * gate that catches a Guardian-bearing config.
 */
public final class GuardianImportGate {

    public static final int CONFIRM_DELAY_SECONDS = 5;

    private GuardianImportGate() {}

    public static boolean needsConfirmation(@Nullable WingsImportParser.ImportedConfig config) {
        return config != null && config.hasGuardian;
    }

    public static Intent createIntent(@NonNull Activity activity) {
        return WarningConfirmActivity.createIntent(
            activity,
            activity.getString(R.string.guardian_warning_text),
            CONFIRM_DELAY_SECONDS
        );
    }

    public static void launchFromActivity(@NonNull Activity activity, int requestCode) {
        activity.startActivityForResult(createIntent(activity), requestCode);
    }

    public static void launchFromFragment(@NonNull Fragment fragment, int requestCode) {
        fragment.startActivityForResult(createIntent(fragment.requireActivity()), requestCode);
    }
}
