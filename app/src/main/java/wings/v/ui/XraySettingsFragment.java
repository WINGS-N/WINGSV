package wings.v.ui;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import wings.v.R;
import wings.v.WarningConfirmActivity;
import wings.v.XrayRoutingSettingsActivity;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.SocksAuthSecurity;
import wings.v.core.XraySettings;
import wings.v.core.XrayStore;

public class XraySettingsFragment extends PreferenceFragmentCompat {

    private static final int SOCKS_AUTH_DISABLE_WARNING_DELAY_SECONDS = 15;

    @Nullable
    private Runnable pendingWarningConfirmedAction;

    private final ActivityResultLauncher<android.content.Intent> warningLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            Runnable action = pendingWarningConfirmedAction;
            pendingWarningConfirmedAction = null;
            if (!isAdded()) {
                return;
            }
            if (result.getResultCode() == android.app.Activity.RESULT_OK && action != null) {
                action.run();
            }
            syncFromStore();
        }
    );

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.xray_preferences, rootKey);
        bindSwitch(AppPrefs.KEY_XRAY_ALLOW_LAN);
        bindSwitch(AppPrefs.KEY_XRAY_ALLOW_INSECURE);
        bindSwitch(AppPrefs.KEY_XRAY_LOCAL_PROXY_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_IPV6_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_SNIFFING_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_PROXY_QUIC_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_RESTART_ON_NETWORK_CHANGE);
        bindRoutingEntry();
        bindSummary(AppPrefs.KEY_XRAY_REMOTE_DNS);
        bindSummary(AppPrefs.KEY_XRAY_DIRECT_DNS);
        bindSummary(AppPrefs.KEY_XRAY_LOCAL_PROXY_USERNAME);
        bindSummary(AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD);
        bindNumeric(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT);
        syncFromStore();
    }

    @Override
    public void onResume() {
        super.onResume();
        syncFromStore();
    }

    private void bindSwitch(String key) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSliderStep(getListView() != null ? getListView() : requireView());
            if (shouldWarnBeforeDisablingSocksAuth(key, preference, newValue)) {
                showWarningBeforeApplying(
                    () ->
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit()
                            .putBoolean(key, false)
                            .apply(),
                    getString(R.string.warning_socks_auth_disable)
                );
                return false;
            }
            if (
                TextUtils.equals(key, AppPrefs.KEY_XRAY_LOCAL_PROXY_ENABLED) ||
                TextUtils.equals(key, AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED)
            ) {
                requireView().post(this::syncFromStore);
            }
            return true;
        });
    }

    private void bindSummary(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            if (shouldWarnBeforeAcceptingWeakPassword(key, newValue)) {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                showWarningBeforeApplying(
                    () ->
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit()
                            .putString(key, newValue == null ? "" : String.valueOf(newValue))
                            .apply(),
                    getString(R.string.warning_socks_password_weak)
                );
                return false;
            }
            return true;
        });
        preference.setSummaryProvider(pref -> {
            String value = ((EditTextPreference) pref).getText();
            return TextUtils.isEmpty(value) ? getString(R.string.sharing_value_auto) : value;
        });
    }

    private void bindNumeric(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
    }

    private void syncFromStore() {
        XraySettings settings = XrayStore.getXraySettings(requireContext());
        syncEditText(AppPrefs.KEY_XRAY_REMOTE_DNS, settings.remoteDns);
        syncEditText(AppPrefs.KEY_XRAY_DIRECT_DNS, settings.directDns);
        syncEditText(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT, String.valueOf(settings.localProxyPort));
        syncEditText(AppPrefs.KEY_XRAY_LOCAL_PROXY_USERNAME, settings.localProxyUsername);
        syncEditText(AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD, settings.localProxyPassword);
        syncSwitch(AppPrefs.KEY_XRAY_ALLOW_LAN, settings.allowLan);
        syncSwitch(AppPrefs.KEY_XRAY_ALLOW_INSECURE, settings.allowInsecure);
        syncSwitch(AppPrefs.KEY_XRAY_LOCAL_PROXY_ENABLED, settings.localProxyEnabled);
        syncSwitch(AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED, settings.localProxyAuthEnabled);
        syncSwitch(AppPrefs.KEY_XRAY_IPV6_ENABLED, settings.ipv6);
        syncSwitch(AppPrefs.KEY_XRAY_SNIFFING_ENABLED, settings.sniffingEnabled);
        syncSwitch(AppPrefs.KEY_XRAY_PROXY_QUIC_ENABLED, settings.proxyQuicEnabled);
        syncSwitch(AppPrefs.KEY_XRAY_RESTART_ON_NETWORK_CHANGE, settings.restartOnNetworkChange);
        syncRoutingSummary();
        refreshLocalProxyVisibility(settings);
    }

    private void syncEditText(String key, String value) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        String normalized = value == null ? "" : value;
        if (!TextUtils.equals(preference.getText(), normalized)) {
            preference.setText(normalized);
        }
    }

    private void syncSwitch(String key, boolean value) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference != null && preference.isChecked() != value) {
            preference.setChecked(value);
        }
    }

    private void refreshLocalProxyVisibility(XraySettings settings) {
        boolean proxyEnabled = settings.localProxyEnabled;
        boolean authEnabled = proxyEnabled && settings.localProxyAuthEnabled;
        setPreferenceEnabled(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT, proxyEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED, proxyEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_LOCAL_PROXY_USERNAME, authEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD, authEnabled);
    }

    private void setPreferenceEnabled(String key, boolean enabled) {
        androidx.preference.Preference preference = findPreference(key);
        if (preference != null) {
            preference.setEnabled(enabled);
        }
    }

    private void bindRoutingEntry() {
        Preference preference = findPreference("pref_xray_routing_open");
        if (preference != null) {
            preference.setOnPreferenceClickListener(clickedPreference -> {
                startActivity(XrayRoutingSettingsActivity.createIntent(requireContext()));
                return true;
            });
        }
    }

    private void syncRoutingSummary() {
        Preference preference = findPreference("pref_xray_routing_open");
        if (preference != null) {
            preference.setSummary(R.string.xray_settings_routing_summary);
        }
    }

    private boolean shouldWarnBeforeDisablingSocksAuth(String key, SwitchPreferenceCompat preference, Object newValue) {
        return (
            TextUtils.equals(key, AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED) &&
            preference.isChecked() &&
            Boolean.FALSE.equals(newValue)
        );
    }

    private boolean shouldWarnBeforeAcceptingWeakPassword(String key, Object newValue) {
        if (!TextUtils.equals(key, AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD)) {
            return false;
        }
        XraySettings settings = XrayStore.getXraySettings(requireContext());
        if (!settings.localProxyEnabled || !settings.localProxyAuthEnabled) {
            return false;
        }
        String candidatePassword = newValue == null ? "" : String.valueOf(newValue);
        return SocksAuthSecurity.isPasswordTooSimple(settings.localProxyUsername, candidatePassword);
    }

    private void showWarningBeforeApplying(Runnable action, String warningText) {
        pendingWarningConfirmedAction = action;
        warningLauncher.launch(
            WarningConfirmActivity.createIntent(requireContext(), warningText, SOCKS_AUTH_DISABLE_WARNING_DELAY_SECONDS)
        );
    }
}
