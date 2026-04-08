package wings.v.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import dev.oneuiproject.oneui.preference.UpdatableWidgetPreference;
import wings.v.AboutAppActivity;
import wings.v.ActiveProbingSettingsActivity;
import wings.v.AmneziaSettingsActivity;
import wings.v.AutoSearchActivity;
import wings.v.ByeDpiSettingsActivity;
import wings.v.ExternalActions;
import wings.v.FirstLaunchActivity;
import wings.v.ProxyLogsActivity;
import wings.v.R;
import wings.v.SubscriptionsActivity;
import wings.v.XposedSettingsActivity;
import wings.v.XraySettingsActivity;
import wings.v.core.ActiveProbingManager;
import wings.v.core.AmneziaStore;
import wings.v.core.AppPrefs;
import wings.v.core.AppUpdateManager;
import wings.v.core.AutoSearchManager;
import wings.v.core.BackendType;
import wings.v.core.ByeDpiStore;
import wings.v.core.Haptics;
import wings.v.core.ProxySettings;
import wings.v.core.RootUtils;
import wings.v.core.UiFormatter;
import wings.v.core.UpdateBadgeUtils;
import wings.v.core.XposedModulePrefs;
import wings.v.core.XrayStore;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.CommentDefaultAccessModifier",
        "PMD.FieldDeclarationsShouldBeAtStartOfClass",
        "PMD.ExcessiveImports",
        "PMD.GodClass",
        "PMD.CyclomaticComplexity",
        "PMD.TooManyMethods",
        "PMD.NcssCount",
        "PMD.CognitiveComplexity",
        "PMD.NPathComplexity",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.ShortClassName",
        "PMD.ShortMethodName",
        "PMD.OnlyOneReturn",
        "PMD.ImplicitFunctionalInterface",
        "PMD.UncommentedEmptyMethodBody",
    }
)
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final int SECRET_PREVIEW_PLAIN_LENGTH = 12;
    private static final String[] VK_PROXY_PREFERENCE_KEYS = {
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
        "pref_inset_after_backend",
        "pref_inset_after_vk_proxy",
        "pref_category_vk_proxy",
    };
    private static final String[] WG_BACKEND_PREFERENCE_KEYS = {
        AppPrefs.KEY_WG_PRIVATE_KEY,
        AppPrefs.KEY_WG_ADDRESSES,
        AppPrefs.KEY_WG_DNS,
        AppPrefs.KEY_WG_MTU,
        AppPrefs.KEY_WG_PUBLIC_KEY,
        AppPrefs.KEY_WG_PRESHARED_KEY,
        AppPrefs.KEY_WG_ALLOWED_IPS,
        "pref_inset_after_wg_interface",
        "pref_inset_after_wg_peer",
        "pref_category_wg_interface",
        "pref_category_wg_peer",
    };
    private static final String[] AWG_BACKEND_PREFERENCE_KEYS = {
        AmneziaStore.KEY_OPEN_SETTINGS,
        "pref_category_awg",
        "pref_inset_after_awg",
    };
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;
    private AppUpdateManager appUpdateManager;
    private final AppUpdateManager.Listener updateStateListener = this::refreshAboutPreferenceBadge;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        AppPrefs.ensureDefaults(requireContext());
        appUpdateManager = AppUpdateManager.getInstance(requireContext());
        setPreferencesFromResource(R.xml.proxy_preferences, rootKey);
        configurePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerPreferencesListener();
        appUpdateManager.registerListener(updateStateListener);
        syncPreferenceValuesFromPrefs();
        configureRootPreferences();
        configureXrayPreferences(XrayStore.getBackendType(requireContext()));
        refreshAboutPreferenceBadge(appUpdateManager.getState());
    }

    @Override
    public void onPause() {
        unregisterPreferencesListener();
        appUpdateManager.unregisterListener(updateStateListener);
        super.onPause();
    }

    private void configurePreferences() {
        bindNumericPreference(AppPrefs.KEY_THREADS);
        bindNumericPreference(AppPrefs.KEY_WG_MTU);
        bindListPreference(AppPrefs.KEY_TURN_SESSION_MODE);
        bindListPreference(AppPrefs.KEY_BACKEND_TYPE);

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
        bindSwitchHaptics(AppPrefs.KEY_MANUAL_CAPTCHA);
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
                startActivity(FirstLaunchActivity.createPermissionsIntent(requireContext()));
                return true;
            });
        }

        Preference proxyLogsPreference = findPreference("pref_open_proxy_logs");
        if (proxyLogsPreference != null) {
            proxyLogsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(ProxyLogsActivity.createProxyIntent(requireContext()));
                return true;
            });
        }

        Preference xrayLogsPreference = findPreference("pref_open_xray_logs");
        if (xrayLogsPreference != null) {
            xrayLogsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(ProxyLogsActivity.createXrayIntent(requireContext()));
                return true;
            });
        }

        Preference runtimeLogsPreference = findPreference("pref_open_runtime_logs");
        if (runtimeLogsPreference != null) {
            runtimeLogsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(ProxyLogsActivity.createRuntimeIntent(requireContext()));
                return true;
            });
        }

        Preference aboutPreference = findPreference("pref_open_about");
        if (aboutPreference != null) {
            aboutPreference.setIcon(R.drawable.ic_about_app_info);
            if (aboutPreference instanceof UpdatableWidgetPreference) {
                ((UpdatableWidgetPreference) aboutPreference).setShowWidget(false);
            }
            aboutPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(AboutAppActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference subscriptionsPreference = findPreference("pref_open_subscriptions");
        if (subscriptionsPreference != null) {
            subscriptionsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(SubscriptionsActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference xraySettingsPreference = findPreference("pref_open_xray_settings");
        if (xraySettingsPreference != null) {
            xraySettingsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                if (XrayStore.getBackendType(requireContext()) != BackendType.XRAY) {
                    return true;
                }
                startActivity(XraySettingsActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference byeDpiSettingsPreference = findPreference(ByeDpiStore.KEY_OPEN_SETTINGS);
        if (byeDpiSettingsPreference != null) {
            byeDpiSettingsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                if (XrayStore.getBackendType(requireContext()) != BackendType.XRAY) {
                    return true;
                }
                startActivity(ByeDpiSettingsActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference activeProbingSettingsPreference = findPreference(ActiveProbingManager.KEY_OPEN_SETTINGS);
        if (activeProbingSettingsPreference != null) {
            activeProbingSettingsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(ActiveProbingSettingsActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference autoSearchPreference = findPreference(AutoSearchManager.KEY_OPEN_SETTINGS);
        if (autoSearchPreference != null) {
            autoSearchPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(AutoSearchActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference amneziaSettingsPreference = findPreference(AmneziaStore.KEY_OPEN_SETTINGS);
        if (amneziaSettingsPreference != null) {
            amneziaSettingsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(AmneziaSettingsActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference xposedSettingsPreference = findPreference(XposedModulePrefs.KEY_OPEN_SETTINGS);
        if (xposedSettingsPreference != null) {
            xposedSettingsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                if (!AppPrefs.isRootModeEnabled(requireContext())) {
                    return true;
                }
                startActivity(XposedSettingsActivity.createIntent(requireContext()));
                return true;
            });
        }

        configureRootPreferences();
        configureXrayPreferences(XrayStore.getBackendType(requireContext()));
        syncPreferenceValuesFromPrefs();
    }

    private void configureRootPreferences() {
        PreferenceCategory rootCategory = findPreference("pref_category_root");
        SwitchPreferenceCompat rootModePreference = findPreference(AppPrefs.KEY_ROOT_MODE);
        SwitchPreferenceCompat kernelWireGuardPreference = findPreference(AppPrefs.KEY_KERNEL_WIREGUARD);
        Preference xposedSettingsPreference = findPreference(XposedModulePrefs.KEY_OPEN_SETTINGS);
        boolean rootGranted = AppPrefs.isRootAccessGranted(requireContext());
        if (rootCategory != null) {
            rootCategory.setVisible(rootGranted);
        }
        if (rootModePreference == null || kernelWireGuardPreference == null) {
            return;
        }
        if (!rootGranted) {
            rootModePreference.setVisible(false);
            kernelWireGuardPreference.setVisible(false);
            if (xposedSettingsPreference != null) {
                xposedSettingsPreference.setVisible(false);
            }
            return;
        }

        rootModePreference.setVisible(true);
        kernelWireGuardPreference.setVisible(true);
        if (xposedSettingsPreference != null) {
            boolean rootModeEnabled = AppPrefs.isRootModeEnabled(requireContext());
            xposedSettingsPreference.setVisible(rootModeEnabled);
            xposedSettingsPreference.setEnabled(rootModeEnabled);
        }
        BackendType backendType = XrayStore.getBackendType(requireContext());
        String unavailableReason = RootUtils.getRootModeUnavailableReason(requireContext(), backendType, false);
        boolean supported = TextUtils.isEmpty(unavailableReason);
        rootModePreference.setEnabled(supported);
        rootModePreference.setSummary(
            supported
                ? getString(R.string.root_mode_summary)
                : getString(R.string.root_mode_unavailable, unavailableReason)
        );
        rootModePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            Haptics.softSliderStep(getListView() != null ? getListView() : requireView());
            if (!(newValue instanceof Boolean) || !(Boolean) newValue) {
                return true;
            }
            String reason = RootUtils.getRootModeUnavailableReason(
                requireContext(),
                XrayStore.getBackendType(requireContext()),
                false
            );
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

        String kernelUnavailableReason = RootUtils.getKernelWireGuardUnavailableReason(
            requireContext(),
            backendType,
            false
        );
        boolean kernelSupported = TextUtils.isEmpty(kernelUnavailableReason);
        kernelWireGuardPreference.setEnabled(kernelSupported);
        kernelWireGuardPreference.setSummary(
            kernelSupported
                ? getString(R.string.kernel_wireguard_summary)
                : getString(R.string.kernel_wireguard_unavailable, kernelUnavailableReason)
        );
        kernelWireGuardPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            Haptics.softSliderStep(getListView() != null ? getListView() : requireView());
            if (!(newValue instanceof Boolean) || !(Boolean) newValue) {
                return true;
            }
            String reason = RootUtils.getKernelWireGuardUnavailableReason(
                requireContext(),
                XrayStore.getBackendType(requireContext()),
                false
            );
            if (!TextUtils.isEmpty(reason)) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.kernel_wireguard_unavailable, reason),
                    Toast.LENGTH_SHORT
                ).show();
                return false;
            }
            return true;
        });
    }

    private void configureXrayPreferences() {
        configureXrayPreferences(XrayStore.getBackendType(requireContext()));
    }

    private void configureXrayPreferences(@Nullable BackendType backendType) {
        boolean xrayBackend = backendType == BackendType.XRAY;
        boolean awgBackend = backendType == BackendType.AMNEZIAWG;
        Preference subscriptionsPreference = findPreference("pref_open_subscriptions");
        Preference xraySettingsPreference = findPreference("pref_open_xray_settings");
        Preference byeDpiSettingsPreference = findPreference(ByeDpiStore.KEY_OPEN_SETTINGS);
        for (String key : VK_PROXY_PREFERENCE_KEYS) {
            setPreferenceVisible(key, !xrayBackend);
        }
        for (String key : WG_BACKEND_PREFERENCE_KEYS) {
            setPreferenceVisible(key, !xrayBackend && !awgBackend);
        }
        for (String key : AWG_BACKEND_PREFERENCE_KEYS) {
            setPreferenceVisible(key, awgBackend);
        }
        if (subscriptionsPreference != null) {
            subscriptionsPreference.setVisible(xrayBackend);
        }
        if (xraySettingsPreference != null) {
            xraySettingsPreference.setVisible(xrayBackend);
            xraySettingsPreference.setEnabled(xrayBackend);
            xraySettingsPreference.setSummary(getString(R.string.drawer_xray_settings_summary));
        }
        if (byeDpiSettingsPreference != null) {
            byeDpiSettingsPreference.setVisible(true);
            byeDpiSettingsPreference.setEnabled(xrayBackend);
            byeDpiSettingsPreference.setSummary(
                xrayBackend ? getString(R.string.byedpi_open_summary) : getString(R.string.byedpi_xray_only_summary)
            );
        }
    }

    private void setPreferenceVisible(String key, boolean visible) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setVisible(visible);
        }
    }

    private void refreshAboutPreferenceBadge(@Nullable AppUpdateManager.UpdateState state) {
        Preference aboutPreference = findPreference("pref_open_about");
        if (aboutPreference == null || !isAdded()) {
            return;
        }
        aboutPreference.setIcon(R.drawable.ic_about_app_info);
        if (aboutPreference instanceof UpdatableWidgetPreference) {
            ((UpdatableWidgetPreference) aboutPreference).setShowWidget(UpdateBadgeUtils.shouldShowUpdateBadge(state));
        }
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
            configureXrayPreferences();
        };
        getPreferenceManager()
            .getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(preferencesChangeListener);
    }

    private void unregisterPreferencesListener() {
        if (preferencesChangeListener == null) {
            return;
        }
        getPreferenceManager()
            .getSharedPreferences()
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
        syncSwitchPreference(AppPrefs.KEY_ROOT_MODE, AppPrefs.isRootModeEnabled(requireContext()));
        syncSwitchPreference(AppPrefs.KEY_KERNEL_WIREGUARD, AppPrefs.isKernelWireGuardEnabled(requireContext()));
        syncSwitchPreference(AppPrefs.KEY_AUTO_START_ON_BOOT, AppPrefs.isAutoStartOnBootEnabled(requireContext()));
        syncListPreference(AppPrefs.KEY_BACKEND_TYPE, XrayStore.getBackendType(requireContext()).prefValue);
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
        if (AppPrefs.KEY_BACKEND_TYPE.equals(key)) {
            preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                BackendType nextBackend = BackendType.fromPrefValue(newValue == null ? null : String.valueOf(newValue));
                ExternalActions.setBackend(requireContext(), nextBackend, true, false);
                configureRootPreferences();
                configureXrayPreferences(nextBackend);
                return true;
            });
        }
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
