package wings.v.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.NetworkInterface;

import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.SwitchItemView;
import kotlin.Unit;
import wings.v.R;
import wings.v.SharingTargetSettingsActivity;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.RootUtils;
import wings.v.core.TetherType;
import wings.v.databinding.FragmentSharingBinding;
import wings.v.service.ProxyTunnelService;

public class SharingFragment extends Fragment {
    private static final long VERIFY_INTERVAL_MS = 350L;
    private static final long VERIFY_TIMEOUT_MS = 8_000L;

    private interface StringPreferenceSetter {
        void set(Context context, String value);
    }

    private interface SettingBooleanSetter {
        void set(boolean checked);
    }

    private interface ChoiceSetter {
        void set(String value);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener =
            (sharedPreferences, key) -> {
                if (!isAdded() || key == null) {
                    return;
                }
                if (AppPrefs.KEY_ROOT_RUNTIME_ACTIVE.equals(key)
                        || AppPrefs.KEY_ROOT_RUNTIME_TUNNEL.equals(key)
                        || AppPrefs.KEY_SHARING_UPSTREAM_INTERFACE.equals(key)
                        || AppPrefs.KEY_SHARING_FALLBACK_UPSTREAM_INTERFACE.equals(key)
                        || AppPrefs.KEY_ROOT_MODE.equals(key)) {
                    mainHandler.post(this::refreshUi);
                }
            };
    private final BroadcastReceiver tetherStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Set<TetherType> types = TetherType.readEnabledTypes(intent);
            enabledTypes = types.isEmpty() ? EnumSet.noneOf(TetherType.class) : EnumSet.copyOf(types);
            refreshUi();
        }
    };

    private FragmentSharingBinding binding;
    private Set<TetherType> enabledTypes = EnumSet.noneOf(TetherType.class);
    private boolean receiverRegistered;
    private boolean operationInFlight;
    private boolean updatingUi;
    private Runnable verificationRunnable;

    public SharingFragment() {
        super(R.layout.fragment_sharing);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentSharingBinding.bind(view);

        bindTetherToggleWithDetails(
                binding.itemWifi,
                TetherType.WIFI,
                R.string.sharing_wifi,
                SharingTargetSettingsActivity.Target.WIFI
        );
        bindTetherToggleWithDetails(
                binding.itemUsb,
                TetherType.USB,
                R.string.sharing_usb,
                SharingTargetSettingsActivity.Target.USB
        );
        bindSimpleTetherToggle(binding.itemBluetooth, TetherType.BLUETOOTH, R.string.sharing_bluetooth);
        bindSimpleTetherToggle(binding.itemEthernet, TetherType.ETHERNET, R.string.sharing_ethernet);
        binding.itemEthernet.setVisibility(TetherType.isEthernetSupported() ? View.VISIBLE : View.GONE);

        configureActionRow(binding.rowCleanRoutes, R.string.sharing_clean_title, R.string.sharing_clean_summary, this::onCleanRoutesRequested);
        binding.rowCurrentUpstreams.setTitle(getString(R.string.sharing_current_upstreams_title));
        configureActionRow(binding.rowUpstreamInterface, R.string.sharing_upstream_title, null, v ->
                showInterfaceInputDialog(
                        R.string.sharing_upstream_title,
                        AppPrefs.getSharingUpstreamInterface(requireContext()),
                        AppPrefs::setSharingUpstreamInterface
                )
        );
        configureActionRow(binding.rowFallbackUpstreamInterface, R.string.sharing_fallback_upstream_title, null, v ->
                showInterfaceInputDialog(
                        R.string.sharing_fallback_upstream_title,
                        AppPrefs.getSharingFallbackUpstreamInterface(requireContext()),
                        AppPrefs::setSharingFallbackUpstreamInterface
                )
        );
        configureActionRow(binding.rowMasqueradeMode, R.string.sharing_masquerade_title, null, v ->
                showSingleChoiceDialog(
                        R.string.sharing_masquerade_title,
                        R.array.sharing_masquerade_entries,
                        R.array.sharing_masquerade_values,
                        AppPrefs.getSharingMasqueradeMode(requireContext()),
                        value -> {
                            AppPrefs.setSharingMasqueradeMode(requireContext(), value);
                            refreshUi();
                        }
                )
        );

        configureSettingSwitch(binding.itemDisableIpv6, R.string.sharing_disable_ipv6_title, R.string.sharing_disable_ipv6_summary,
                checked -> AppPrefs.setSharingDisableIpv6Enabled(requireContext(), checked));
        configureSettingSwitch(
                binding.itemAutoStartServices,
                R.string.sharing_auto_start_title,
                R.string.sharing_auto_start_summary,
                checked -> AppPrefs.setSharingAutoStartOnBootEnabled(requireContext(), checked)
        );

        refreshStickyState();
        refreshUi();
    }

    @Override
    public void onStart() {
        super.onStart();
        PreferenceManager.getDefaultSharedPreferences(requireContext().getApplicationContext())
                .registerOnSharedPreferenceChangeListener(preferenceChangeListener);
        registerTetherReceiver();
        refreshStickyState();
        refreshUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    public void onStop() {
        super.onStop();
        PreferenceManager.getDefaultSharedPreferences(requireContext().getApplicationContext())
                .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        unregisterTetherReceiver();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelVerification();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void bindSimpleTetherToggle(SwitchItemView itemView, TetherType type, int titleRes) {
        itemView.setTitle(getString(titleRes));
        itemView.setOnClickListener(v -> {
            if (!itemView.isEnabled()) {
                return;
            }
            onTetherToggleRequested(type, resolveTargetCheckedStateForRowTap(itemView), v);
        });
        itemView.setOnCheckedChangedListener((viewId, checked) -> {
            if (!updatingUi) {
                onTetherToggleRequested(type, checked, itemView);
            }
            return Unit.INSTANCE;
        });
    }

    private void bindTetherToggleWithDetails(SwitchItemView itemView,
                                             TetherType type,
                                             int titleRes,
                                             SharingTargetSettingsActivity.Target target) {
        itemView.setTitle(getString(titleRes));
        itemView.setOnClickListener(v -> {
            if (!itemView.isEnabled()) {
                return;
            }
            Haptics.softSelection(v);
            startActivity(SharingTargetSettingsActivity.createIntent(requireContext(), target));
        });
        itemView.setOnCheckedChangedListener((viewId, checked) -> {
            if (!updatingUi) {
                onTetherToggleRequested(type, checked, itemView);
            }
            return Unit.INSTANCE;
        });
    }

    private void configureActionRow(CardItemView row,
                                    int titleRes,
                                    @Nullable Integer summaryRes,
                                    View.OnClickListener listener) {
        row.setTitle(getString(titleRes));
        if (summaryRes != null) {
            row.setSummary(getString(summaryRes));
        }
        row.setOnClickListener(v -> {
            Haptics.softSliderStep(v);
            listener.onClick(v);
        });
    }

    private void configureSettingSwitch(SwitchItemView itemView,
                                        int titleRes,
                                        int summaryRes,
                                        SettingBooleanSetter setter) {
        itemView.setTitle(getString(titleRes));
        itemView.setSummary(getString(summaryRes));
        itemView.setOnClickListener(v ->
                applySettingSwitchChange(itemView, resolveTargetCheckedStateForRowTap(itemView), setter, v)
        );
        itemView.setOnCheckedChangedListener((viewId, checked) -> {
            if (!updatingUi) {
                applySettingSwitchChange(itemView, checked, setter, itemView);
            }
            return Unit.INSTANCE;
        });
    }

    private boolean resolveTargetCheckedStateForRowTap(SwitchItemView itemView) {
        return itemView.getSeparateSwitch() ? !itemView.isChecked() : itemView.isChecked();
    }

    private void applySettingSwitchChange(SwitchItemView itemView,
                                          boolean checked,
                                          SettingBooleanSetter setter,
                                          View sourceView) {
        if (itemView.isChecked() != checked) {
            updatingUi = true;
            itemView.setChecked(checked);
            updatingUi = false;
        }
        setter.set(checked);
        Haptics.softSliderStep(sourceView);
        refreshUi();
    }

    private void onTetherToggleRequested(TetherType type, boolean enabled, View sourceView) {
        if (operationInFlight) {
            return;
        }
        if (!isRootModeReady()) {
            refreshUi();
            return;
        }
        Haptics.softSliderStep(sourceView);
        operationInFlight = true;
        refreshUi();
        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            String helperError = null;
            try {
                RootUtils.runRootHelper(
                        appContext,
                        "tether",
                        enabled ? "start" : "stop",
                        type.commandName
                );
            } catch (Exception e) {
                helperError = e.getMessage();
            }
            String finalHelperError = helperError;
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                verifyRequestedState(type, enabled, finalHelperError);
            });
        });
    }

    private void onCleanRoutesRequested(View sourceView) {
        if (!isRootModeReady()) {
            refreshUi();
            return;
        }
        if (!ProxyTunnelService.isActive()) {
            Toast.makeText(requireContext(), R.string.sharing_clean_requires_active, Toast.LENGTH_SHORT).show();
            return;
        }
        requireContext().startService(ProxyTunnelService.createReapplySharingIntent(requireContext()));
        Toast.makeText(requireContext(), R.string.sharing_clean_requested, Toast.LENGTH_SHORT).show();
    }

    private void verifyRequestedState(TetherType type, boolean enabled, @Nullable String helperError) {
        cancelVerification();
        final long startedAt = System.currentTimeMillis();
        verificationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAdded()) {
                    operationInFlight = false;
                    verificationRunnable = null;
                    return;
                }
                refreshStickyState();
                boolean actualEnabled = enabledTypes.contains(type);
                if (actualEnabled == enabled) {
                    operationInFlight = false;
                    verificationRunnable = null;
                    if (ProxyTunnelService.isActive()) {
                        requireContext().startService(
                                ProxyTunnelService.createReapplySharingIntent(requireContext())
                        );
                    }
                    refreshUi();
                    return;
                }
                if (System.currentTimeMillis() - startedAt >= VERIFY_TIMEOUT_MS) {
                    operationInFlight = false;
                    verificationRunnable = null;
                    refreshUi();
                    String message = helperError;
                    if (message == null || message.isEmpty()) {
                        message = getString(R.string.sharing_state_change_timeout);
                    }
                    Toast.makeText(
                            requireContext(),
                            getString(R.string.sharing_action_failed_detail, message),
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
                refreshUi();
                mainHandler.postDelayed(this, VERIFY_INTERVAL_MS);
            }
        };
        verificationRunnable.run();
    }

    private void cancelVerification() {
        if (verificationRunnable != null) {
            mainHandler.removeCallbacks(verificationRunnable);
            verificationRunnable = null;
        }
    }

    private void refreshUi() {
        if (binding == null || !isAdded()) {
            return;
        }
        boolean rootModeReady = isRootModeReady();
        binding.progressBusy.setVisibility(operationInFlight ? View.VISIBLE : View.GONE);
        binding.textBusy.setVisibility(operationInFlight ? View.VISIBLE : View.GONE);
        binding.textRootModeHint.setVisibility(rootModeReady ? View.GONE : View.VISIBLE);
        if (!rootModeReady) {
            binding.textRootModeHint.setText(R.string.sharing_root_mode_required);
        }

        updateTetherToggle(binding.itemWifi, TetherType.WIFI, rootModeReady);
        updateTetherToggle(binding.itemUsb, TetherType.USB, rootModeReady);
        updateTetherToggle(binding.itemBluetooth, TetherType.BLUETOOTH, rootModeReady);
        if (TetherType.isEthernetSupported()) {
            updateTetherToggle(binding.itemEthernet, TetherType.ETHERNET, rootModeReady);
        }

        updateBooleanSetting(binding.itemDisableIpv6, AppPrefs.isSharingDisableIpv6Enabled(requireContext()));
        updateBooleanSetting(binding.itemAutoStartServices, AppPrefs.isSharingAutoStartOnBootEnabled(requireContext()));

        binding.rowCurrentUpstreams.setSummary(buildCurrentUpstreamsSummary());
        binding.rowUpstreamInterface.setSummary(summarizeInterfaceValue(
                AppPrefs.getSharingUpstreamInterface(requireContext()),
                R.string.sharing_upstream_summary_auto
        ));
        binding.rowFallbackUpstreamInterface.setSummary(summarizeInterfaceValue(
                AppPrefs.getSharingFallbackUpstreamInterface(requireContext()),
                R.string.sharing_fallback_upstream_summary_auto
        ));
        binding.rowMasqueradeMode.setSummary(getMasqueradeModeLabel(
                AppPrefs.getSharingMasqueradeMode(requireContext())
        ));
        binding.rowCleanRoutes.setEnabled(rootModeReady && ProxyTunnelService.isActive());
    }

    private void updateTetherToggle(SwitchItemView itemView,
                                    TetherType type,
                                    boolean rootModeReady) {
        boolean enabled = enabledTypes.contains(type);
        boolean interactive = rootModeReady && !operationInFlight;
        updatingUi = true;
        itemView.setChecked(enabled);
        updatingUi = false;
        itemView.setEnabled(interactive);
        itemView.setSummary(rootModeReady
                ? getString(enabled ? R.string.sharing_state_on : R.string.sharing_state_off)
                : getString(R.string.sharing_root_mode_required));
    }

    private void updateBooleanSetting(SwitchItemView itemView, boolean checked) {
        updatingUi = true;
        itemView.setChecked(checked);
        updatingUi = false;
    }

    private String summarizeInterfaceValue(String value, int autoSummaryRes) {
        return TextUtils.isEmpty(value) ? getString(autoSummaryRes) : value;
    }

    private String buildCurrentUpstreamsSummary() {
        Context context = requireContext();
        String current = resolveCurrentUpstreamName();
        String fallback = AppPrefs.getSharingFallbackUpstreamInterface(context);
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
        Context context = requireContext();
        String configured = AppPrefs.getSharingUpstreamInterface(context);
        if (!TextUtils.isEmpty(configured)) {
            return configured;
        }
        if (AppPrefs.isRootModeEnabled(context)) {
            String rootTunnelName = AppPrefs.getRootRuntimeRecoveryTunnelHint(context);
            if (isInterfacePresent(rootTunnelName)) {
                return rootTunnelName;
            }
        }
        return detectActiveInterfaceName();
    }

    private boolean isInterfacePresent(@Nullable String interfaceName) {
        if (TextUtils.isEmpty(interfaceName)) {
            return false;
        }
        try {
            return NetworkInterface.getByName(interfaceName) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String detectActiveInterfaceName() {
        ConnectivityManager connectivityManager = requireContext().getSystemService(ConnectivityManager.class);
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

    private void showInterfaceInputDialog(int titleRes,
                                          @Nullable String initialValue,
                                          StringPreferenceSetter setter) {
        Context context = requireContext();
        EditText editText = new EditText(context);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setHint(R.string.sharing_input_upstream_hint);
        editText.setText(initialValue == null ? "" : initialValue);
        editText.setSelection(editText.getText().length());

        new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setView(editText)
                .setPositiveButton(R.string.sharing_edit_dialog_save, (dialog, which) -> {
                    setter.set(context, editText.getText() == null ? "" : editText.getText().toString().trim());
                    refreshUi();
                })
                .setNegativeButton(R.string.sharing_edit_dialog_cancel, null)
                .show();
    }

    private void showSingleChoiceDialog(int titleRes,
                                        int entriesRes,
                                        int valuesRes,
                                        @Nullable String selectedValue,
                                        ChoiceSetter setter) {
        Context context = requireContext();
        CharSequence[] entries = context.getResources().getTextArray(entriesRes);
        String[] values = context.getResources().getStringArray(valuesRes);
        int selectedIndex = 0;
        if (!TextUtils.isEmpty(selectedValue)) {
            for (int index = 0; index < values.length; index++) {
                if (selectedValue.equals(values[index])) {
                    selectedIndex = index;
                    break;
                }
            }
        }

        new AlertDialog.Builder(context)
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

    private boolean isRootModeReady() {
        return isAdded()
                && AppPrefs.isRootModeEnabled(requireContext())
                && RootUtils.isRootModeSupported(requireContext());
    }

    private void refreshStickyState() {
        if (!isAdded()) {
            return;
        }
        Intent stickyIntent;
        IntentFilter filter = new IntentFilter(TetherType.ACTION_TETHER_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stickyIntent = requireContext().registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            stickyIntent = requireContext().registerReceiver(null, filter);
        }
        Set<TetherType> types = TetherType.readEnabledTypes(stickyIntent);
        enabledTypes = types.isEmpty() ? EnumSet.noneOf(TetherType.class) : EnumSet.copyOf(types);
        AppPrefs.setSharingLastActiveTypes(requireContext(), enabledTypes);
    }

    private void registerTetherReceiver() {
        if (receiverRegistered || !isAdded()) {
            return;
        }
        IntentFilter filter = new IntentFilter(TetherType.ACTION_TETHER_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(tetherStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(tetherStateReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterTetherReceiver() {
        if (!receiverRegistered || !isAdded()) {
            return;
        }
        try {
            requireContext().unregisterReceiver(tetherStateReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }
}
