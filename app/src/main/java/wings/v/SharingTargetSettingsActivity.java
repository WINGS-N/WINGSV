package wings.v;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.SwitchItemView;
import java.net.NetworkInterface;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kotlin.Unit;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.XrayStore;
import wings.v.databinding.ActivitySharingTargetSettingsBinding;
import wings.v.service.ProxyTunnelService;
import wings.v.vpnhotspot.bridge.VpnHotspotBridge;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.CommentRequired",
        "PMD.CommentDefaultAccessModifier",
        "PMD.FieldDeclarationsShouldBeAtStartOfClass",
        "PMD.ExcessiveImports",
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
        "PMD.ImplicitFunctionalInterface",
    }
)
public class SharingTargetSettingsActivity extends AppCompatActivity {

    private static final String EXTRA_TARGET = "sharing_target";

    private interface StringPreferenceSetter {
        void set(Context context, String value);
    }

    private interface BooleanPreferenceSetter {
        void set(Context context, boolean checked);
    }

    private interface ChoiceSetter {
        void set(String value);
    }

    public enum Target {
        WIFI("wifi"),
        USB("usb");

        final String value;

        Target(String value) {
            this.value = value;
        }

        @Nullable
        static Target fromValue(@Nullable String rawValue) {
            if (rawValue == null) {
                return null;
            }
            for (Target target : values()) {
                if (target.value.equals(rawValue)) {
                    return target;
                }
            }
            return null;
        }
    }

    private ActivitySharingTargetSettingsBinding binding;
    private Target target;
    private boolean updatingUi;
    private boolean tetherOffloadOperationInFlight;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener = (
        sharedPreferences,
        key
    ) -> {
        if (key == null) {
            return;
        }
        if (
            AppPrefs.KEY_ROOT_RUNTIME_ACTIVE.equals(key) ||
            AppPrefs.KEY_ROOT_RUNTIME_TUNNEL.equals(key) ||
            AppPrefs.KEY_SHARING_UPSTREAM_INTERFACE.equals(key) ||
            AppPrefs.KEY_SHARING_FALLBACK_UPSTREAM_INTERFACE.equals(key) ||
            AppPrefs.KEY_ROOT_MODE.equals(key) ||
            AppPrefs.KEY_KERNEL_WIREGUARD.equals(key)
        ) {
            mainHandler.post(this::refreshUi);
        }
    };
    private final ExecutorService workExecutor = Executors.newSingleThreadExecutor();

    public static Intent createIntent(Context context, Target target) {
        return new Intent(context, SharingTargetSettingsActivity.class).putExtra(EXTRA_TARGET, target.value);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        target = Target.fromValue(getIntent().getStringExtra(EXTRA_TARGET));
        if (target == null) {
            finish();
            return;
        }

        binding = ActivitySharingTargetSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);

        bindCommonRows();
        bindTargetRows();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).registerOnSharedPreferenceChangeListener(
            preferenceChangeListener
        );
        refreshUi();
    }

    @Override
    protected void onPause() {
        PreferenceManager.getDefaultSharedPreferences(
            getApplicationContext()
        ).unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        workExecutor.shutdownNow();
    }

    private void bindCommonRows() {
        binding.rowCurrentUpstreams.setTitle(getString(R.string.sharing_current_upstreams_title));

        configureSwitch(
            binding.itemDisableIpv6,
            R.string.sharing_disable_ipv6_title,
            R.string.sharing_disable_ipv6_summary,
            AppPrefs::setSharingDisableIpv6Enabled
        );
        configureSwitch(
            binding.itemDhcpWorkaround,
            R.string.sharing_dhcp_title,
            R.string.sharing_dhcp_summary,
            AppPrefs::setSharingDhcpWorkaroundEnabled
        );
        configureSwitch(
            binding.itemRepeaterSafeMode,
            R.string.sharing_repeater_safe_mode_title,
            R.string.sharing_repeater_safe_mode_summary,
            AppPrefs::setSharingRepeaterSafeModeEnabled
        );
        configureSwitch(
            binding.itemTempHotspotUseSystem,
            R.string.sharing_temp_hotspot_use_system_title,
            R.string.sharing_temp_hotspot_use_system_summary,
            AppPrefs::setSharingTempHotspotUseSystemEnabled
        );

        configureActionRow(binding.rowUpstreamInterface, R.string.sharing_upstream_title, null, view ->
            showInterfaceInputDialog(
                R.string.sharing_upstream_title,
                AppPrefs.getSharingUpstreamInterface(this),
                AppPrefs::setSharingUpstreamInterface
            )
        );
        configureActionRow(binding.rowFallbackUpstreamInterface, R.string.sharing_fallback_upstream_title, null, view ->
            showInterfaceInputDialog(
                R.string.sharing_fallback_upstream_title,
                AppPrefs.getSharingFallbackUpstreamInterface(this),
                AppPrefs::setSharingFallbackUpstreamInterface
            )
        );
        configureActionRow(binding.rowMasqueradeMode, R.string.sharing_masquerade_title, null, view ->
            showSingleChoiceDialog(
                R.string.sharing_masquerade_title,
                R.array.sharing_masquerade_entries,
                R.array.sharing_masquerade_values,
                AppPrefs.getSharingMasqueradeMode(this),
                value -> {
                    AppPrefs.setSharingMasqueradeMode(this, value);
                    refreshUi();
                }
            )
        );
        configureActionRow(binding.rowWifiLock, R.string.sharing_wifi_lock_title, null, view ->
            showSingleChoiceDialog(
                R.string.sharing_wifi_lock_title,
                R.array.sharing_wifi_lock_entries,
                R.array.sharing_wifi_lock_values,
                AppPrefs.getSharingWifiLockMode(this),
                value -> {
                    AppPrefs.setSharingWifiLockMode(this, value);
                    refreshUi();
                }
            )
        );
        configureTetherOffloadSwitch();
    }

    private void bindTargetRows() {
        if (target == Target.WIFI) {
            binding.toolbarLayout.setTitle(getString(R.string.sharing_wifi));
            binding.textHeaderTitle.setText(R.string.sharing_wifi);
            binding.textHeaderSummary.setText(R.string.sharing_wifi_detail_subtitle);

            showView(binding.rowWifiLock, true);
            showView(binding.rowTetherOffload, true);

            showView(binding.rowCurrentUpstreams, false);
            showView(binding.rowUpstreamInterface, false);
            showView(binding.rowFallbackUpstreamInterface, false);
            showView(binding.rowMasqueradeMode, false);
            showView(binding.itemDisableIpv6, false);
            showView(binding.itemDhcpWorkaround, false);
            showView(binding.itemRepeaterSafeMode, false);
            showView(binding.itemTempHotspotUseSystem, false);
            return;
        }

        binding.toolbarLayout.setTitle(getString(R.string.sharing_usb));
        binding.textHeaderTitle.setText(R.string.sharing_usb);
        binding.textHeaderSummary.setText(R.string.sharing_usb_detail_subtitle);

        showView(binding.rowCurrentUpstreams, false);
        showView(binding.rowUpstreamInterface, false);
        showView(binding.rowFallbackUpstreamInterface, false);
        showView(binding.rowMasqueradeMode, false);
        showView(binding.itemDisableIpv6, false);
        showView(binding.itemDhcpWorkaround, true);

        showView(binding.rowWifiLock, false);
        showView(binding.rowTetherOffload, false);
        showView(binding.itemRepeaterSafeMode, false);
        showView(binding.itemTempHotspotUseSystem, false);
    }

    private void configureSwitch(
        SwitchItemView itemView,
        int titleRes,
        int summaryRes,
        BooleanPreferenceSetter setter
    ) {
        itemView.setTitle(getString(titleRes));
        itemView.setSummary(getString(summaryRes));
        itemView.setOnClickListener(view ->
            applySwitchChange(itemView, resolveTargetCheckedStateForRowTap(itemView), setter, view)
        );
        itemView.setOnCheckedChangedListener((viewId, checked) -> {
            if (!updatingUi) {
                applySwitchChange(itemView, checked, setter, itemView);
            }
            return Unit.INSTANCE;
        });
    }

    private boolean resolveTargetCheckedStateForRowTap(SwitchItemView itemView) {
        return itemView.getSeparateSwitch() ? !itemView.isChecked() : itemView.isChecked();
    }

    private void configureActionRow(
        CardItemView itemView,
        int titleRes,
        @Nullable Integer summaryRes,
        View.OnClickListener listener
    ) {
        itemView.setTitle(getString(titleRes));
        if (summaryRes != null) {
            itemView.setSummary(getString(summaryRes));
        }
        itemView.setOnClickListener(view -> {
            Haptics.softSelection(view);
            listener.onClick(view);
        });
    }

    private void applySwitchChange(
        SwitchItemView itemView,
        boolean checked,
        BooleanPreferenceSetter setter,
        View sourceView
    ) {
        if (itemView.isChecked() != checked) {
            itemView.setChecked(checked);
        }
        setter.set(this, checked);
        Haptics.softSliderStep(sourceView);
        refreshUi();
    }

    private void refreshUi() {
        binding.rowCurrentUpstreams.setSummary(buildCurrentUpstreamsSummary());
        binding.rowUpstreamInterface.setSummary(
            summarizeInterfaceValue(AppPrefs.getSharingUpstreamInterface(this), R.string.sharing_upstream_summary_auto)
        );
        binding.rowFallbackUpstreamInterface.setSummary(
            summarizeInterfaceValue(
                AppPrefs.getSharingFallbackUpstreamInterface(this),
                R.string.sharing_fallback_upstream_summary_auto
            )
        );
        binding.rowMasqueradeMode.setSummary(getMasqueradeModeLabel(AppPrefs.getSharingMasqueradeMode(this)));
        binding.rowWifiLock.setSummary(getWifiLockLabel(AppPrefs.getSharingWifiLockMode(this)));
        updateSwitch(binding.itemDisableIpv6, AppPrefs.isSharingDisableIpv6Enabled(this));
        updateSwitch(binding.itemDhcpWorkaround, AppPrefs.isSharingDhcpWorkaroundEnabled(this));
        updateSwitch(binding.itemRepeaterSafeMode, AppPrefs.isSharingRepeaterSafeModeEnabled(this));
        updateSwitch(binding.itemTempHotspotUseSystem, AppPrefs.isSharingTempHotspotUseSystemEnabled(this));
        updateSwitch(binding.rowTetherOffload, VpnHotspotBridge.isTetherOffloadEnabled(this));
        binding.rowTetherOffload.setEnabled(!tetherOffloadOperationInFlight);
    }

    private void updateSwitch(SwitchItemView itemView, boolean checked) {
        itemView.setChecked(checked);
    }

    private void showView(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void configureTetherOffloadSwitch() {
        binding.rowTetherOffload.setTitle(getString(R.string.sharing_offload_title));
        binding.rowTetherOffload.setSummary(getString(R.string.sharing_offload_summary));
        binding.rowTetherOffload.setOnClickListener(view ->
            applyTetherOffloadChange(!binding.rowTetherOffload.isChecked(), view)
        );
        binding.rowTetherOffload.setOnCheckedChangedListener((viewId, checked) -> {
            if (!updatingUi) {
                applyTetherOffloadChange(checked, binding.rowTetherOffload);
            }
            return Unit.INSTANCE;
        });
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void applyTetherOffloadChange(boolean enabled, View sourceView) {
        if (tetherOffloadOperationInFlight) {
            return;
        }
        if (binding.rowTetherOffload.isChecked() != enabled) {
            binding.rowTetherOffload.setChecked(enabled);
        }
        tetherOffloadOperationInFlight = true;
        binding.rowTetherOffload.setEnabled(false);
        Haptics.softSliderStep(sourceView);
        Context appContext = getApplicationContext();
        workExecutor.execute(() -> {
            String error = null;
            try {
                VpnHotspotBridge.setTetherOffloadEnabled(appContext, enabled);
                if (ProxyTunnelService.isActive()) {
                    appContext.startService(ProxyTunnelService.createReapplySharingIntent(appContext));
                }
            } catch (Exception exception) {
                error = exception.getMessage();
            }
            String finalError = error;
            mainHandler.post(() -> {
                tetherOffloadOperationInFlight = false;
                refreshUi();
                if (!TextUtils.isEmpty(finalError)) {
                    Toast.makeText(
                        this,
                        getString(R.string.sharing_action_failed_detail, finalError),
                        Toast.LENGTH_SHORT
                    ).show();
                }
            });
        });
    }

    private String summarizeInterfaceValue(String value, int autoSummaryRes) {
        return TextUtils.isEmpty(value) ? getString(autoSummaryRes) : value;
    }

    private String buildCurrentUpstreamsSummary() {
        String current = resolveCurrentUpstreamName();
        String fallback = AppPrefs.getSharingFallbackUpstreamInterface(this);
        if (TextUtils.isEmpty(fallback)) {
            fallback = detectActiveInterfaceName();
        }
        if (TextUtils.isEmpty(current)) {
            current = getString(R.string.sharing_value_not_available);
        }
        if (TextUtils.isEmpty(fallback)) {
            fallback = getString(R.string.sharing_value_auto);
        }
        return getString(R.string.sharing_current_upstreams_summary, current, fallback);
    }

    private String resolveCurrentUpstreamName() {
        String configured = AppPrefs.getSharingUpstreamInterface(this);
        if (!TextUtils.isEmpty(configured)) {
            return configured;
        }
        if (shouldUseVpnServiceUpstream()) {
            return getString(R.string.sharing_value_vpn_service);
        }
        if (AppPrefs.isRootModeEnabled(this)) {
            String rootTunnelName = AppPrefs.getRootRuntimeRecoveryTunnelHint(this);
            if (isInterfacePresent(rootTunnelName)) {
                return rootTunnelName;
            }
        }
        return detectActiveInterfaceName();
    }

    private boolean shouldUseVpnServiceUpstream() {
        BackendType backendType = XrayStore.getBackendType(this);
        if (
            (backendType != null && backendType.usesXrayCore()) ||
            (backendType != null && backendType.usesAmneziaSettings())
        ) {
            return true;
        }
        return (
            backendType != null &&
            backendType.supportsKernelWireGuard() &&
            AppPrefs.isRootModeEnabled(this) &&
            !AppPrefs.isKernelWireGuardEnabled(this)
        );
    }

    private boolean isInterfacePresent(@Nullable String interfaceName) {
        if (TextUtils.isEmpty(interfaceName)) {
            return false;
        }
        try {
            return NetworkInterface.getByName(interfaceName) != null;
        } catch (java.net.SocketException ignored) {
            return false;
        }
    }

    private String detectActiveInterfaceName() {
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return getString(R.string.sharing_value_not_available);
        }
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return getString(R.string.sharing_value_not_available);
        }
        LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
        String interfaceName = linkProperties != null ? linkProperties.getInterfaceName() : null;
        if (TextUtils.isEmpty(interfaceName)) {
            return getString(R.string.sharing_value_not_available);
        }
        return interfaceName;
    }

    private String getMasqueradeModeLabel(String value) {
        if (AppPrefs.SHARING_MASQUERADE_NONE.equals(value)) {
            return getString(R.string.sharing_masquerade_none);
        }
        if (AppPrefs.SHARING_MASQUERADE_SIMPLE.equals(value)) {
            return getString(R.string.sharing_masquerade_simple);
        }
        return getString(R.string.sharing_masquerade_netd);
    }

    private String getWifiLockLabel(String value) {
        if (AppPrefs.SHARING_WIFI_LOCK_FULL.equals(value)) {
            return getString(R.string.sharing_wifi_lock_full);
        }
        if (AppPrefs.SHARING_WIFI_LOCK_HIGH_PERF.equals(value)) {
            return getString(R.string.sharing_wifi_lock_high_perf);
        }
        if (AppPrefs.SHARING_WIFI_LOCK_LOW_LATENCY.equals(value)) {
            return getString(R.string.sharing_wifi_lock_low_latency);
        }
        return getString(R.string.sharing_wifi_lock_system);
    }

    private void showInterfaceInputDialog(int titleRes, @Nullable String initialValue, StringPreferenceSetter setter) {
        EditText editText = new EditText(this);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setHint(R.string.sharing_input_upstream_hint);
        editText.setText(initialValue == null ? "" : initialValue);
        editText.setSelection(editText.getText().length());

        new AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(editText)
            .setPositiveButton(R.string.sharing_edit_dialog_save, (dialog, which) -> {
                setter.set(this, editText.getText() == null ? "" : editText.getText().toString().trim());
                refreshUi();
            })
            .setNegativeButton(R.string.sharing_edit_dialog_cancel, null)
            .show();
    }

    private void showSingleChoiceDialog(
        int titleRes,
        int entriesRes,
        int valuesRes,
        @Nullable String selectedValue,
        ChoiceSetter setter
    ) {
        CharSequence[] entries = getResources().getTextArray(entriesRes);
        String[] values = getResources().getStringArray(valuesRes);
        int selectedIndex = 0;
        if (!TextUtils.isEmpty(selectedValue)) {
            for (int index = 0; index < values.length; index++) {
                if (selectedValue.equals(values[index])) {
                    selectedIndex = index;
                    break;
                }
            }
        }

        new AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(entries, selectedIndex, (dialog, which) -> {
                if (which >= 0 && which < values.length) {
                    setter.set(values[which]);
                }
                dialog.dismiss();
                refreshUi();
            })
            .setNegativeButton(R.string.sharing_edit_dialog_cancel, null)
            .show();
    }
}
