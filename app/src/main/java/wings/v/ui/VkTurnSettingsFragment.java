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
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;
import java.util.Locale;
import wings.v.R;
import wings.v.core.AmneziaStore;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.ProxySettings;
import wings.v.core.UiFormatter;
import wings.v.core.XrayStore;

public class VkTurnSettingsFragment extends PreferenceFragmentCompat {

    private static final int SECRET_PREVIEW_PLAIN_LENGTH = 12;
    private static final String[] TURN_PROXY_PREFERENCE_KEYS = {
        AppPrefs.KEY_ENDPOINT,
        AppPrefs.KEY_VK_LINK,
        AppPrefs.KEY_THREADS,
        AppPrefs.KEY_USE_UDP,
        AppPrefs.KEY_NO_OBFUSCATION,
        AppPrefs.KEY_MANUAL_CAPTCHA,
        AppPrefs.KEY_TURN_SESSION_MODE,
        AppPrefs.KEY_LOCAL_ENDPOINT,
        AppPrefs.KEY_TURN_HOST,
        AppPrefs.KEY_TURN_PORT,
    };
    private static final String[] WIREGUARD_PREFERENCE_KEYS = {
        AppPrefs.KEY_WG_PRIVATE_KEY,
        AppPrefs.KEY_WG_ADDRESSES,
        AppPrefs.KEY_WG_DNS,
        AppPrefs.KEY_WG_MTU,
        AppPrefs.KEY_WG_PUBLIC_KEY,
        AppPrefs.KEY_WG_PRESHARED_KEY,
        AppPrefs.KEY_WG_ALLOWED_IPS,
        AppPrefs.KEY_WG_ENDPOINT,
        "pref_category_wg_interface",
        "pref_inset_after_wg_interface",
        "pref_category_wg_peer",
        "pref_inset_after_wg_peer",
    };
    private static final String[] AMNEZIA_PREFERENCE_KEYS = {
        "pref_category_awg_raw",
        AmneziaStore.KEY_IMPORT_FROM_CLIPBOARD,
        AppPrefs.KEY_AWG_QUICK_CONFIG,
        AmneziaStore.KEY_INFO,
        "pref_inset_after_awg_raw",
        "pref_category_awg_interface",
        AmneziaStore.KEY_INTERFACE_PRIVATE_KEY,
        AmneziaStore.KEY_INTERFACE_ADDRESSES,
        AmneziaStore.KEY_INTERFACE_DNS,
        AmneziaStore.KEY_INTERFACE_LISTEN_PORT,
        AmneziaStore.KEY_INTERFACE_MTU,
        AmneziaStore.KEY_INTERFACE_JC,
        AmneziaStore.KEY_INTERFACE_JMIN,
        AmneziaStore.KEY_INTERFACE_JMAX,
        AmneziaStore.KEY_INTERFACE_S1,
        AmneziaStore.KEY_INTERFACE_S2,
        AmneziaStore.KEY_INTERFACE_S3,
        AmneziaStore.KEY_INTERFACE_S4,
        AmneziaStore.KEY_INTERFACE_H1,
        AmneziaStore.KEY_INTERFACE_H2,
        AmneziaStore.KEY_INTERFACE_H3,
        AmneziaStore.KEY_INTERFACE_H4,
        AmneziaStore.KEY_INTERFACE_I1,
        AmneziaStore.KEY_INTERFACE_I2,
        AmneziaStore.KEY_INTERFACE_I3,
        AmneziaStore.KEY_INTERFACE_I4,
        AmneziaStore.KEY_INTERFACE_I5,
        "pref_inset_after_awg_interface",
        "pref_category_awg_peer",
        AmneziaStore.KEY_PEER_PUBLIC_KEY,
        AmneziaStore.KEY_PEER_PRESHARED_KEY,
        AmneziaStore.KEY_PEER_ALLOWED_IPS,
        AmneziaStore.KEY_PEER_ENDPOINT,
        AmneziaStore.KEY_PEER_PERSISTENT_KEEPALIVE,
        "pref_inset_after_awg_peer",
    };

    private boolean suppressPreferenceSync;
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.vk_turn_preferences, rootKey);
        bindNumericPreference(AppPrefs.KEY_THREADS);
        bindNumericPreference(AppPrefs.KEY_WG_MTU);
        bindNumericPreference(AmneziaStore.KEY_INTERFACE_LISTEN_PORT);
        bindNumericPreference(AmneziaStore.KEY_INTERFACE_MTU);
        bindNumericPreference(AmneziaStore.KEY_INTERFACE_JC);
        bindNumericPreference(AmneziaStore.KEY_INTERFACE_JMIN);
        bindNumericPreference(AmneziaStore.KEY_INTERFACE_JMAX);
        bindNumericPreference(AmneziaStore.KEY_INTERFACE_S1);
        bindNumericPreference(AmneziaStore.KEY_INTERFACE_S2);
        bindNumericPreference(AmneziaStore.KEY_INTERFACE_S3);
        bindNumericPreference(AmneziaStore.KEY_INTERFACE_S4);
        bindNumericPreference(AmneziaStore.KEY_PEER_PERSISTENT_KEEPALIVE);
        bindListPreference(AppPrefs.KEY_TURN_SESSION_MODE);
        bindRawConfigPreference();
        bindImportFromClipboardPreference();

        bindSummaryPreference(AppPrefs.KEY_ENDPOINT);
        bindSummaryPreference(AppPrefs.KEY_VK_LINK);
        bindSummaryPreference(AppPrefs.KEY_LOCAL_ENDPOINT);
        bindSummaryPreference(AppPrefs.KEY_TURN_HOST);
        bindSummaryPreference(AppPrefs.KEY_TURN_PORT);
        bindSummaryPreference(AppPrefs.KEY_WG_ADDRESSES);
        bindSummaryPreference(AppPrefs.KEY_WG_DNS);
        bindSummaryPreference(AppPrefs.KEY_WG_ALLOWED_IPS);
        bindSummaryPreference(AppPrefs.KEY_WG_ENDPOINT);
        bindSummaryPreference(AppPrefs.KEY_AWG_QUICK_CONFIG);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_ADDRESSES);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_DNS);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_LISTEN_PORT);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_MTU);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_JC);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_JMIN);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_JMAX);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_S1);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_S2);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_S3);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_S4);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_H1);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_H2);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_H3);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_H4);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_I1);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_I2);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_I3);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_I4);
        bindSummaryPreference(AmneziaStore.KEY_INTERFACE_I5);
        bindSummaryPreference(AmneziaStore.KEY_PEER_ALLOWED_IPS);
        bindSummaryPreference(AmneziaStore.KEY_PEER_ENDPOINT);
        bindSummaryPreference(AmneziaStore.KEY_PEER_PERSISTENT_KEEPALIVE);

        bindSwitchHaptics(AppPrefs.KEY_USE_UDP);
        bindSwitchHaptics(AppPrefs.KEY_NO_OBFUSCATION);
        bindSwitchHaptics(AppPrefs.KEY_MANUAL_CAPTCHA);

        bindSecretPreference(AppPrefs.KEY_WG_PRIVATE_KEY);
        bindSecretPreference(AppPrefs.KEY_WG_PUBLIC_KEY);
        bindSecretPreference(AppPrefs.KEY_WG_PRESHARED_KEY);
        bindSecretPreference(AmneziaStore.KEY_INTERFACE_PRIVATE_KEY);
        bindSecretPreference(AmneziaStore.KEY_PEER_PUBLIC_KEY);
        bindSecretPreference(AmneziaStore.KEY_PEER_PRESHARED_KEY);

        makeMultiLine(AppPrefs.KEY_VK_LINK);
        makeMultiLine(AppPrefs.KEY_AWG_QUICK_CONFIG);
        makeMultiLine(AppPrefs.KEY_WG_PRIVATE_KEY);
        makeMultiLine(AppPrefs.KEY_WG_PUBLIC_KEY);
        makeMultiLine(AppPrefs.KEY_WG_PRESHARED_KEY);
        makeMultiLine(AppPrefs.KEY_WG_ADDRESSES);
        makeMultiLine(AppPrefs.KEY_WG_DNS);
        makeMultiLine(AppPrefs.KEY_WG_ALLOWED_IPS);
        makeMultiLine(AmneziaStore.KEY_INTERFACE_PRIVATE_KEY);
        makeMultiLine(AmneziaStore.KEY_INTERFACE_ADDRESSES);
        makeMultiLine(AmneziaStore.KEY_INTERFACE_DNS);
        makeMultiLine(AmneziaStore.KEY_PEER_PUBLIC_KEY);
        makeMultiLine(AmneziaStore.KEY_PEER_PRESHARED_KEY);
        makeMultiLine(AmneziaStore.KEY_PEER_ALLOWED_IPS);

        syncFromStore();
        refreshBackendSections();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerPreferencesListener();
        AmneziaStore.maybeBackfillStructuredPrefs(requireContext());
        syncFromStore();
        refreshBackendSections();
    }

    @Override
    public void onPause() {
        unregisterPreferencesListener();
        super.onPause();
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
            if (value.length() <= SECRET_PREVIEW_PLAIN_LENGTH) {
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
        preference.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
        preference.setSummaryProvider(pref -> {
            String value = ((EditTextPreference) pref).getText();
            return TextUtils.isEmpty(value) ? "Не задано" : value;
        });
    }

    private void bindListPreference(String key) {
        ListPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            if (AppPrefs.KEY_TURN_SESSION_MODE.equals(key)) {
                String normalizedValue = normalizeTurnSessionMode(newValue);
                PreferenceManager.getDefaultSharedPreferences(requireContext().getApplicationContext())
                    .edit()
                    .putString(key, normalizedValue)
                    .apply();
                if (!TextUtils.equals(preference.getValue(), normalizedValue)) {
                    preference.setValue(normalizedValue);
                }
                return false;
            }
            return true;
        });
    }

    private void bindRawConfigPreference() {
        EditTextPreference preference = findPreference(AppPrefs.KEY_AWG_QUICK_CONFIG);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            String rawConfig = newValue instanceof String ? (String) newValue : "";
            try {
                suppressPreferenceSync = true;
                AmneziaStore.applyRawConfig(requireContext(), rawConfig);
                syncFromStore();
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
                syncFromStore();
                Toast.makeText(
                    requireContext(),
                    R.string.awg_settings_clipboard_import_success,
                    Toast.LENGTH_SHORT
                ).show();
            } catch (Exception ignored) {
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

    private String normalizeTurnSessionMode(Object value) {
        String normalizedValue = value == null ? "auto" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("mainline".equals(normalizedValue) || "mux".equals(normalizedValue)) {
            return normalizedValue;
        }
        return "auto";
    }

    private void makeMultiLine(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnBindEditTextListener(editText -> {
            editText.setSingleLine(false);
            editText.setMinLines(3);
            editText.setInputType(
                InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            );
        });
    }

    private void syncFromStore() {
        ProxySettings settings = AppPrefs.getSettings(requireContext());

        syncEditTextPreference(AppPrefs.KEY_ENDPOINT, settings.endpoint);
        syncEditTextPreference(AppPrefs.KEY_VK_LINK, settings.vkLink);
        syncEditTextPreference(AppPrefs.KEY_THREADS, String.valueOf(settings.threads));
        syncSwitchPreference(AppPrefs.KEY_USE_UDP, settings.useUdp);
        syncSwitchPreference(AppPrefs.KEY_NO_OBFUSCATION, settings.noObfuscation);
        syncSwitchPreference(AppPrefs.KEY_MANUAL_CAPTCHA, settings.manualCaptcha);
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
        syncEditTextPreference(AppPrefs.KEY_WG_ENDPOINT, AppPrefs.getWireGuardEndpoint(requireContext()));
        syncAmneziaStructuredPrefs();
    }

    private void refreshBackendSections() {
        BackendType backendType = XrayStore.getBackendType(requireContext());
        boolean turnBackend = backendType.usesTurnProxy();
        boolean wireGuardBackend = backendType.usesWireGuardSettings();
        boolean awgBackend = backendType.usesAmneziaSettings();

        setPreferenceVisible("pref_category_vk_proxy", turnBackend);
        setPreferenceVisible("pref_inset_after_vk_proxy", turnBackend);
        Preference proxyCategory = findPreference("pref_category_vk_proxy");
        if (proxyCategory != null) {
            proxyCategory.setTitle("Proxy");
        }
        setPreferencesVisible(TURN_PROXY_PREFERENCE_KEYS, turnBackend);
        setPreferencesVisible(WIREGUARD_PREFERENCE_KEYS, wireGuardBackend);
        setPreferencesVisible(AMNEZIA_PREFERENCE_KEYS, awgBackend);
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
                syncFromStore();
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

    private void setPreferenceVisible(String key, boolean visible) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setVisible(visible);
        }
    }

    private void setPreferencesVisible(String[] keys, boolean visible) {
        for (String key : keys) {
            setPreferenceVisible(key, visible);
        }
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

    private void syncAmneziaStructuredPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
            requireContext().getApplicationContext()
        );
        syncEditTextPreference(AppPrefs.KEY_AWG_QUICK_CONFIG, prefs.getString(AppPrefs.KEY_AWG_QUICK_CONFIG, ""));
        syncEditTextPreference(
            AmneziaStore.KEY_INTERFACE_PRIVATE_KEY,
            prefs.getString(AmneziaStore.KEY_INTERFACE_PRIVATE_KEY, "")
        );
        syncEditTextPreference(
            AmneziaStore.KEY_INTERFACE_ADDRESSES,
            prefs.getString(AmneziaStore.KEY_INTERFACE_ADDRESSES, "")
        );
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_DNS, prefs.getString(AmneziaStore.KEY_INTERFACE_DNS, ""));
        syncEditTextPreference(
            AmneziaStore.KEY_INTERFACE_LISTEN_PORT,
            prefs.getString(AmneziaStore.KEY_INTERFACE_LISTEN_PORT, "")
        );
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_MTU, prefs.getString(AmneziaStore.KEY_INTERFACE_MTU, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_JC, prefs.getString(AmneziaStore.KEY_INTERFACE_JC, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_JMIN, prefs.getString(AmneziaStore.KEY_INTERFACE_JMIN, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_JMAX, prefs.getString(AmneziaStore.KEY_INTERFACE_JMAX, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_S1, prefs.getString(AmneziaStore.KEY_INTERFACE_S1, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_S2, prefs.getString(AmneziaStore.KEY_INTERFACE_S2, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_S3, prefs.getString(AmneziaStore.KEY_INTERFACE_S3, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_S4, prefs.getString(AmneziaStore.KEY_INTERFACE_S4, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_H1, prefs.getString(AmneziaStore.KEY_INTERFACE_H1, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_H2, prefs.getString(AmneziaStore.KEY_INTERFACE_H2, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_H3, prefs.getString(AmneziaStore.KEY_INTERFACE_H3, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_H4, prefs.getString(AmneziaStore.KEY_INTERFACE_H4, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_I1, prefs.getString(AmneziaStore.KEY_INTERFACE_I1, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_I2, prefs.getString(AmneziaStore.KEY_INTERFACE_I2, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_I3, prefs.getString(AmneziaStore.KEY_INTERFACE_I3, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_I4, prefs.getString(AmneziaStore.KEY_INTERFACE_I4, ""));
        syncEditTextPreference(AmneziaStore.KEY_INTERFACE_I5, prefs.getString(AmneziaStore.KEY_INTERFACE_I5, ""));
        syncEditTextPreference(AmneziaStore.KEY_PEER_PUBLIC_KEY, prefs.getString(AmneziaStore.KEY_PEER_PUBLIC_KEY, ""));
        syncEditTextPreference(
            AmneziaStore.KEY_PEER_PRESHARED_KEY,
            prefs.getString(AmneziaStore.KEY_PEER_PRESHARED_KEY, "")
        );
        syncEditTextPreference(
            AmneziaStore.KEY_PEER_ALLOWED_IPS,
            prefs.getString(AmneziaStore.KEY_PEER_ALLOWED_IPS, "")
        );
        syncEditTextPreference(AmneziaStore.KEY_PEER_ENDPOINT, prefs.getString(AmneziaStore.KEY_PEER_ENDPOINT, ""));
        syncEditTextPreference(
            AmneziaStore.KEY_PEER_PERSISTENT_KEEPALIVE,
            prefs.getString(AmneziaStore.KEY_PEER_PERSISTENT_KEEPALIVE, "")
        );
    }
}
