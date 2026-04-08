package wings.v.ui;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.XraySettings;
import wings.v.core.XrayStore;

public class XraySettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.xray_preferences, rootKey);
        bindSwitch(AppPrefs.KEY_XRAY_ALLOW_LAN);
        bindSwitch(AppPrefs.KEY_XRAY_ALLOW_INSECURE);
        bindSwitch(AppPrefs.KEY_XRAY_LOCAL_PROXY_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_IPV6_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_SNIFFING_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_RESTART_ON_NETWORK_CHANGE);
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
        syncSwitch(AppPrefs.KEY_XRAY_RESTART_ON_NETWORK_CHANGE, settings.restartOnNetworkChange);
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
}
