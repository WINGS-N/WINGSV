package wings.v.guardian;

import android.content.Context;
import android.content.Intent;
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
        setPreferencesFromResource(R.xml.guardian_preferences, rootKey);

        SwitchPreferenceCompat master = findPreference(AppPrefs.KEY_GUARDIAN_ENABLED);
        if (master != null) {
            master.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = Boolean.TRUE.equals(newValue);
                Context ctx = requireContext();
                if (enabled && !AppPrefs.isGuardianConfigured(ctx)) {
                    return false;
                }
                AppPrefs.setGuardianEnabled(ctx, enabled);
                if (enabled) {
                    GuardianRunner.applyMode(ctx.getApplicationContext());
                } else {
                    GuardianRunner.stopAll(ctx.getApplicationContext());
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
        if (requestCode == REQUEST_REVOKE && resultCode == android.app.Activity.RESULT_OK) {
            Context ctx = requireContext();
            GuardianRunner.stopAll(ctx.getApplicationContext());
            AppPrefs.clearGuardian(ctx);
            refresh();
        }
    }

    private void refresh() {
        Context ctx = requireContext();
        boolean configured = AppPrefs.isGuardianConfigured(ctx);
        boolean enabled = AppPrefs.isGuardianEnabled(ctx);
        SwitchPreferenceCompat master = findPreference(AppPrefs.KEY_GUARDIAN_ENABLED);
        if (master != null) {
            master.setEnabled(configured);
            if (master.isChecked() != enabled) {
                master.setChecked(enabled);
            }
        }

        setSummary("pref_guardian_status", statusText(ctx, configured));
        setSummary("pref_guardian_ws_url_view", orDash(AppPrefs.getGuardianWsUrl(ctx)));
        setSummary("pref_guardian_client_id_view", orDash(AppPrefs.getGuardianClientId(ctx)));
        setSummary("pref_guardian_client_name_view", orDash(AppPrefs.getGuardianClientName(ctx)));
        setSummary("pref_guardian_sync_mode_view", syncModeText(ctx));

        syncSwitch("pref_guardian_log_runtime_allowed", AppPrefs.isGuardianLogRuntimeAllowed(ctx));
        syncSwitch("pref_guardian_log_proxy_allowed", AppPrefs.isGuardianLogProxyAllowed(ctx));
        syncSwitch("pref_guardian_log_xray_allowed", AppPrefs.isGuardianLogXRayAllowed(ctx));

        Preference revoke = findPreference("pref_guardian_revoke");
        if (revoke != null) {
            revoke.setEnabled(configured);
        }
    }

    private String statusText(Context ctx, boolean configured) {
        if (!configured) {
            return getString(R.string.guardian_settings_summary_not_configured);
        }
        if (GuardianService.isConnected()) {
            String host = "";
            String url = AppPrefs.getGuardianWsUrl(ctx);
            if (!TextUtils.isEmpty(url)) {
                try {
                    host = android.net.Uri.parse(url).getHost();
                } catch (Exception ignored) {}
            }
            return getString(R.string.guardian_settings_summary_connected, host == null ? "" : host);
        }
        return getString(R.string.guardian_settings_summary_disconnected);
    }

    private void setSummary(String key, CharSequence summary) {
        Preference p = findPreference(key);
        if (p != null) {
            p.setSummary(summary);
        }
    }

    private void syncSwitch(String key, boolean value) {
        SwitchPreferenceCompat p = findPreference(key);
        if (p != null && p.isChecked() != value) {
            p.setChecked(value);
        }
    }

    private String orDash(String value) {
        return TextUtils.isEmpty(value) ? "—" : value;
    }

    private String syncModeText(Context ctx) {
        String mode = AppPrefs.getGuardianSyncMode(ctx);
        if (AppPrefs.GUARDIAN_SYNC_MODE_PERIODIC.equals(mode)) {
            return getString(
                R.string.guardian_sync_mode_periodic,
                AppPrefs.getGuardianPeriodicIntervalMinutes(ctx)
            );
        }
        if (AppPrefs.GUARDIAN_SYNC_MODE_FOREGROUND_ONLY.equals(mode)) {
            return getString(R.string.guardian_sync_mode_foreground);
        }
        return getString(R.string.guardian_sync_mode_always);
    }
}
