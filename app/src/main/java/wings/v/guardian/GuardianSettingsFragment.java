package wings.v.guardian;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import wings.v.R;
import wings.v.WarningConfirmActivity;
import wings.v.core.AppPrefs;

/** Backing fragment for GuardianActivity. */
public final class GuardianSettingsFragment extends PreferenceFragmentCompat {

    private static final int REQUEST_REVOKE = 4101;

    private GuardianStateBroadcast.Listener stateListener;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName("wings.v.app_prefs");
        getPreferenceManager().setSharedPreferencesMode(Context.MODE_PRIVATE);
        setPreferencesFromResource(R.xml.guardian_preferences, rootKey);

        SwitchPreferenceCompat master = findPreference(AppPrefs.KEY_GUARDIAN_ENABLED);
        if (master != null) {
            master.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = Boolean.TRUE.equals(newValue);
                Context ctx = requireContext();
                if (enabled) {
                    if (!AppPrefs.isGuardianConfigured(ctx)) {
                        return false;
                    }
                    Intent start = GuardianService.startIntent(ctx);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(start);
                    } else {
                        ctx.startService(start);
                    }
                } else {
                    ctx.startService(GuardianService.stopIntent(ctx));
                }
                return true;
            });
        }

        Preference revoke = findPreference("pref_guardian_revoke");
        if (revoke != null) {
            revoke.setOnPreferenceClickListener(p -> {
                startActivityForResult(
                    WarningConfirmActivity.createIntent(
                        requireContext(),
                        getString(R.string.guardian_revoke_warning),
                        5
                    ),
                    REQUEST_REVOKE
                );
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        stateListener = (connected, host) -> refresh();
        GuardianStateBroadcast.register(stateListener);
    }

    @Override
    public void onPause() {
        if (stateListener != null) {
            GuardianStateBroadcast.unregister(stateListener);
            stateListener = null;
        }
        super.onPause();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_REVOKE && resultCode == AppCompatResultOk()) {
            Context ctx = requireContext();
            ctx.startService(GuardianService.stopIntent(ctx));
            AppPrefs.clearGuardian(ctx);
            refresh();
        }
    }

    private int AppCompatResultOk() {
        return android.app.Activity.RESULT_OK;
    }

    private void refresh() {
        Context ctx = requireContext();
        Preference status = findPreference("pref_guardian_status");
        if (status != null) {
            if (!AppPrefs.isGuardianConfigured(ctx)) {
                status.setSummary(R.string.guardian_settings_summary_not_configured);
            } else if (GuardianService.isConnected()) {
                String host = hostOf(AppPrefs.getGuardianWsUrl(ctx));
                status.setSummary(getString(R.string.guardian_settings_summary_connected, host));
            } else {
                status.setSummary(R.string.guardian_settings_summary_disconnected);
            }
        }
        Preference wsUrl = findPreference("pref_guardian_ws_url_view");
        if (wsUrl != null) {
            wsUrl.setSummary(orDash(AppPrefs.getGuardianWsUrl(ctx)));
        }
        Preference clientId = findPreference("pref_guardian_client_id_view");
        if (clientId != null) {
            clientId.setSummary(orDash(AppPrefs.getGuardianClientId(ctx)));
        }
        Preference clientName = findPreference("pref_guardian_client_name_view");
        if (clientName != null) {
            clientName.setSummary(orDash(AppPrefs.getGuardianClientName(ctx)));
        }
        Preference logs = findPreference("pref_guardian_logs_view");
        if (logs != null) {
            logs.setSummary(
                getString(
                    R.string.guardian_pref_logs_summary,
                    getString(
                        AppPrefs.isGuardianLogRuntimeAllowed(ctx)
                            ? R.string.guardian_logs_on
                            : R.string.guardian_logs_off
                    ),
                    getString(
                        AppPrefs.isGuardianLogProxyAllowed(ctx) ? R.string.guardian_logs_on : R.string.guardian_logs_off
                    ),
                    getString(
                        AppPrefs.isGuardianLogXRayAllowed(ctx) ? R.string.guardian_logs_on : R.string.guardian_logs_off
                    )
                )
            );
        }
    }

    private String hostOf(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        try {
            return android.net.Uri.parse(url).getHost();
        } catch (Exception ignored) {
            return url;
        }
    }

    private String orDash(String value) {
        return TextUtils.isEmpty(value) ? "—" : value;
    }
}
