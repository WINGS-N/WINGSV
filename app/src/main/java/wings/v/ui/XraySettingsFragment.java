package wings.v.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.DropDownPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import java.util.LinkedHashSet;
import java.util.Set;
import wings.v.R;
import wings.v.WarningConfirmActivity;
import wings.v.XrayRoutingSettingsActivity;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.ProxyRuntimeMode;
import wings.v.core.SocksAuthSecurity;
import wings.v.core.XraySettings;
import wings.v.core.XrayStore;
import wings.v.core.XrayTransportMode;
import wings.v.service.ProxyTunnelService;

public class XraySettingsFragment extends PreferenceFragmentCompat {

    private static final int SOCKS_AUTH_DISABLE_WARNING_DELAY_SECONDS = 15;
    private static final Set<String> RUNTIME_AFFECTING_KEYS = new LinkedHashSet<>();

    static {
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_ALLOW_LAN);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_ALLOW_INSECURE);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_LOCAL_PROXY_ENABLED);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_LOCAL_PROXY_USERNAME);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_LOCAL_PROXY_LISTEN_ADDRESS);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_HTTP_PROXY_ENABLED);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_HTTP_PROXY_AUTH_ENABLED);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_HTTP_PROXY_USERNAME);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_HTTP_PROXY_PASSWORD);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_HTTP_PROXY_PORT);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_HTTP_PROXY_LISTEN_ADDRESS);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_WAKE_PROBE_MODE);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_REMOTE_DNS);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_DIRECT_DNS);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_IPV6_ENABLED);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_SNIFFING_ENABLED);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_PROXY_QUIC_ENABLED);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_RUNTIME_MODE);
        RUNTIME_AFFECTING_KEYS.add(AppPrefs.KEY_XRAY_TRANSPORT_MODE);
    }

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
        bindSwitch(AppPrefs.KEY_XRAY_HTTP_PROXY_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_HTTP_PROXY_AUTH_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_IPV6_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_SNIFFING_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_PROXY_QUIC_ENABLED);
        bindSwitch(AppPrefs.KEY_XRAY_RESTART_ON_NETWORK_CHANGE);
        bindRuntimeMode(AppPrefs.KEY_XRAY_RUNTIME_MODE);
        bindTransportMode(AppPrefs.KEY_XRAY_TRANSPORT_MODE);
        bindWakeProbeMode(AppPrefs.KEY_XRAY_WAKE_PROBE_MODE);
        bindRoutingEntry();
        bindSummary(AppPrefs.KEY_XRAY_REMOTE_DNS);
        bindSummary(AppPrefs.KEY_XRAY_DIRECT_DNS);
        bindSummary(AppPrefs.KEY_XRAY_LOCAL_PROXY_USERNAME);
        bindSummary(AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD);
        bindNumeric(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT);
        bindSummary(AppPrefs.KEY_XRAY_LOCAL_PROXY_LISTEN_ADDRESS);
        bindSummary(AppPrefs.KEY_XRAY_HTTP_PROXY_USERNAME);
        bindSummary(AppPrefs.KEY_XRAY_HTTP_PROXY_PASSWORD);
        bindNumeric(AppPrefs.KEY_XRAY_HTTP_PROXY_PORT);
        bindSummary(AppPrefs.KEY_XRAY_HTTP_PROXY_LISTEN_ADDRESS);
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
            String authDisableWarning = warningForAuthDisable(key, preference, newValue);
            if (authDisableWarning != null) {
                showWarningBeforeApplying(
                    () -> {
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit()
                            .putBoolean(key, false)
                            .commit();
                        requestRuntimeReconnectIfActive(key);
                    },
                    authDisableWarning
                );
                return false;
            }
            if (
                TextUtils.equals(key, AppPrefs.KEY_XRAY_LOCAL_PROXY_ENABLED) ||
                TextUtils.equals(key, AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED) ||
                TextUtils.equals(key, AppPrefs.KEY_XRAY_HTTP_PROXY_ENABLED) ||
                TextUtils.equals(key, AppPrefs.KEY_XRAY_HTTP_PROXY_AUTH_ENABLED)
            ) {
                requireView().post(this::syncFromStore);
            }
            requestRuntimeReconnectAfterPersist(key);
            return true;
        });
    }

    private void bindSummary(String key) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            String weakWarning = warningForWeakPassword(key, newValue);
            if (weakWarning != null) {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                showWarningBeforeApplying(
                    () -> {
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit()
                            .putString(key, newValue == null ? "" : String.valueOf(newValue))
                            .commit();
                        requestRuntimeReconnectIfActive(key);
                    },
                    weakWarning
                );
                return false;
            }
            requestRuntimeReconnectAfterPersist(key);
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
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            requestRuntimeReconnectAfterPersist(key);
            return true;
        });
    }

    private void syncFromStore() {
        XraySettings settings = XrayStore.getXraySettings(requireContext());
        syncEditText(AppPrefs.KEY_XRAY_REMOTE_DNS, settings.remoteDns);
        syncEditText(AppPrefs.KEY_XRAY_DIRECT_DNS, settings.directDns);
        syncEditText(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT, String.valueOf(settings.localProxyPort));
        syncEditText(AppPrefs.KEY_XRAY_LOCAL_PROXY_USERNAME, settings.localProxyUsername);
        syncEditText(AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD, settings.localProxyPassword);
        syncEditText(AppPrefs.KEY_XRAY_LOCAL_PROXY_LISTEN_ADDRESS, settings.localProxyListenAddress);
        syncEditText(AppPrefs.KEY_XRAY_HTTP_PROXY_PORT, String.valueOf(settings.httpProxyPort));
        syncEditText(AppPrefs.KEY_XRAY_HTTP_PROXY_USERNAME, settings.httpProxyUsername);
        syncEditText(AppPrefs.KEY_XRAY_HTTP_PROXY_PASSWORD, settings.httpProxyPassword);
        syncEditText(AppPrefs.KEY_XRAY_HTTP_PROXY_LISTEN_ADDRESS, settings.httpProxyListenAddress);
        syncSwitch(AppPrefs.KEY_XRAY_ALLOW_LAN, settings.allowLan);
        syncSwitch(AppPrefs.KEY_XRAY_ALLOW_INSECURE, settings.allowInsecure);
        syncSwitch(AppPrefs.KEY_XRAY_LOCAL_PROXY_ENABLED, settings.localProxyEnabled);
        syncSwitch(AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED, settings.localProxyAuthEnabled);
        syncSwitch(AppPrefs.KEY_XRAY_HTTP_PROXY_ENABLED, settings.httpProxyEnabled);
        syncSwitch(AppPrefs.KEY_XRAY_HTTP_PROXY_AUTH_ENABLED, settings.httpProxyAuthEnabled);
        syncSwitch(AppPrefs.KEY_XRAY_IPV6_ENABLED, settings.ipv6);
        syncSwitch(AppPrefs.KEY_XRAY_SNIFFING_ENABLED, settings.sniffingEnabled);
        syncSwitch(AppPrefs.KEY_XRAY_PROXY_QUIC_ENABLED, settings.proxyQuicEnabled);
        syncSwitch(AppPrefs.KEY_XRAY_RESTART_ON_NETWORK_CHANGE, settings.restartOnNetworkChange);
        syncDropDown(
            AppPrefs.KEY_XRAY_RUNTIME_MODE,
            settings.runtimeMode == null ? ProxyRuntimeMode.VPN.prefValue : settings.runtimeMode.prefValue
        );
        refreshTransportModeVisibility();
        syncDropDown(
            AppPrefs.KEY_XRAY_TRANSPORT_MODE,
            settings.transportMode == null ? XrayTransportMode.DIRECT.prefValue : settings.transportMode.prefValue
        );
        syncDropDown(AppPrefs.KEY_XRAY_WAKE_PROBE_MODE, XraySettings.WakeProbeMode.normalize(settings.wakeProbeMode));
        refreshWakeProbeVisibility();
        syncRoutingSummary();
        refreshLocalProxyVisibility(settings);
    }

    private void refreshWakeProbeVisibility() {
        Preference preference = findPreference(AppPrefs.KEY_XRAY_WAKE_PROBE_MODE);
        if (preference == null) {
            return;
        }
        Context context = getContext();
        boolean hideForTproxy = context != null && AppPrefs.isXrayTproxyModeEnabled(context);
        preference.setVisible(!hideForTproxy);
    }

    private void refreshTransportModeVisibility() {
        Preference preference = findPreference(AppPrefs.KEY_XRAY_TRANSPORT_MODE);
        if (preference != null) {
            preference.setVisible(true);
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

    private void syncSwitch(String key, boolean value) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference != null && preference.isChecked() != value) {
            preference.setChecked(value);
        }
    }

    private void syncDropDown(String key, String value) {
        DropDownPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        String normalized = value == null ? "" : value;
        if (!TextUtils.equals(preference.getValue(), normalized)) {
            preference.setValue(normalized);
        }
    }

    private void refreshLocalProxyVisibility(XraySettings settings) {
        boolean proxyEnabled = settings.localProxyEnabled;
        boolean authEnabled = proxyEnabled && settings.localProxyAuthEnabled;
        setPreferenceEnabled(AppPrefs.KEY_XRAY_LOCAL_PROXY_PORT, proxyEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_LOCAL_PROXY_LISTEN_ADDRESS, proxyEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED, proxyEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_LOCAL_PROXY_USERNAME, authEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD, authEnabled);
        boolean httpEnabled = settings.httpProxyEnabled;
        boolean httpAuthEnabled = httpEnabled && settings.httpProxyAuthEnabled;
        setPreferenceEnabled(AppPrefs.KEY_XRAY_HTTP_PROXY_PORT, httpEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_HTTP_PROXY_LISTEN_ADDRESS, httpEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_HTTP_PROXY_AUTH_ENABLED, httpEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_HTTP_PROXY_USERNAME, httpAuthEnabled);
        setPreferenceEnabled(AppPrefs.KEY_XRAY_HTTP_PROXY_PASSWORD, httpAuthEnabled);
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

    private void bindTransportMode(String key) {
        DropDownPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummaryProvider(pref -> {
            CharSequence entry = ((DropDownPreference) pref).getEntry();
            return TextUtils.isEmpty(entry) ? getString(R.string.xray_settings_transport_mode_summary) : entry;
        });
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            requestRuntimeReconnectAfterPersist(key);
            return true;
        });
    }

    private void bindWakeProbeMode(String key) {
        DropDownPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummaryProvider(pref -> {
            CharSequence entry = ((DropDownPreference) pref).getEntry();
            return TextUtils.isEmpty(entry) ? getString(R.string.xray_settings_wake_probe_mode_summary) : entry;
        });
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            requestRuntimeReconnectAfterPersist(key);
            return true;
        });
    }

    private void bindRuntimeMode(String key) {
        DropDownPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummaryProvider(pref -> {
            CharSequence entry = ((DropDownPreference) pref).getEntry();
            return TextUtils.isEmpty(entry) ? getString(R.string.runtime_mode_summary) : entry;
        });
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            if (ProxyRuntimeMode.fromPrefValue(String.valueOf(newValue)).isProxyOnly()) {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putBoolean(AppPrefs.KEY_XRAY_LOCAL_PROXY_ENABLED, true)
                    .commit();
                requireView().post(this::syncFromStore);
            }
            requestRuntimeReconnectAfterPersist(key);
            return true;
        });
    }

    private String warningForAuthDisable(String key, SwitchPreferenceCompat preference, Object newValue) {
        if (!preference.isChecked() || !Boolean.FALSE.equals(newValue)) {
            return null;
        }
        if (TextUtils.equals(key, AppPrefs.KEY_XRAY_LOCAL_PROXY_AUTH_ENABLED)) {
            return getString(R.string.warning_socks_auth_disable);
        }
        if (TextUtils.equals(key, AppPrefs.KEY_XRAY_HTTP_PROXY_AUTH_ENABLED)) {
            return getString(R.string.warning_http_auth_disable);
        }
        return null;
    }

    private String warningForWeakPassword(String key, Object newValue) {
        // An empty value is the user asking for auto-generation; SocksAuthCredentials.ensure
        // mints a fresh random token on next read. Don't gate that path behind a "too simple"
        // warning, it would block the regeneration UX.
        String candidate = newValue == null ? "" : String.valueOf(newValue);
        if (TextUtils.isEmpty(candidate)) {
            return null;
        }
        XraySettings settings = XrayStore.getXraySettings(requireContext());
        if (TextUtils.equals(key, AppPrefs.KEY_XRAY_LOCAL_PROXY_PASSWORD)) {
            if (!settings.localProxyEnabled || !settings.localProxyAuthEnabled) {
                return null;
            }
            if (SocksAuthSecurity.isPasswordTooSimple(settings.localProxyUsername, candidate)) {
                return getString(R.string.warning_socks_password_weak);
            }
            return null;
        }
        if (TextUtils.equals(key, AppPrefs.KEY_XRAY_HTTP_PROXY_PASSWORD)) {
            if (!settings.httpProxyEnabled || !settings.httpProxyAuthEnabled) {
                return null;
            }
            if (SocksAuthSecurity.isPasswordTooSimple(settings.httpProxyUsername, candidate)) {
                return getString(R.string.warning_http_password_weak);
            }
            return null;
        }
        return null;
    }

    private void showWarningBeforeApplying(Runnable action, String warningText) {
        pendingWarningConfirmedAction = action;
        warningLauncher.launch(
            WarningConfirmActivity.createIntent(requireContext(), warningText, SOCKS_AUTH_DISABLE_WARNING_DELAY_SECONDS)
        );
    }

    private void requestRuntimeReconnectAfterPersist(String key) {
        if (!isRuntimeAffectingKey(key)) {
            return;
        }
        android.view.View view = getView();
        Runnable reconnect = () -> requestRuntimeReconnectIfActive(key);
        if (view != null) {
            view.post(reconnect);
        } else {
            reconnect.run();
        }
    }

    private void requestRuntimeReconnectIfActive(String key) {
        if (!isRuntimeAffectingKey(key) || !ProxyTunnelService.isActive()) {
            return;
        }
        android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        BackendType backendType = XrayStore.getBackendType(context);
        if (backendType == null || !backendType.usesXrayCore()) {
            return;
        }
        ProxyTunnelService.requestReconnect(context.getApplicationContext(), "Xray settings changed");
    }

    private boolean isRuntimeAffectingKey(String key) {
        return !TextUtils.isEmpty(key) && RUNTIME_AFFECTING_KEYS.contains(key);
    }
}
