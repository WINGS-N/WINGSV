package wings.v.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import wings.v.PermissionOnboardingActivity;
import wings.v.AboutAppActivity;
import wings.v.ProxyLogsActivity;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.RootUtils;
import wings.v.core.UiFormatter;
import wings.v.core.ProxySettings;

public class SettingsFragment extends PreferenceFragmentCompat {
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        AppPrefs.ensureDefaults(requireContext());
        setPreferencesFromResource(R.xml.proxy_preferences, rootKey);
        configurePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerPreferencesListener();
        syncPreferenceValuesFromPrefs();
        configureRootPreferences();
    }

    @Override
    public void onPause() {
        unregisterPreferencesListener();
        super.onPause();
    }

    private void configurePreferences() {
        bindNumericPreference(AppPrefs.KEY_THREADS);
        bindNumericPreference(AppPrefs.KEY_WG_MTU);
        bindListPreference(AppPrefs.KEY_TURN_SESSION_MODE);

        bindSummaryPreference(AppPrefs.KEY_ENDPOINT);
        bindSummaryPreference(AppPrefs.KEY_VK_LINK);
        bindSummaryPreference(AppPrefs.KEY_LOCAL_ENDPOINT);
        bindSummaryPreference(AppPrefs.KEY_TURN_HOST);
        bindSummaryPreference(AppPrefs.KEY_TURN_PORT);
        bindSummaryPreference(AppPrefs.KEY_WG_ADDRESSES);
        bindSummaryPreference(AppPrefs.KEY_WG_DNS);
        bindSummaryPreference(AppPrefs.KEY_WG_ALLOWED_IPS);
        bindSwitchHaptics(AppPrefs.KEY_USE_UDP);
        bindSwitchHaptics(AppPrefs.KEY_NO_OBFUSCATION);
        bindSwitchHaptics(AppPrefs.KEY_AUTO_START_ON_BOOT);

        bindSecretPreference(AppPrefs.KEY_WG_PRIVATE_KEY);
        bindSecretPreference(AppPrefs.KEY_WG_PUBLIC_KEY);
        bindSecretPreference(AppPrefs.KEY_WG_PRESHARED_KEY);

        makeMultiLine(AppPrefs.KEY_VK_LINK);
        makeMultiLine(AppPrefs.KEY_WG_PRIVATE_KEY);
        makeMultiLine(AppPrefs.KEY_WG_PUBLIC_KEY);
        makeMultiLine(AppPrefs.KEY_WG_PRESHARED_KEY);
        makeMultiLine(AppPrefs.KEY_WG_ADDRESSES);
        makeMultiLine(AppPrefs.KEY_WG_DNS);
        makeMultiLine(AppPrefs.KEY_WG_ALLOWED_IPS);

        Preference permissionsPreference = findPreference("pref_open_permissions");
        if (permissionsPreference != null) {
            permissionsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(PermissionOnboardingActivity.createIntent(requireContext(), true));
                return true;
            });
        }

        Preference proxyLogsPreference = findPreference("pref_open_proxy_logs");
        if (proxyLogsPreference != null) {
            proxyLogsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(ProxyLogsActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference aboutPreference = findPreference("pref_open_about");
        if (aboutPreference != null) {
            aboutPreference.setIcon(R.drawable.ic_about_app_info);
            aboutPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(AboutAppActivity.createIntent(requireContext()));
                return true;
            });
        }

        configureRootPreferences();
        syncPreferenceValuesFromPrefs();
    }

    private void configureRootPreferences() {
        PreferenceCategory rootCategory = findPreference("pref_category_root");
        SwitchPreferenceCompat rootModePreference = findPreference(AppPrefs.KEY_ROOT_MODE);
        boolean rootGranted = AppPrefs.isRootAccessGranted(requireContext());
        if (rootCategory != null) {
            rootCategory.setVisible(rootGranted);
        }
        if (rootModePreference == null) {
            return;
        }
        if (!rootGranted) {
            rootModePreference.setVisible(false);
            return;
        }

        rootModePreference.setVisible(true);
        String unavailableReason = RootUtils.getRootModeUnavailableReason(requireContext());
        boolean supported = TextUtils.isEmpty(unavailableReason);
        rootModePreference.setEnabled(supported);
        rootModePreference.setSummary(supported
                ? getString(R.string.root_mode_summary)
                : getString(R.string.root_mode_unavailable, unavailableReason));
        rootModePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            Haptics.softSliderStep(getListView() != null ? getListView() : requireView());
            if (!(newValue instanceof Boolean) || !((Boolean) newValue)) {
                return true;
            }
            String reason = RootUtils.getRootModeUnavailableReason(requireContext());
            if (!TextUtils.isEmpty(reason)) {
                Toast.makeText(
                        requireContext(),
                        getString(R.string.root_mode_unavailable, reason),
                        Toast.LENGTH_SHORT
                ).show();
                return false;
            }
            return true;
        });
    }

    private void bindSwitchHaptics(String key) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSliderStep(getListView() != null ? getListView() : requireView());
            return true;
        });
    }

    private void bindSummaryPreference(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummaryProvider(pref -> {
            String value = ((EditTextPreference) pref).getText();
            return TextUtils.isEmpty(value) ? "Не задано" : UiFormatter.truncate(value, 64);
        });
    }

    private void bindSecretPreference(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummaryProvider(pref -> {
            String value = ((EditTextPreference) pref).getText();
            if (TextUtils.isEmpty(value)) {
                return "Не задано";
            }
            if (value.length() <= 12) {
                return value;
            }
            return value.substring(0, 6) + "…" + value.substring(value.length() - 4);
        });
    }

    private void bindNumericPreference(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER)
        );
        preference.setSummaryProvider(pref -> {
            String value = ((EditTextPreference) pref).getText();
            return TextUtils.isEmpty(value) ? "Не задано" : value;
        });
    }

    private void makeMultiLine(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnBindEditTextListener(editText -> {
            editText.setSingleLine(false);
            editText.setMinLines(3);
            editText.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        });
    }

    private void registerPreferencesListener() {
        if (preferencesChangeListener != null) {
            return;
        }
        preferencesChangeListener = (sharedPreferences, key) -> {
            if (!isAdded()) {
                return;
            }
            syncPreferenceValuesFromPrefs();
            configureRootPreferences();
        };
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(preferencesChangeListener);
    }

    private void unregisterPreferencesListener() {
        if (preferencesChangeListener == null) {
            return;
        }
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(preferencesChangeListener);
        preferencesChangeListener = null;
    }

    private void syncPreferenceValuesFromPrefs() {
        ProxySettings settings = AppPrefs.getSettings(requireContext());

        syncEditTextPreference(AppPrefs.KEY_ENDPOINT, settings.endpoint);
        syncEditTextPreference(AppPrefs.KEY_VK_LINK, settings.vkLink);
        syncEditTextPreference(AppPrefs.KEY_THREADS, String.valueOf(settings.threads));
        syncSwitchPreference(AppPrefs.KEY_USE_UDP, settings.useUdp);
        syncSwitchPreference(AppPrefs.KEY_NO_OBFUSCATION, settings.noObfuscation);
        syncListPreference(AppPrefs.KEY_TURN_SESSION_MODE, settings.turnSessionMode);
        syncEditTextPreference(AppPrefs.KEY_LOCAL_ENDPOINT, settings.localEndpoint);
        syncEditTextPreference(AppPrefs.KEY_TURN_HOST, settings.turnHost);
        syncEditTextPreference(AppPrefs.KEY_TURN_PORT, settings.turnPort);
        syncEditTextPreference(AppPrefs.KEY_WG_PRIVATE_KEY, settings.wgPrivateKey);
        syncEditTextPreference(AppPrefs.KEY_WG_ADDRESSES, settings.wgAddresses);
        syncEditTextPreference(AppPrefs.KEY_WG_DNS, settings.wgDns);
        syncEditTextPreference(AppPrefs.KEY_WG_MTU, String.valueOf(settings.wgMtu));
        syncEditTextPreference(AppPrefs.KEY_WG_PUBLIC_KEY, settings.wgPublicKey);
        syncEditTextPreference(AppPrefs.KEY_WG_PRESHARED_KEY, settings.wgPresharedKey);
        syncEditTextPreference(AppPrefs.KEY_WG_ALLOWED_IPS, settings.wgAllowedIps);
        syncSwitchPreference(AppPrefs.KEY_ROOT_MODE, AppPrefs.isRootModeEnabled(requireContext()));
        syncSwitchPreference(
                AppPrefs.KEY_AUTO_START_ON_BOOT,
                AppPrefs.isAutoStartOnBootEnabled(requireContext())
        );
    }

    private void syncEditTextPreference(String key, @Nullable String value) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        String normalizedValue = value == null ? "" : value;
        String currentValue = preference.getText();
        if (TextUtils.equals(currentValue, normalizedValue)) {
            return;
        }
        preference.setText(normalizedValue);
    }

    private void syncSwitchPreference(String key, boolean checked) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference == null || preference.isChecked() == checked) {
            return;
        }
        preference.setChecked(checked);
    }

    private void bindListPreference(String key) {
        ListPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
    }

    private void syncListPreference(String key, @Nullable String value) {
        ListPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        String normalizedValue = TextUtils.isEmpty(value) ? "auto" : value;
        if (TextUtils.equals(preference.getValue(), normalizedValue)) {
            return;
        }
        preference.setValue(normalizedValue);
    }
}
