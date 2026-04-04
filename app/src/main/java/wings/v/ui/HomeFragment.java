package wings.v.ui;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import wings.v.MainActivity;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.AmneziaStore;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.ProxySettings;
import wings.v.core.PublicIpFetcher;
import wings.v.core.UiFormatter;
import wings.v.core.WingsImportParser;
import wings.v.core.XrayProfile;
import wings.v.databinding.FragmentHomeBinding;
import wings.v.service.ProxyTunnelService;

public class HomeFragment extends Fragment {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (binding == null) {
                return;
            }
            refreshUi();
            handler.postDelayed(this, 1000L);
        }
    };

    private FragmentHomeBinding binding;
    private PublicIpFetcher.IpInfo fallbackIpInfo;
    private PublicIpFetcher.Request ipRequest;
    private ObjectAnimator ipRefreshAnimator;
    private int ipRequestGeneration;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.buttonToggle.setOnClickListener(v -> {
            Haptics.powerWave(v, !ProxyTunnelService.isActive());
            ((MainActivity) requireActivity()).toggleTunnelRequested();
        });
        binding.buttonImportClipboard.setOnClickListener(v -> {
            Haptics.softSelection(v);
            importFromClipboard();
        });
        binding.buttonCopyConfig.setOnClickListener(v -> {
            Haptics.softSelection(v);
            copyCurrentConfiguration();
        });
        binding.buttonRefreshIp.setOnClickListener(v -> {
            Haptics.softSelection(v);
            refreshPublicIp();
        });
        binding.cardRuntimeNotice.addButton(getString(R.string.runtime_notice_hide), v -> {
            Haptics.softSelection(v);
            ProxyTunnelService.dismissVisibleErrorNotice();
            refreshUi();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        ProxyTunnelService.requestRuntimeSyncIfNeeded(requireContext());
        syncIpRefreshAnimation();
        requestPublicIpIfNeeded();
        handler.post(refreshRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
        stopIpRefreshAnimation();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelIpRefreshRequest();
        stopIpRefreshAnimation();
        binding = null;
    }

    private void refreshUi() {
        if (binding == null || getContext() == null) {
            return;
        }

        Context context = requireContext();
        boolean running = ProxyTunnelService.isRunning();
        boolean connecting = ProxyTunnelService.isConnecting();
        boolean active = running || connecting;
        ProxySettings settings = AppPrefs.getSettings(context);

        if (connecting) {
            binding.textServiceState.setText(R.string.service_connecting);
            binding.textServiceHint.setText(R.string.service_connecting_hint);
        } else {
            binding.textServiceState.setText(running ? R.string.service_on : R.string.service_off);
            binding.textServiceHint.setText(running ? R.string.tap_to_disconnect : R.string.tap_to_connect);
        }

        int tintColor = ContextCompat.getColor(
                context,
                android.R.color.white
        );
        if (!active) {
            tintColor = resolveThemeColor(android.R.attr.textColorPrimary, tintColor);
        }
        binding.buttonToggle.setBackgroundResource(
                active ? R.drawable.bg_power_button_on : R.drawable.bg_power_button_off
        );
        binding.buttonToggle.setImageTintList(ColorStateList.valueOf(tintColor));

        binding.textDownlink.setText(UiFormatter.formatBytesPerSecond(
                context,
                ProxyTunnelService.getRxBytesPerSecond()
        ));
        binding.textUplink.setText(UiFormatter.formatBytesPerSecond(
                context,
                ProxyTunnelService.getTxBytesPerSecond()
        ));
        binding.textRx.setText(UiFormatter.formatBytes(context, ProxyTunnelService.getRxBytes()));
        binding.textTx.setText(UiFormatter.formatBytes(context, ProxyTunnelService.getTxBytes()));

        String ip = firstNonEmpty(ProxyTunnelService.getPublicIp(), fallbackIpInfo != null ? fallbackIpInfo.ip : null);
        binding.textIp.setText(TextUtils.isEmpty(ip) ? getString(R.string.ip_loading) : ip);

        String country = firstNonEmpty(
                ProxyTunnelService.getPublicCountry(),
                fallbackIpInfo != null ? fallbackIpInfo.country : null
        );
        binding.textCountry.setText(TextUtils.isEmpty(country) ? getString(R.string.ip_unknown) : country);

        String isp = firstNonEmpty(
                ProxyTunnelService.getPublicIsp(),
                fallbackIpInfo != null ? fallbackIpInfo.isp : null
        );
        binding.textIsp.setText(TextUtils.isEmpty(isp) ? getString(R.string.ip_unknown) : isp);

        String error = ProxyTunnelService.getVisibleErrorNotice();
        if (TextUtils.isEmpty(error)) {
            binding.cardRuntimeNotice.setVisibility(View.GONE);
        } else {
            binding.cardRuntimeNotice.setVisibility(View.VISIBLE);
            binding.cardRuntimeNotice.setTitle(getString(R.string.runtime_notice_title));
            binding.cardRuntimeNotice.setSummary(getString(R.string.runtime_notice_message, error));
        }

        syncIpRefreshAnimation();

        String summary = resolveConnectionSummary(settings);
        binding.textConnectionSummary.setText(summary);
    }

    private String resolveConnectionSummary(ProxySettings settings) {
        if (settings == null || settings.backendType == null) {
            return getString(R.string.backend_vk_turn_wireguard_title);
        }
        if (settings.backendType == BackendType.XRAY) {
            XrayProfile activeProfile = settings.activeXrayProfile;
            if (activeProfile != null) {
                if (!TextUtils.isEmpty(activeProfile.title)) {
                    return activeProfile.title;
                }
                if (!TextUtils.isEmpty(activeProfile.address) && activeProfile.port > 0) {
                    return activeProfile.address + ":" + activeProfile.port;
                }
            }
            return getString(R.string.backend_xray_title);
        }
        if (settings.backendType == BackendType.AMNEZIAWG) {
            if (!TextUtils.isEmpty(settings.endpoint)) {
                return settings.endpoint;
            }
            String endpoint = AmneziaStore.getConfiguredEndpoint(requireContext());
            if (!TextUtils.isEmpty(endpoint)) {
                return endpoint;
            }
            return getString(R.string.backend_amneziawg_title);
        }
        if (!TextUtils.isEmpty(settings.endpoint)) {
            return settings.endpoint;
        }
        return getString(R.string.backend_vk_turn_wireguard_title);
    }

    private void requestPublicIpIfNeeded() {
        if (ProxyTunnelService.isRunning()
                && !TextUtils.isEmpty(ProxyTunnelService.getPublicIp())
                && !TextUtils.isEmpty(ProxyTunnelService.getPublicCountry())
                && !TextUtils.isEmpty(ProxyTunnelService.getPublicIsp())) {
            return;
        }
        if (ProxyTunnelService.isActive()) {
            requestServicePublicIpRefresh(false);
            return;
        }
        refreshPublicIp(false);
    }

    private void refreshPublicIp() {
        refreshPublicIp(true);
    }

    private void refreshPublicIp(boolean forceRestart) {
        if (binding == null) {
            return;
        }
        if (ProxyTunnelService.isActive()) {
            requestServicePublicIpRefresh(forceRestart);
            return;
        }
        if (ipRequest != null) {
            if (!forceRestart) {
                return;
            }
            cancelIpRefreshRequest();
        }
        startIpRefreshAnimation();
        final int requestGeneration = ++ipRequestGeneration;
        ipRequest = PublicIpFetcher.fetchAsyncCancelable(
                requireContext(),
                false,
                result -> {
            if (!isAdded()) {
                return;
            }
            if (requestGeneration != ipRequestGeneration) {
                return;
            }
            ipRequest = null;
            stopIpRefreshAnimation();
            fallbackIpInfo = result;
            ProxyTunnelService.applyPublicIpInfo(result);
            refreshUi();
        });
    }

    private void cancelIpRefreshRequest() {
        if (ipRequest == null) {
            return;
        }
        ipRequest.cancel();
        ipRequest = null;
    }

    private void requestServicePublicIpRefresh(boolean forceRestart) {
        if (!ProxyTunnelService.isActive()) {
            return;
        }
        if (ProxyTunnelService.isPublicIpRefreshInProgress() && !forceRestart) {
            return;
        }
        startIpRefreshAnimation();
        try {
            requireContext().startService(ProxyTunnelService.createRefreshIpIntent(requireContext()));
        } catch (Exception ignored) {
            stopIpRefreshAnimation();
        }
    }

    private void syncIpRefreshAnimation() {
        boolean refreshing = ipRequest != null || ProxyTunnelService.isPublicIpRefreshInProgress();
        if (refreshing) {
            startIpRefreshAnimation();
        } else {
            stopIpRefreshAnimation();
        }
    }

    private void startIpRefreshAnimation() {
        if (binding == null) {
            return;
        }
        if (ipRefreshAnimator == null) {
            ipRefreshAnimator = ObjectAnimator.ofFloat(binding.buttonRefreshIp, View.ROTATION, 0f, 360f);
            ipRefreshAnimator.setDuration(800L);
            ipRefreshAnimator.setInterpolator(new LinearInterpolator());
            ipRefreshAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        }
        if (!ipRefreshAnimator.isStarted()) {
            binding.buttonRefreshIp.setRotation(0f);
            ipRefreshAnimator.start();
        }
    }

    private void stopIpRefreshAnimation() {
        if (binding == null || ipRefreshAnimator == null) {
            return;
        }
        if (ipRefreshAnimator.isRunning() || ipRefreshAnimator.isStarted()) {
            ipRefreshAnimator.cancel();
        }
        binding.buttonRefreshIp.setRotation(0f);
    }

    private void importFromClipboard() {
        Context context = requireContext();
        ClipboardManager clipboardManager =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        ClipData clipData = clipboardManager.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence text = clipData.getItemAt(0).coerceToText(context);
        try {
            WingsImportParser.ImportedConfig importedConfig =
                    WingsImportParser.parseFromText(text != null ? text.toString() : null);
            AppPrefs.applyImportedConfig(context, importedConfig);
            Haptics.softConfirm(binding.buttonImportClipboard);
            Toast.makeText(context, R.string.clipboard_import_success, Toast.LENGTH_SHORT).show();
            refreshUi();
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.clipboard_import_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyCurrentConfiguration() {
        Context context = requireContext();
        ClipboardManager clipboardManager =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            Toast.makeText(context, R.string.clipboard_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ProxySettings settings = AppPrefs.getSettings(context);
            String link;
            if (settings.backendType == BackendType.XRAY) {
                XrayProfile activeProfile = settings.activeXrayProfile;
                if (activeProfile == null || TextUtils.isEmpty(activeProfile.rawLink)) {
                    throw new IllegalArgumentException("No active Xray profile");
                }
                link = WingsImportParser.buildSingleXrayProfileLink(activeProfile);
            } else {
                link = WingsImportParser.buildLink(context, settings);
            }
            clipboardManager.setPrimaryClip(ClipData.newPlainText("WINGSV", link));
            Haptics.softConfirm(binding.buttonCopyConfig);
            Toast.makeText(context, R.string.clipboard_copy_success, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.clipboard_copy_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private int resolveThemeColor(int attr, int fallback) {
        TypedValue typedValue = new TypedValue();
        if (!requireContext().getTheme().resolveAttribute(attr, typedValue, true)) {
            return fallback;
        }
        if (typedValue.resourceId != 0) {
            return ContextCompat.getColor(requireContext(), typedValue.resourceId);
        }
        return typedValue.data;
    }

    private String firstNonEmpty(String primary, String fallback) {
        return !TextUtils.isEmpty(primary) ? primary : fallback;
    }
}
