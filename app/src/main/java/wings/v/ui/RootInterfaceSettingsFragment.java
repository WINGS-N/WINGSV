package wings.v.ui;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.UiFormatter;

public class RootInterfaceSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.root_interface_preferences, rootKey);
        bindWireGuardInterfacePreference();
        updateSummaries();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSummaries();
    }

    private void bindWireGuardInterfacePreference() {
        bindInterfacePreference(
            AppPrefs.KEY_ROOT_WIREGUARD_INTERFACE_NAME,
            AppPrefs::isValidRootWireGuardInterfaceNameTemplate,
            AppPrefs::normalizeRootWireGuardInterfaceNameTemplate
        );
    }

    private void bindInterfacePreference(
        String key,
        InterfaceNameValidator validator,
        InterfaceNameNormalizer normalizer
    ) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnBindEditTextListener(editText -> {
            editText.setSingleLine(true);
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        });
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            String rawValue = newValue == null ? "" : String.valueOf(newValue);
            if (TextUtils.isEmpty(rawValue.trim())) {
                String normalizedDefault = normalizer.normalize("");
                PreferenceManager.getDefaultSharedPreferences(requireContext().getApplicationContext())
                    .edit()
                    .putString(key, normalizedDefault)
                    .apply();
                preference.setText(normalizedDefault);
                updateSummaries();
                return false;
            }
            if (!validator.isValid(rawValue)) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.root_interface_invalid_wireguard_name),
                    Toast.LENGTH_SHORT
                ).show();
                return false;
            }
            String normalized = normalizer.normalize(rawValue);
            if (!TextUtils.equals(normalized, rawValue)) {
                PreferenceManager.getDefaultSharedPreferences(requireContext().getApplicationContext())
                    .edit()
                    .putString(key, normalized)
                    .apply();
                preference.setText(normalized);
                updateSummaries();
                return false;
            }
            updateSummaries();
            return true;
        });
    }

    private void updateSummaries() {
        updateWireGuardSummary();
    }

    private void updateWireGuardSummary() {
        EditTextPreference preference = findPreference(AppPrefs.KEY_ROOT_WIREGUARD_INTERFACE_NAME);
        if (preference == null) {
            return;
        }
        String value = AppPrefs.normalizeRootWireGuardInterfaceNameTemplate(preference.getText());
        preference.setSummary(
            getString(R.string.root_interface_wireguard_summary_value, UiFormatter.truncate(value, 32))
        );
    }

    @FunctionalInterface
    private interface InterfaceNameValidator {
        boolean isValid(String value);
    }

    @FunctionalInterface
    private interface InterfaceNameNormalizer {
        String normalize(String value);
    }
}
