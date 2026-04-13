package wings.v.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import wings.v.AutoSearchActivity;
import wings.v.ByeDpiSettingsActivity;
import wings.v.ExportSettingsActivity;
import wings.v.ExternalActions;
import wings.v.FirstLaunchActivity;
import wings.v.ProxyLogsActivity;
import wings.v.R;
import wings.v.RootInterfaceSettingsActivity;
import wings.v.SubscriptionsActivity;
import wings.v.ThemeSettingsActivity;
import wings.v.VkTurnSettingsActivity;
import wings.v.XposedSettingsActivity;
import wings.v.XraySettingsActivity;
import wings.v.core.ActiveProbingManager;
import wings.v.core.AppPrefs;
import wings.v.core.AppUpdateManager;
import wings.v.core.AutoSearchManager;
import wings.v.core.BackendType;
import wings.v.core.ByeDpiStore;
import wings.v.core.Haptics;
import wings.v.core.ProxySettings;
import wings.v.core.RootUtils;
import wings.v.core.ThemeModeController;
import wings.v.core.UiFormatter;
import wings.v.core.UpdateBadgeUtils;
import wings.v.core.XposedModulePrefs;
import wings.v.service.ProxyTunnelService;

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
    private static final long RUNTIME_BACKEND_REFRESH_INTERVAL_MS = 500L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable runtimeBackendRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshRuntimeBackedPreferences(false);
            handler.postDelayed(this, RUNTIME_BACKEND_REFRESH_INTERVAL_MS);
        }
    };
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;
    private AppUpdateManager appUpdateManager;
    private BackendType lastRuntimeVisibleBackendType;
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
        refreshRuntimeBackedPreferences(true);
        startRuntimeBackendRefresh();
        refreshAboutPreferenceBadge(appUpdateManager.getState());
    }

    @Override
    public void onPause() {
        stopRuntimeBackendRefresh();
        unregisterPreferencesListener();
        appUpdateManager.unregisterListener(updateStateListener);
        super.onPause();
    }

    private void startRuntimeBackendRefresh() {
        handler.removeCallbacks(runtimeBackendRefreshRunnable);
        handler.postDelayed(runtimeBackendRefreshRunnable, RUNTIME_BACKEND_REFRESH_INTERVAL_MS);
    }

    private void stopRuntimeBackendRefresh() {
        handler.removeCallbacks(runtimeBackendRefreshRunnable);
    }

    private void refreshRuntimeBackedPreferences(boolean force) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        BackendType visibleBackendType = ProxyTunnelService.getVisibleBackendType(context);
        if (!force && visibleBackendType == lastRuntimeVisibleBackendType) {
            return;
        }
        lastRuntimeVisibleBackendType = visibleBackendType;
        syncListPreference(AppPrefs.KEY_BACKEND_TYPE, visibleBackendType.prefValue);
        configureRootPreferences(visibleBackendType);
        configureXrayPreferences(visibleBackendType);
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

        Preference exportSettingsPreference = findPreference("pref_open_export_settings");
        if (exportSettingsPreference != null) {
            exportSettingsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(ExportSettingsActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference themePreference = findPreference(AppPrefs.KEY_THEME_MODE);
        if (themePreference != null) {
            themePreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(ThemeSettingsActivity.createIntent(requireContext()));
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
                if (ProxyTunnelService.getVisibleBackendType(requireContext()) != BackendType.XRAY) {
                    return true;
                }
                startActivity(XraySettingsActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference vkTurnSettingsPreference = findPreference(AppPrefs.KEY_OPEN_VK_TURN_SETTINGS);
        if (vkTurnSettingsPreference != null) {
            vkTurnSettingsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                BackendType backendType = ProxyTunnelService.getVisibleBackendType(requireContext());
                if (!backendType.isVkTurnLike()) {
                    return true;
                }
                startActivity(VkTurnSettingsActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference byeDpiSettingsPreference = findPreference(ByeDpiStore.KEY_OPEN_SETTINGS);
        if (byeDpiSettingsPreference != null) {
            byeDpiSettingsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                if (ProxyTunnelService.getVisibleBackendType(requireContext()) != BackendType.XRAY) {
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

        Preference rootInterfaceSettingsPreference = findPreference(AppPrefs.KEY_OPEN_ROOT_INTERFACE_SETTINGS);
        if (rootInterfaceSettingsPreference != null) {
            rootInterfaceSettingsPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(RootInterfaceSettingsActivity.createIntent(requireContext()));
                return true;
            });
        }

        refreshRuntimeBackedPreferences(true);
        syncPreferenceValuesFromPrefs();
    }

    private void configureRootPreferences() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        configureRootPreferences(ProxyTunnelService.getVisibleBackendType(context));
    }

    private void configureRootPreferences(@Nullable BackendType backendType) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        PreferenceCategory rootCategory = findPreference("pref_category_root");
        SwitchPreferenceCompat rootModePreference = findPreference(AppPrefs.KEY_ROOT_MODE);
        SwitchPreferenceCompat kernelWireGuardPreference = findPreference(AppPrefs.KEY_KERNEL_WIREGUARD);
        Preference rootInterfaceSettingsPreference = findPreference(AppPrefs.KEY_OPEN_ROOT_INTERFACE_SETTINGS);
        Preference xposedSettingsPreference = findPreference(XposedModulePrefs.KEY_OPEN_SETTINGS);
        boolean rootGranted = AppPrefs.isRootAccessGranted(context);
        if (rootCategory != null) {
            rootCategory.setVisible(rootGranted);
        }
        if (rootModePreference == null || kernelWireGuardPreference == null) {
            return;
        }
        if (!rootGranted) {
            rootModePreference.setVisible(false);
            kernelWireGuardPreference.setVisible(false);
            if (rootInterfaceSettingsPreference != null) {
                rootInterfaceSettingsPreference.setVisible(false);
            }
            if (xposedSettingsPreference != null) {
                xposedSettingsPreference.setVisible(false);
            }
            return;
        }

        rootModePreference.setVisible(true);
        kernelWireGuardPreference.setVisible(true);
        if (rootInterfaceSettingsPreference != null) {
            rootInterfaceSettingsPreference.setVisible(true);
            rootInterfaceSettingsPreference.setEnabled(true);
        }
        if (xposedSettingsPreference != null) {
            boolean rootModeEnabled = AppPrefs.isRootModeEnabled(context);
            xposedSettingsPreference.setVisible(rootModeEnabled);
            xposedSettingsPreference.setEnabled(rootModeEnabled);
        }
        String unavailableReason = RootUtils.getRootModeUnavailableReason(context, backendType, false);
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
            Context preferenceContext = preference.getContext();
            String reason = RootUtils.getRootModeUnavailableReason(
                preferenceContext,
                ProxyTunnelService.getVisibleBackendType(preferenceContext),
                false
            );
            if (!TextUtils.isEmpty(reason)) {
                Toast.makeText(
                    preferenceContext,
                    getString(R.string.root_mode_unavailable, reason),
                    Toast.LENGTH_SHORT
                ).show();
                return false;
            }
            return true;
        });

        String kernelUnavailableReason = RootUtils.getKernelWireGuardUnavailableReason(context, backendType, false);
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
            Context preferenceContext = preference.getContext();
            String reason = RootUtils.getKernelWireGuardUnavailableReason(
                preferenceContext,
                ProxyTunnelService.getVisibleBackendType(preferenceContext),
                false
            );
            if (!TextUtils.isEmpty(reason)) {
                Toast.makeText(
                    preferenceContext,
                    getString(R.string.kernel_wireguard_unavailable, reason),
                    Toast.LENGTH_SHORT
                ).show();
                return false;
            }
            return true;
        });
    }

    private void configureXrayPreferences() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        configureXrayPreferences(ProxyTunnelService.getVisibleBackendType(context));
    }

    private void configureXrayPreferences(@Nullable BackendType backendType) {
        boolean xrayBackend = backendType == BackendType.XRAY;
        boolean vkTurnLikeBackend = backendType != null && backendType.isVkTurnLike();
        Preference subscriptionsPreference = findPreference("pref_open_subscriptions");
        Preference xraySettingsPreference = findPreference("pref_open_xray_settings");
        Preference vkTurnSettingsPreference = findPreference(AppPrefs.KEY_OPEN_VK_TURN_SETTINGS);
        Preference byeDpiSettingsPreference = findPreference(ByeDpiStore.KEY_OPEN_SETTINGS);
        if (subscriptionsPreference != null) {
            subscriptionsPreference.setVisible(xrayBackend);
        }
        if (xraySettingsPreference != null) {
            xraySettingsPreference.setVisible(xrayBackend);
            xraySettingsPreference.setEnabled(xrayBackend);
            xraySettingsPreference.setSummary(getString(R.string.drawer_xray_settings_summary));
        }
        if (vkTurnSettingsPreference != null) {
            vkTurnSettingsPreference.setVisible(vkTurnLikeBackend);
            vkTurnSettingsPreference.setEnabled(vkTurnLikeBackend);
            if (backendType == BackendType.WIREGUARD) {
                vkTurnSettingsPreference.setTitle(getString(R.string.wireguard_settings_title));
                vkTurnSettingsPreference.setSummary(getString(R.string.wireguard_settings_summary));
            } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
                vkTurnSettingsPreference.setTitle(getString(R.string.amneziawg_settings_title));
                vkTurnSettingsPreference.setSummary(getString(R.string.amneziawg_settings_summary));
            } else if (backendType == BackendType.AMNEZIAWG) {
                vkTurnSettingsPreference.setTitle(getString(R.string.vk_turn_awg_settings_title));
                vkTurnSettingsPreference.setSummary(getString(R.string.vk_turn_awg_settings_summary));
            } else {
                vkTurnSettingsPreference.setTitle(getString(R.string.vk_turn_settings_title));
                vkTurnSettingsPreference.setSummary(getString(R.string.vk_turn_settings_summary));
            }
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
            refreshRuntimeBackedPreferences(true);
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
        Context context = getContext();
        if (context == null) {
            return;
        }
        ProxySettings settings = AppPrefs.getSettings(context);

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
        syncSwitchPreference(AppPrefs.KEY_ROOT_MODE, AppPrefs.isRootModeEnabled(context));
        syncSwitchPreference(AppPrefs.KEY_KERNEL_WIREGUARD, AppPrefs.isKernelWireGuardEnabled(context));
        syncSwitchPreference(AppPrefs.KEY_AUTO_START_ON_BOOT, AppPrefs.isAutoStartOnBootEnabled(context));
        syncListPreference(AppPrefs.KEY_BACKEND_TYPE, ProxyTunnelService.getVisibleBackendType(context).prefValue);
        syncPreferenceSummary(
            AppPrefs.KEY_THEME_MODE,
            getString(ThemeModeController.resolveLabelRes(AppPrefs.getThemeMode(context)))
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
        if (AppPrefs.KEY_BACKEND_TYPE.equals(key)) {
            preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
                if (getListView() != null) {
                    Haptics.softSelection(getListView());
                } else if (getView() != null) {
                    Haptics.softSelection(getView());
                }
                BackendType nextBackend = BackendType.fromPrefValue(newValue == null ? null : String.valueOf(newValue));
                ExternalActions.setBackend(changedPreference.getContext(), nextBackend, true, false);
                return true;
            });
            return;
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

    private void syncPreferenceSummary(String key, CharSequence summary) {
        Preference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        if (TextUtils.equals(preference.getSummary(), summary)) {
            return;
        }
        preference.setSummary(summary);
    }
}
