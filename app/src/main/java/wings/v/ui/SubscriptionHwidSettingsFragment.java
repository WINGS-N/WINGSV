package wings.v.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.SubscriptionHwidStore;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
    }
)
public class SubscriptionHwidSettingsFragment extends PreferenceFragmentCompat {

    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        AppPrefs.ensureDefaults(requireContext());
        setPreferencesFromResource(R.xml.subscription_hwid_preferences, rootKey);
        configurePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerPreferencesListener();
        syncFromPrefs();
        refreshAvailability();
    }

    @Override
    public void onPause() {
        unregisterPreferencesListener();
        super.onPause();
    }

    private void configurePreferences() {
        bindSwitch(AppPrefs.KEY_SUBSCRIPTION_HWID_ENABLED);
        bindSwitch(AppPrefs.KEY_SUBSCRIPTION_HWID_MANUAL_ENABLED);
        bindValueField(AppPrefs.KEY_SUBSCRIPTION_HWID_VALUE);
        bindValueField(AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_OS);
        bindValueField(AppPrefs.KEY_SUBSCRIPTION_HWID_VER_OS);
        bindValueField(AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_MODEL);
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

    private void bindValueField(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnBindEditTextListener(editText ->
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        );
    }

    private CharSequence buildValueSummary(String key) {
        if (!isAdded()) {
            return "";
        }
        boolean manualValues = getPreferenceManager()
            .getSharedPreferences()
            .getBoolean(AppPrefs.KEY_SUBSCRIPTION_HWID_MANUAL_ENABLED, false);
        String displayedValue = SubscriptionHwidStore.getDisplayedValue(requireContext(), key);
        if (TextUtils.isEmpty(displayedValue)) {
            return getString(R.string.subscription_hwid_value_not_set);
        }
        if (manualValues) {
            return displayedValue;
        }
        return getString(R.string.subscription_hwid_auto_value_summary, displayedValue);
    }

    private void registerPreferencesListener() {
        if (preferencesChangeListener != null) {
            return;
        }
        SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
        if (preferences == null) {
            return;
        }
        preferencesChangeListener = (prefs, key) -> {
            if (!isAdded()) {
                return;
            }
            syncFromPrefs();
            refreshAvailability();
        };
        preferences.registerOnSharedPreferenceChangeListener(preferencesChangeListener);
    }

    private void unregisterPreferencesListener() {
        SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
        if (preferences != null && preferencesChangeListener != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferencesChangeListener);
        }
        preferencesChangeListener = null;
    }

    private void syncFromPrefs() {
        SubscriptionHwidStore.SettingsModel settings = SubscriptionHwidStore.getSettings(requireContext());
        syncSwitch(AppPrefs.KEY_SUBSCRIPTION_HWID_ENABLED, settings.enabled);
        syncSwitch(AppPrefs.KEY_SUBSCRIPTION_HWID_MANUAL_ENABLED, settings.manualValues);
        syncText(AppPrefs.KEY_SUBSCRIPTION_HWID_VALUE, settings.hwid);
        syncText(AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_OS, settings.deviceOs);
        syncText(AppPrefs.KEY_SUBSCRIPTION_HWID_VER_OS, settings.verOs);
        syncText(AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_MODEL, settings.deviceModel);
        updateSummary(AppPrefs.KEY_SUBSCRIPTION_HWID_VALUE);
        updateSummary(AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_OS);
        updateSummary(AppPrefs.KEY_SUBSCRIPTION_HWID_VER_OS);
        updateSummary(AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_MODEL);
    }

    private void refreshAvailability() {
        boolean manualValues = getPreferenceManager()
            .getSharedPreferences()
            .getBoolean(AppPrefs.KEY_SUBSCRIPTION_HWID_MANUAL_ENABLED, false);
        setEnabled(AppPrefs.KEY_SUBSCRIPTION_HWID_VALUE, manualValues);
        setEnabled(AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_OS, manualValues);
        setEnabled(AppPrefs.KEY_SUBSCRIPTION_HWID_VER_OS, manualValues);
        setEnabled(AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_MODEL, manualValues);
    }

    private void syncSwitch(String key, boolean value) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference != null && preference.isChecked() != value) {
            preference.setChecked(value);
        }
    }

    private void syncText(String key, String value) {
        EditTextPreference preference = findPreference(key);
        if (preference != null && !TextUtils.equals(preference.getText(), value)) {
            preference.setText(value);
        }
    }

    private void setEnabled(String key, boolean enabled) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setEnabled(enabled);
        }
    }

    private void updateSummary(String key) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setSummary(buildValueSummary(key));
        }
    }
}
