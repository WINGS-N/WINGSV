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
        bindSwitch(AppPrefs.KEY_XRAY_IPV6_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_SNIFFING_ENABLED);
        bindSummary(AppPrefs.KEY_XRAY_REMOTE_DNS);
        bindSummary(AppPrefs.KEY_XRAY_DIRECT_DNS);
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
        preference.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER)
        );
    }

    private void syncFromStore() {
        XraySettings settings = XrayStore.getXraySettings(requireContext());
        syncEditText(AppPrefs.KEY_XRAY_REMOTE_DNS, settings.remoteDns);
        syncEditText(AppPrefs.KEY_XRAY_DIRECT_DNS, settings.directDns);
        syncEditText(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT, String.valueOf(settings.localProxyPort));
        syncSwitch(AppPrefs.KEY_XRAY_ALLOW_LAN, settings.allowLan);
        syncSwitch(AppPrefs.KEY_XRAY_ALLOW_INSECURE, settings.allowInsecure);
        syncSwitch(AppPrefs.KEY_XRAY_IPV6_ENABLED, settings.ipv6);
        syncSwitch(AppPrefs.KEY_XRAY_SNIFFING_ENABLED, settings.sniffingEnabled);
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
}
