package wings.v.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.DropDownPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import wings.v.ByeDpiStrategyTestActivity;
import wings.v.ByeDpiTargetsActivity;
import wings.v.R;
import wings.v.WarningConfirmActivity;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.ByeDpiSettings;
import wings.v.core.ByeDpiStore;
import wings.v.core.Haptics;
import wings.v.core.SocksAuthSecurity;
import wings.v.core.XrayStore;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.GodClass",
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.NPathComplexity",
        "PMD.TooManyMethods",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class ByeDpiSettingsFragment extends PreferenceFragmentCompat {

    private static final int SOCKS_AUTH_DISABLE_WARNING_DELAY_SECONDS = 15;

    private static final Set<String> RUNTIME_AFFECTING_KEYS = new LinkedHashSet<>();
    private static final String[] UI_EDITOR_KEYS = {
        ByeDpiStore.KEY_MAX_CONNECTIONS,
        ByeDpiStore.KEY_BUFFER_SIZE,
        ByeDpiStore.KEY_NO_DOMAIN,
        ByeDpiStore.KEY_TCP_FAST_OPEN,
        ByeDpiStore.KEY_HOSTS_MODE,
        ByeDpiStore.KEY_HOSTS_BLACKLIST,
        ByeDpiStore.KEY_HOSTS_WHITELIST,
        ByeDpiStore.KEY_DEFAULT_TTL,
        ByeDpiStore.KEY_DESYNC_METHOD,
        ByeDpiStore.KEY_SPLIT_POSITION,
        ByeDpiStore.KEY_SPLIT_AT_HOST,
        ByeDpiStore.KEY_DROP_SACK,
        ByeDpiStore.KEY_FAKE_TTL,
        ByeDpiStore.KEY_FAKE_OFFSET,
        ByeDpiStore.KEY_FAKE_SNI,
        ByeDpiStore.KEY_OOB_DATA,
        ByeDpiStore.KEY_DESYNC_HTTP,
        ByeDpiStore.KEY_DESYNC_HTTPS,
        ByeDpiStore.KEY_DESYNC_UDP,
        ByeDpiStore.KEY_HOST_MIXED_CASE,
        ByeDpiStore.KEY_DOMAIN_MIXED_CASE,
        ByeDpiStore.KEY_HOST_REMOVE_SPACES,
        ByeDpiStore.KEY_TLSREC_ENABLED,
        ByeDpiStore.KEY_TLSREC_POSITION,
        ByeDpiStore.KEY_TLSREC_AT_SNI,
        ByeDpiStore.KEY_UDP_FAKE_COUNT,
    };

    static {
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_AUTO_START_WITH_XRAY);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_USE_COMMAND_SETTINGS);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_PROXY_IP);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_PROXY_PORT);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_PROXY_AUTH_ENABLED);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_PROXY_USERNAME);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_PROXY_PASSWORD);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_MAX_CONNECTIONS);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_BUFFER_SIZE);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_NO_DOMAIN);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_TCP_FAST_OPEN);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_HOSTS_MODE);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_HOSTS_BLACKLIST);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_HOSTS_WHITELIST);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_DEFAULT_TTL);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_DESYNC_METHOD);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_SPLIT_POSITION);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_SPLIT_AT_HOST);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_DROP_SACK);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_FAKE_TTL);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_FAKE_OFFSET);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_FAKE_SNI);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_OOB_DATA);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_DESYNC_HTTP);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_DESYNC_HTTPS);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_DESYNC_UDP);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_HOST_MIXED_CASE);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_DOMAIN_MIXED_CASE);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_HOST_REMOVE_SPACES);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_TLSREC_ENABLED);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_TLSREC_POSITION);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_TLSREC_AT_SNI);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_UDP_FAKE_COUNT);
        RUNTIME_AFFECTING_KEYS.add(ByeDpiStore.KEY_CMD_ARGS);
    }

    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;

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
            syncFromPrefs();
            refreshAvailability();
            refreshVisibility();
        }
    );

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        AppPrefs.ensureDefaults(requireContext());
        setPreferencesFromResource(R.xml.byedpi_preferences, rootKey);
        configurePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerPreferencesListener();
        syncFromPrefs();
        refreshAvailability();
        refreshVisibility();
    }

    @Override
    public void onPause() {
        unregisterPreferencesListener();
        super.onPause();
    }

    private void configurePreferences() {
        bindSwitch(ByeDpiStore.KEY_AUTO_START_WITH_XRAY);
        bindSwitch(ByeDpiStore.KEY_USE_COMMAND_SETTINGS);
        bindSwitch(ByeDpiStore.KEY_PROXY_AUTH_ENABLED);
        bindSwitch(ByeDpiStore.KEY_NO_DOMAIN);
        bindSwitch(ByeDpiStore.KEY_TCP_FAST_OPEN);
        bindSwitch(ByeDpiStore.KEY_SPLIT_AT_HOST);
        bindSwitch(ByeDpiStore.KEY_DROP_SACK);
        bindSwitch(ByeDpiStore.KEY_DESYNC_HTTP);
        bindSwitch(ByeDpiStore.KEY_DESYNC_HTTPS);
        bindSwitch(ByeDpiStore.KEY_DESYNC_UDP);
        bindSwitch(ByeDpiStore.KEY_HOST_MIXED_CASE);
        bindSwitch(ByeDpiStore.KEY_DOMAIN_MIXED_CASE);
        bindSwitch(ByeDpiStore.KEY_HOST_REMOVE_SPACES);
        bindSwitch(ByeDpiStore.KEY_TLSREC_ENABLED);
        bindSwitch(ByeDpiStore.KEY_TLSREC_AT_SNI);
        bindSwitch(ByeDpiStore.KEY_PROXYTEST_USE_CUSTOM_STRATEGIES);

        bindNumeric(ByeDpiStore.KEY_PROXY_PORT, false, 0);
        bindNumeric(ByeDpiStore.KEY_MAX_CONNECTIONS, false, 0);
        bindNumeric(ByeDpiStore.KEY_BUFFER_SIZE, false, 0);
        bindNumeric(ByeDpiStore.KEY_DEFAULT_TTL, false, 0);
        bindNumeric(ByeDpiStore.KEY_SPLIT_POSITION, true, 0);
        bindNumeric(ByeDpiStore.KEY_FAKE_TTL, false, 0);
        bindNumeric(ByeDpiStore.KEY_FAKE_OFFSET, true, 0);
        bindNumeric(ByeDpiStore.KEY_TLSREC_POSITION, true, 0);
        bindNumeric(ByeDpiStore.KEY_UDP_FAKE_COUNT, false, 0);
        bindNumeric(ByeDpiStore.KEY_PROXYTEST_DELAY, false, R.string.proxytest_delay_desc);
        bindNumeric(ByeDpiStore.KEY_PROXYTEST_REQUESTS, false, R.string.proxytest_requests_desc);
        bindNumeric(ByeDpiStore.KEY_PROXYTEST_LIMIT, false, R.string.proxytest_limit_desc);
        bindNumeric(ByeDpiStore.KEY_PROXYTEST_TIMEOUT, false, R.string.proxytest_timeout_desc);

        bindSummary(ByeDpiStore.KEY_PROXY_IP, false);
        bindSummary(ByeDpiStore.KEY_PROXY_USERNAME, false);
        bindSummary(ByeDpiStore.KEY_PROXY_PASSWORD, false);
        bindSummary(ByeDpiStore.KEY_HOSTS_BLACKLIST, true);
        bindSummary(ByeDpiStore.KEY_HOSTS_WHITELIST, true);
        bindSummary(ByeDpiStore.KEY_FAKE_SNI, false);
        bindSummary(ByeDpiStore.KEY_OOB_DATA, false);
        bindSummary(ByeDpiStore.KEY_CMD_ARGS, true);
        bindSummary(ByeDpiStore.KEY_PROXYTEST_SNI, false);
        bindStrategyListSummary();

        bindDropdown(ByeDpiStore.KEY_HOSTS_MODE);
        bindDropdown(ByeDpiStore.KEY_DESYNC_METHOD);

        Preference openTargets = findPreference(ByeDpiStore.KEY_PROXYTEST_OPEN_TARGETS);
        if (openTargets != null) {
            openTargets.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(ByeDpiTargetsActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference openRunner = findPreference(ByeDpiStore.KEY_PROXYTEST_OPEN_RUNNER);
        if (openRunner != null) {
            openRunner.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                startActivity(ByeDpiStrategyTestActivity.createIntent(requireContext()));
                return true;
            });
        }
    }

    private void bindSwitch(String key) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSliderStep(getListView() != null ? getListView() : requireView());
            if (shouldWarnBeforeDisablingSocksAuth(key, preference, newValue)) {
                showWarningBeforeApplying(
                    () -> {
                        SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
                        if (preferences != null) {
                            preferences.edit().putBoolean(key, false).apply();
                        }
                    },
                    getString(R.string.warning_socks_auth_disable)
                );
                return false;
            }
            return true;
        });
    }

    private void bindNumeric(String key, boolean signed, int descriptionResId) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnBindEditTextListener(editText ->
            editText.setInputType(
                signed ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED : InputType.TYPE_CLASS_NUMBER
            )
        );
        preference.setSummaryProvider(pref -> {
            String value = ((EditTextPreference) pref).getText();
            String normalizedValue = TextUtils.isEmpty(value) ? getString(R.string.byedpi_value_not_set) : value;
            if (descriptionResId == 0) {
                return normalizedValue;
            }
            return normalizedValue + "\n" + getString(descriptionResId);
        });
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
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            if (shouldWarnBeforeAcceptingWeakPassword(key, newValue)) {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                showWarningBeforeApplying(
                    () -> {
                        SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
                        if (preferences != null) {
                            preferences.edit().putString(key, newValue == null ? "" : String.valueOf(newValue)).apply();
                        }
                    },
                    getString(R.string.warning_socks_password_weak)
                );
                return false;
            }
            return true;
        });
        preference.setSummaryProvider(pref -> {
            String value = ((EditTextPreference) pref).getText();
            return TextUtils.isEmpty(value) ? getString(R.string.byedpi_value_not_set) : value;
        });
    }

    private void bindStrategyListSummary() {
        EditTextPreference preference = findPreference(ByeDpiStore.KEY_PROXYTEST_CUSTOM_STRATEGIES);
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
        preference.setSummaryProvider(pref -> {
            String value = ((EditTextPreference) pref).getText();
            if (TextUtils.isEmpty(value)) {
                return getString(R.string.byedpi_strategy_list_empty);
            }
            int count = 0;
            for (String line : value.split("\n")) {
                if (!TextUtils.isEmpty(line.trim())) {
                    count++;
                }
            }
            return getResources().getQuantityString(R.plurals.byedpi_strategy_count, count, count);
        });
    }

    private void bindDropdown(String key) {
        DropDownPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummaryProvider(DropDownPreference.SimpleSummaryProvider.getInstance());
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            return true;
        });
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
            refreshVisibility();
            if (
                isRuntimeAffectingKey(key) &&
                XrayStore.getBackendType(requireContext()) == BackendType.XRAY &&
                ProxyTunnelService.isActive()
            ) {
                ProxyTunnelService.requestReconnect(requireContext(), "ByeDPI settings changed");
            }
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
        ByeDpiSettings settings = ByeDpiStore.getSettings(requireContext());
        syncSwitch(ByeDpiStore.KEY_AUTO_START_WITH_XRAY, settings.launchOnXrayStart);
        syncSwitch(ByeDpiStore.KEY_USE_COMMAND_SETTINGS, settings.useCommandLineSettings);
        syncSwitch(ByeDpiStore.KEY_PROXY_AUTH_ENABLED, settings.proxyAuthEnabled);
        syncSwitch(ByeDpiStore.KEY_NO_DOMAIN, settings.noDomain);
        syncSwitch(ByeDpiStore.KEY_TCP_FAST_OPEN, settings.tcpFastOpen);
        syncSwitch(ByeDpiStore.KEY_SPLIT_AT_HOST, settings.splitAtHost);
        syncSwitch(ByeDpiStore.KEY_DROP_SACK, settings.dropSack);
        syncSwitch(ByeDpiStore.KEY_DESYNC_HTTP, settings.desyncHttp);
        syncSwitch(ByeDpiStore.KEY_DESYNC_HTTPS, settings.desyncHttps);
        syncSwitch(ByeDpiStore.KEY_DESYNC_UDP, settings.desyncUdp);
        syncSwitch(ByeDpiStore.KEY_HOST_MIXED_CASE, settings.hostMixedCase);
        syncSwitch(ByeDpiStore.KEY_DOMAIN_MIXED_CASE, settings.domainMixedCase);
        syncSwitch(ByeDpiStore.KEY_HOST_REMOVE_SPACES, settings.hostRemoveSpaces);
        syncSwitch(ByeDpiStore.KEY_TLSREC_ENABLED, settings.tlsRecordSplit);
        syncSwitch(ByeDpiStore.KEY_TLSREC_AT_SNI, settings.tlsRecordSplitAtSni);
        syncSwitch(ByeDpiStore.KEY_PROXYTEST_USE_CUSTOM_STRATEGIES, settings.proxyTestUseCustomStrategies);

        syncEditText(ByeDpiStore.KEY_PROXY_IP, settings.proxyIp);
        syncEditText(ByeDpiStore.KEY_PROXY_PORT, String.valueOf(settings.proxyPort));
        syncEditText(ByeDpiStore.KEY_PROXY_USERNAME, settings.proxyUsername);
        syncEditText(ByeDpiStore.KEY_PROXY_PASSWORD, settings.proxyPassword);
        syncEditText(ByeDpiStore.KEY_MAX_CONNECTIONS, String.valueOf(settings.maxConnections));
        syncEditText(ByeDpiStore.KEY_BUFFER_SIZE, String.valueOf(settings.bufferSize));
        syncEditText(ByeDpiStore.KEY_DEFAULT_TTL, String.valueOf(settings.defaultTtl));
        syncEditText(ByeDpiStore.KEY_SPLIT_POSITION, String.valueOf(settings.splitPosition));
        syncEditText(ByeDpiStore.KEY_FAKE_TTL, String.valueOf(settings.fakeTtl));
        syncEditText(ByeDpiStore.KEY_FAKE_OFFSET, String.valueOf(settings.fakeOffset));
        syncEditText(ByeDpiStore.KEY_FAKE_SNI, settings.fakeSni);
        syncEditText(ByeDpiStore.KEY_OOB_DATA, settings.oobData);
        syncEditText(ByeDpiStore.KEY_HOSTS_BLACKLIST, settings.hostsBlacklist);
        syncEditText(ByeDpiStore.KEY_HOSTS_WHITELIST, settings.hostsWhitelist);
        syncEditText(ByeDpiStore.KEY_TLSREC_POSITION, String.valueOf(settings.tlsRecordSplitPosition));
        syncEditText(ByeDpiStore.KEY_UDP_FAKE_COUNT, String.valueOf(settings.udpFakeCount));
        syncEditText(ByeDpiStore.KEY_CMD_ARGS, settings.rawCommandArgs);
        syncEditText(ByeDpiStore.KEY_PROXYTEST_DELAY, String.valueOf(settings.proxyTestDelaySeconds));
        syncEditText(ByeDpiStore.KEY_PROXYTEST_REQUESTS, String.valueOf(settings.proxyTestRequests));
        syncEditText(ByeDpiStore.KEY_PROXYTEST_LIMIT, String.valueOf(settings.proxyTestConcurrencyLimit));
        syncEditText(ByeDpiStore.KEY_PROXYTEST_TIMEOUT, String.valueOf(settings.proxyTestTimeoutSeconds));
        syncEditText(ByeDpiStore.KEY_PROXYTEST_SNI, settings.proxyTestSni);
        syncEditText(ByeDpiStore.KEY_PROXYTEST_CUSTOM_STRATEGIES, settings.proxyTestCustomStrategies);
        syncDropdown(ByeDpiStore.KEY_HOSTS_MODE, settings.hostsMode.prefValue);
        syncDropdown(ByeDpiStore.KEY_DESYNC_METHOD, settings.desyncMethod.prefValue);

        Preference targets = findPreference(ByeDpiStore.KEY_PROXYTEST_OPEN_TARGETS);
        if (targets != null) {
            targets.setSummary(ByeDpiStore.buildTargetsSummary(requireContext()));
        }
    }

    private void refreshAvailability() {
        boolean available = XrayStore.getBackendType(requireContext()) == BackendType.XRAY;
        SwitchPreferenceCompat autoStartPreference = findPreference(ByeDpiStore.KEY_AUTO_START_WITH_XRAY);
        if (autoStartPreference != null) {
            autoStartPreference.setEnabled(available);
            autoStartPreference.setSummary(
                available ? getString(R.string.byedpi_auto_start_summary) : getString(R.string.byedpi_xray_only_summary)
            );
        }
    }

    private void refreshVisibility() {
        ByeDpiSettings settings = ByeDpiStore.getSettings(requireContext());
        boolean useCommandSettings = settings.useCommandLineSettings;
        boolean useCustomStrategies = settings.proxyTestUseCustomStrategies;
        PreferenceCategory uiCategory = findPreference("pref_category_bydpi_ui");
        PreferenceCategory commandCategory = findPreference("pref_category_bydpi_cmd");
        PreferenceCategory uiProxyCategory = findPreference("pref_category_bydpi_proxy");
        EditTextPreference hostsBlacklist = findPreference(ByeDpiStore.KEY_HOSTS_BLACKLIST);
        EditTextPreference hostsWhitelist = findPreference(ByeDpiStore.KEY_HOSTS_WHITELIST);
        EditTextPreference fakeTtl = findPreference(ByeDpiStore.KEY_FAKE_TTL);
        EditTextPreference fakeOffset = findPreference(ByeDpiStore.KEY_FAKE_OFFSET);
        EditTextPreference fakeSni = findPreference(ByeDpiStore.KEY_FAKE_SNI);
        EditTextPreference oobData = findPreference(ByeDpiStore.KEY_OOB_DATA);
        EditTextPreference tlsrecPosition = findPreference(ByeDpiStore.KEY_TLSREC_POSITION);
        SwitchPreferenceCompat tlsrecAtSni = findPreference(ByeDpiStore.KEY_TLSREC_AT_SNI);
        EditTextPreference customStrategies = findPreference(ByeDpiStore.KEY_PROXYTEST_CUSTOM_STRATEGIES);
        EditTextPreference proxyUsername = findPreference(ByeDpiStore.KEY_PROXY_USERNAME);
        EditTextPreference proxyPassword = findPreference(ByeDpiStore.KEY_PROXY_PASSWORD);
        Preference openRunner = findPreference(ByeDpiStore.KEY_PROXYTEST_OPEN_RUNNER);

        if (uiCategory != null) {
            uiCategory.setVisible(!useCommandSettings);
        }
        if (uiProxyCategory != null) {
            uiProxyCategory.setVisible(true);
        }
        if (proxyUsername != null) {
            proxyUsername.setEnabled(settings.proxyAuthEnabled);
        }
        if (proxyPassword != null) {
            proxyPassword.setEnabled(settings.proxyAuthEnabled);
        }
        if (commandCategory != null) {
            commandCategory.setVisible(useCommandSettings);
        }
        for (String key : UI_EDITOR_KEYS) {
            Preference preference = findPreference(key);
            if (preference != null) {
                preference.setVisible(!useCommandSettings);
            }
        }
        if (hostsBlacklist != null) {
            hostsBlacklist.setVisible(!useCommandSettings && settings.hostsMode == ByeDpiSettings.HostsMode.BLACKLIST);
        }
        if (hostsWhitelist != null) {
            hostsWhitelist.setVisible(!useCommandSettings && settings.hostsMode == ByeDpiSettings.HostsMode.WHITELIST);
        }
        boolean fakeVisible = !useCommandSettings && settings.desyncMethod == ByeDpiSettings.DesyncMethod.FAKE;
        if (fakeTtl != null) {
            fakeTtl.setVisible(fakeVisible);
        }
        if (fakeOffset != null) {
            fakeOffset.setVisible(fakeVisible);
        }
        if (fakeSni != null) {
            fakeSni.setVisible(fakeVisible);
        }
        boolean oobVisible =
            !useCommandSettings &&
            (settings.desyncMethod == ByeDpiSettings.DesyncMethod.OOB ||
                settings.desyncMethod == ByeDpiSettings.DesyncMethod.DISOOB);
        if (oobData != null) {
            oobData.setVisible(oobVisible);
        }
        if (tlsrecPosition != null) {
            tlsrecPosition.setVisible(!useCommandSettings && settings.tlsRecordSplit);
        }
        if (tlsrecAtSni != null) {
            tlsrecAtSni.setVisible(!useCommandSettings && settings.tlsRecordSplit);
        }
        if (customStrategies != null) {
            customStrategies.setVisible(useCustomStrategies);
        }
        if (openRunner != null) {
            List<String> targets = ByeDpiStore.getProxyTestTargets(requireContext());
            List<String> strategies = ByeDpiStore.getProxyTestStrategies(requireContext());
            openRunner.setEnabled(!targets.isEmpty() && !strategies.isEmpty());
        }
    }

    private void syncSwitch(String key, boolean value) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference != null && preference.isChecked() != value) {
            preference.setChecked(value);
        }
    }

    private void syncEditText(String key, @Nullable String value) {
        EditTextPreference preference = findPreference(key);
        String normalized = value == null ? "" : value;
        if (preference != null && !TextUtils.equals(preference.getText(), normalized)) {
            preference.setText(normalized);
        }
    }

    private void syncDropdown(String key, @Nullable String value) {
        DropDownPreference preference = findPreference(key);
        String normalized = TextUtils.isEmpty(value) ? "" : value;
        if (preference != null && !TextUtils.equals(preference.getValue(), normalized)) {
            preference.setValue(normalized);
        }
    }

    private boolean isRuntimeAffectingKey(@Nullable String key) {
        return !TextUtils.isEmpty(key) && RUNTIME_AFFECTING_KEYS.contains(key);
    }

    private boolean shouldWarnBeforeDisablingSocksAuth(String key, SwitchPreferenceCompat preference, Object newValue) {
        return (
            TextUtils.equals(key, ByeDpiStore.KEY_PROXY_AUTH_ENABLED) &&
            preference.isChecked() &&
            Boolean.FALSE.equals(newValue)
        );
    }

    private boolean shouldWarnBeforeAcceptingWeakPassword(String key, Object newValue) {
        if (!TextUtils.equals(key, ByeDpiStore.KEY_PROXY_PASSWORD)) {
            return false;
        }
        ByeDpiSettings settings = ByeDpiStore.getSettings(requireContext());
        if (!settings.proxyAuthEnabled) {
            return false;
        }
        String candidatePassword = newValue == null ? "" : String.valueOf(newValue);
        return SocksAuthSecurity.isPasswordTooSimple(settings.proxyUsername, candidatePassword);
    }

    private void showWarningBeforeApplying(Runnable action, String warningText) {
        pendingWarningConfirmedAction = action;
        warningLauncher.launch(
            WarningConfirmActivity.createIntent(requireContext(), warningText, SOCKS_AUTH_DISABLE_WARNING_DELAY_SECONDS)
        );
    }
}
