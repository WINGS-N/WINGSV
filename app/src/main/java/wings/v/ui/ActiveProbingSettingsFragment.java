package wings.v.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import wings.v.ActiveProbingTargetsActivity;
import wings.v.R;
import wings.v.core.ActiveProbingBackgroundScheduler;
import wings.v.core.ActiveProbingManager;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;

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
public class ActiveProbingSettingsFragment extends PreferenceFragmentCompat {

    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        AppPrefs.ensureDefaults(requireContext());
        setPreferencesFromResource(R.xml.active_probing_preferences, rootKey);
        configurePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerPreferencesListener();
        syncFromPrefs();
        refreshAvailability();
        ActiveProbingBackgroundScheduler.refresh(requireContext());
    }

    @Override
    public void onPause() {
        unregisterPreferencesListener();
        super.onPause();
    }

    private void configurePreferences() {
        bindSwitch(ActiveProbingManager.KEY_TUNNEL_ENABLED);
        bindSwitch(ActiveProbingManager.KEY_VK_TURN_ENABLED);
        bindSwitch(ActiveProbingManager.KEY_BACKGROUND_ENABLED);
        bindList(ActiveProbingManager.KEY_XRAY_FALLBACK_BACKEND);
        bindNumeric(ActiveProbingManager.KEY_INTERVAL_SECONDS);
        bindNumeric(ActiveProbingManager.KEY_TIMEOUT_SECONDS);
        bindTargetsPreference();
        syncFromPrefs();
        refreshAvailability();
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

    private void bindNumeric(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
        preference.setSummaryProvider(pref -> {
            String value = ((EditTextPreference) pref).getText();
            return TextUtils.isEmpty(value) ? "Не задано" : value;
        });
    }

    private void bindTargetsPreference() {
        Preference preference = findPreference(ActiveProbingManager.KEY_OPEN_TARGETS);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceClickListener(clickedPreference -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            startActivity(ActiveProbingTargetsActivity.createIntent(requireContext()));
            return true;
        });
    }

    private void bindList(String key) {
        ListPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            return true;
        });
    }

    private void refreshAvailability() {
        SwitchPreferenceCompat tunnelPreference = findPreference(ActiveProbingManager.KEY_TUNNEL_ENABLED);
        if (tunnelPreference == null) {
            return;
        }
        boolean available = ActiveProbingManager.isTunnelProbingAvailable(requireContext());
        tunnelPreference.setEnabled(available);
        tunnelPreference.setSummary(
            available
                ? getString(R.string.active_probing_tunnel_summary)
                : getString(R.string.active_probing_tunnel_xray_only)
        );
    }

    private void registerPreferencesListener() {
        if (preferencesChangeListener != null) {
            return;
        }
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (sharedPreferences == null) {
            return;
        }
        preferencesChangeListener = (prefs, key) -> {
            if (!isAdded()) {
                return;
            }
            syncFromPrefs();
            refreshAvailability();
            ActiveProbingBackgroundScheduler.refresh(requireContext());
        };
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferencesChangeListener);
    }

    private void unregisterPreferencesListener() {
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (sharedPreferences != null && preferencesChangeListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferencesChangeListener);
        }
        preferencesChangeListener = null;
    }

    private void syncFromPrefs() {
        ActiveProbingManager.Settings settings = ActiveProbingManager.getSettings(requireContext());
        syncSwitch(ActiveProbingManager.KEY_TUNNEL_ENABLED, settings.tunnelEnabled);
        syncSwitch(ActiveProbingManager.KEY_VK_TURN_ENABLED, settings.vkTurnEnabled);
        syncSwitch(ActiveProbingManager.KEY_BACKGROUND_ENABLED, settings.backgroundEnabled);
        syncList(ActiveProbingManager.KEY_XRAY_FALLBACK_BACKEND, settings.xrayFallbackBackend.prefValue);
        syncTargetsPreference(settings.rawUrls);
        syncEditText(ActiveProbingManager.KEY_INTERVAL_SECONDS, String.valueOf(settings.intervalSeconds));
        syncEditText(ActiveProbingManager.KEY_TIMEOUT_SECONDS, String.valueOf(settings.timeoutSeconds));
    }

    private void syncSwitch(String key, boolean checked) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference == null || preference.isChecked() == checked) {
            return;
        }
        preference.setChecked(checked);
    }

    private void syncEditText(String key, @Nullable String value) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        String normalized = value == null ? "" : value;
        if (TextUtils.equals(preference.getText(), normalized)) {
            return;
        }
        preference.setText(normalized);
    }

    private void syncList(String key, @Nullable String value) {
        ListPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        String normalized = TextUtils.isEmpty(value) ? BackendType.VK_TURN_WIREGUARD.prefValue : value;
        if (TextUtils.equals(preference.getValue(), normalized)) {
            return;
        }
        preference.setValue(normalized);
    }

    private void syncTargetsPreference(@Nullable String rawValue) {
        Preference preference = findPreference(ActiveProbingManager.KEY_OPEN_TARGETS);
        if (preference == null) {
            return;
        }
        preference.setSummary(ActiveProbingManager.buildUrlsSummary(rawValue));
    }
}
