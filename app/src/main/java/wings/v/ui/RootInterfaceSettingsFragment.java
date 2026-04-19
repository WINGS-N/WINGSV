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
import wings.v.service.ProxyTunnelService;

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
                    .commit();
                preference.setText(normalizedDefault);
                updateWireGuardSummary(normalizedDefault);
                requestRuntimeReconnectIfActive();
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
                    .commit();
                preference.setText(normalized);
                updateWireGuardSummary(normalized);
                requestRuntimeReconnectIfActive();
                return false;
            }
            updateWireGuardSummary(normalized);
            requestRuntimeReconnectAfterPersist();
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
        updateWireGuardSummary(preference.getText());
    }

    private void updateWireGuardSummary(String value) {
        EditTextPreference preference = findPreference(AppPrefs.KEY_ROOT_WIREGUARD_INTERFACE_NAME);
        if (preference == null) {
            return;
        }
        String normalizedValue = AppPrefs.normalizeRootWireGuardInterfaceNameTemplate(value);
        preference.setSummary(
            getString(R.string.root_interface_wireguard_summary_value, UiFormatter.truncate(normalizedValue, 32))
        );
    }

    private void requestRuntimeReconnectIfActive() {
        if (
            !ProxyTunnelService.isActive() ||
            !AppPrefs.isRootModeEnabled(requireContext()) ||
            !AppPrefs.isKernelWireGuardEnabled(requireContext())
        ) {
            return;
        }
        ProxyTunnelService.requestReconnect(
            requireContext().getApplicationContext(),
            "Root interface settings changed"
        );
    }

    private void requestRuntimeReconnectAfterPersist() {
        android.view.View view = getView();
        if (view != null) {
            view.post(this::requestRuntimeReconnectIfActive);
            return;
        }
        requestRuntimeReconnectIfActive();
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
