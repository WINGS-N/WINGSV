package wings.v.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import wings.v.R;
import wings.v.core.AmneziaStore;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
    }
)
public class AmneziaSettingsFragment extends PreferenceFragmentCompat {

    private boolean suppressPreferenceSync;
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        AppPrefs.ensureDefaults(requireContext());
        setPreferencesFromResource(R.xml.amnezia_preferences, rootKey);
        configurePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerPreferencesListener();
        AmneziaStore.maybeBackfillStructuredPrefs(requireContext());
        syncFromPrefs();
    }

    @Override
    public void onPause() {
        unregisterPreferencesListener();
        super.onPause();
    }

    private void configurePreferences() {
        bindRawConfigPreference();
        bindImportFromClipboardPreference();
        bindNumeric(AmneziaStore.KEY_INTERFACE_LISTEN_PORT);
        bindNumeric(AmneziaStore.KEY_INTERFACE_MTU);
        bindNumeric(AmneziaStore.KEY_INTERFACE_JC);
        bindNumeric(AmneziaStore.KEY_INTERFACE_JMIN);
        bindNumeric(AmneziaStore.KEY_INTERFACE_JMAX);
        bindNumeric(AmneziaStore.KEY_INTERFACE_S1);
        bindNumeric(AmneziaStore.KEY_INTERFACE_S2);
        bindNumeric(AmneziaStore.KEY_INTERFACE_S3);
        bindNumeric(AmneziaStore.KEY_INTERFACE_S4);
        bindNumeric(AmneziaStore.KEY_PEER_PERSISTENT_KEEPALIVE);

        bindSummary(AppPrefs.KEY_AWG_QUICK_CONFIG, true);
        bindSummary(AmneziaStore.KEY_INTERFACE_PRIVATE_KEY, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_ADDRESSES, true);
        bindSummary(AmneziaStore.KEY_INTERFACE_DNS, true);
        bindSummary(AmneziaStore.KEY_INTERFACE_LISTEN_PORT, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_MTU, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_JC, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_JMIN, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_JMAX, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_S1, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_S2, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_S3, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_S4, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_H1, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_H2, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_H3, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_H4, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_I1, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_I2, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_I3, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_I4, false);
        bindSummary(AmneziaStore.KEY_INTERFACE_I5, false);
        bindSummary(AmneziaStore.KEY_PEER_PUBLIC_KEY, false);
        bindSummary(AmneziaStore.KEY_PEER_PRESHARED_KEY, false);
        bindSummary(AmneziaStore.KEY_PEER_ALLOWED_IPS, true);
        bindSummary(AmneziaStore.KEY_PEER_ENDPOINT, false);
        bindSummary(AmneziaStore.KEY_PEER_PERSISTENT_KEEPALIVE, false);

        syncFromPrefs();
    }

    private void bindImportFromClipboardPreference() {
        Preference preference = findPreference(AmneziaStore.KEY_IMPORT_FROM_CLIPBOARD);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceClickListener(clickedPreference -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            ClipboardManager clipboardManager = (ClipboardManager) requireContext().getSystemService(
                Context.CLIPBOARD_SERVICE
            );
            if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
                Toast.makeText(requireContext(), R.string.awg_settings_clipboard_empty, Toast.LENGTH_SHORT).show();
                return true;
            }
            ClipData clipData = clipboardManager.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) {
                Toast.makeText(requireContext(), R.string.awg_settings_clipboard_empty, Toast.LENGTH_SHORT).show();
                return true;
            }
            CharSequence text = clipData.getItemAt(0).coerceToText(requireContext());
            String rawConfig = text == null ? "" : text.toString();
            if (TextUtils.isEmpty(rawConfig.trim())) {
                Toast.makeText(requireContext(), R.string.awg_settings_clipboard_empty, Toast.LENGTH_SHORT).show();
                return true;
            }
            try {
                suppressPreferenceSync = true;
                AmneziaStore.applyRawConfig(requireContext(), rawConfig);
                syncFromPrefs();
                Toast.makeText(
                    requireContext(),
                    R.string.awg_settings_clipboard_import_success,
                    Toast.LENGTH_SHORT
                ).show();
            } catch (Exception error) {
                Toast.makeText(
                    requireContext(),
                    R.string.awg_settings_clipboard_import_invalid,
                    Toast.LENGTH_SHORT
                ).show();
            } finally {
                suppressPreferenceSync = false;
            }
            return true;
        });
    }

    private void bindRawConfigPreference() {
        EditTextPreference preference = findPreference(AppPrefs.KEY_AWG_QUICK_CONFIG);
        if (preference == null) {
            return;
        }
        preference.setOnBindEditTextListener(editText ->
            editText.setInputType(
                InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            )
        );
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            String rawConfig = newValue instanceof String ? (String) newValue : "";
            try {
                suppressPreferenceSync = true;
                AmneziaStore.applyRawConfig(requireContext(), rawConfig);
                syncFromPrefs();
                return false;
            } catch (Exception error) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.awg_settings_raw_apply_failed, error.getMessage()),
                    Toast.LENGTH_SHORT
                ).show();
                return false;
            } finally {
                suppressPreferenceSync = false;
            }
        });
    }

    private void bindNumeric(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
    }

    private void bindSummary(String key, boolean multiline) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        if (multiline) {
            preference.setOnBindEditTextListener(editText ->
                editText.setInputType(
                    InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                )
            );
        }
        preference.setSummaryProvider(pref -> {
            String value = ((EditTextPreference) pref).getText();
            return TextUtils.isEmpty(value) ? getString(R.string.awg_settings_value_not_set) : value;
        });
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
            if (suppressPreferenceSync || TextUtils.isEmpty(key)) {
                return;
            }
            if (!AmneziaStore.isStructuredPreferenceKey(key)) {
                return;
            }
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            suppressPreferenceSync = true;
            try {
                AmneziaStore.syncRawConfigFromStructuredPrefs(requireContext());
                syncFromPrefs();
            } finally {
                suppressPreferenceSync = false;
            }
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
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        if (prefs == null) {
            return;
        }
        boolean previousSuppressState = suppressPreferenceSync;
        suppressPreferenceSync = true;
        try {
            syncEditText(AppPrefs.KEY_AWG_QUICK_CONFIG, prefs.getString(AppPrefs.KEY_AWG_QUICK_CONFIG, ""));
            syncEditText(
                AmneziaStore.KEY_INTERFACE_PRIVATE_KEY,
                prefs.getString(AmneziaStore.KEY_INTERFACE_PRIVATE_KEY, "")
            );
            syncEditText(
                AmneziaStore.KEY_INTERFACE_ADDRESSES,
                prefs.getString(AmneziaStore.KEY_INTERFACE_ADDRESSES, "")
            );
            syncEditText(AmneziaStore.KEY_INTERFACE_DNS, prefs.getString(AmneziaStore.KEY_INTERFACE_DNS, ""));
            syncEditText(
                AmneziaStore.KEY_INTERFACE_LISTEN_PORT,
                prefs.getString(AmneziaStore.KEY_INTERFACE_LISTEN_PORT, "")
            );
            syncEditText(AmneziaStore.KEY_INTERFACE_MTU, prefs.getString(AmneziaStore.KEY_INTERFACE_MTU, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_JC, prefs.getString(AmneziaStore.KEY_INTERFACE_JC, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_JMIN, prefs.getString(AmneziaStore.KEY_INTERFACE_JMIN, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_JMAX, prefs.getString(AmneziaStore.KEY_INTERFACE_JMAX, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_S1, prefs.getString(AmneziaStore.KEY_INTERFACE_S1, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_S2, prefs.getString(AmneziaStore.KEY_INTERFACE_S2, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_S3, prefs.getString(AmneziaStore.KEY_INTERFACE_S3, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_S4, prefs.getString(AmneziaStore.KEY_INTERFACE_S4, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_H1, prefs.getString(AmneziaStore.KEY_INTERFACE_H1, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_H2, prefs.getString(AmneziaStore.KEY_INTERFACE_H2, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_H3, prefs.getString(AmneziaStore.KEY_INTERFACE_H3, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_H4, prefs.getString(AmneziaStore.KEY_INTERFACE_H4, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_I1, prefs.getString(AmneziaStore.KEY_INTERFACE_I1, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_I2, prefs.getString(AmneziaStore.KEY_INTERFACE_I2, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_I3, prefs.getString(AmneziaStore.KEY_INTERFACE_I3, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_I4, prefs.getString(AmneziaStore.KEY_INTERFACE_I4, ""));
            syncEditText(AmneziaStore.KEY_INTERFACE_I5, prefs.getString(AmneziaStore.KEY_INTERFACE_I5, ""));
            syncEditText(AmneziaStore.KEY_PEER_PUBLIC_KEY, prefs.getString(AmneziaStore.KEY_PEER_PUBLIC_KEY, ""));
            syncEditText(AmneziaStore.KEY_PEER_PRESHARED_KEY, prefs.getString(AmneziaStore.KEY_PEER_PRESHARED_KEY, ""));
            syncEditText(AmneziaStore.KEY_PEER_ALLOWED_IPS, prefs.getString(AmneziaStore.KEY_PEER_ALLOWED_IPS, ""));
            syncEditText(AmneziaStore.KEY_PEER_ENDPOINT, prefs.getString(AmneziaStore.KEY_PEER_ENDPOINT, ""));
            syncEditText(
                AmneziaStore.KEY_PEER_PERSISTENT_KEEPALIVE,
                prefs.getString(AmneziaStore.KEY_PEER_PERSISTENT_KEEPALIVE, "")
            );
        } finally {
            suppressPreferenceSync = previousSuppressState;
        }
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
}
