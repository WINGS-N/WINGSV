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
import android.view.animation.LinearInterpolator;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.MainActivity;
import wings.v.R;
import wings.v.core.AmneziaStore;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.ProxySettings;
import wings.v.core.PublicIpFetcher;
import wings.v.core.UiFormatter;
import wings.v.core.WingsImportParser;
import wings.v.core.XrayProfile;
import wings.v.core.XraySubscriptionImportHelper;
import wings.v.core.XrayTransportMode;
import wings.v.databinding.FragmentHomeBinding;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.NullAssignment",
        "PMD.ExceptionAsFlowControl",
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.ExcessiveImports",
        "PMD.GodClass",
        "PMD.CyclomaticComplexity",
        "PMD.TooManyMethods",
        "PMD.NcssCount",
        "PMD.CognitiveComplexity",
        "PMD.NPathComplexity",
        "PMD.AvoidDeeplyNestedIfStmts",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.ShortVariable",
        "PMD.OnlyOneReturn",
        "PMD.ConfusingTernary",
    }
)
public class HomeFragment extends Fragment {

    private static final long NO_DURATION_MS = 0L;
    private static final int COUNTRY_CODE_LENGTH = 2;
    private static final long UI_REFRESH_INTERVAL_MS = 250L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (binding == null) {
                return;
            }
            refreshUi();
            handler.postDelayed(this, UI_REFRESH_INTERVAL_MS);
        }
    };

    private FragmentHomeBinding binding;
    private PublicIpFetcher.IpInfo fallbackIpInfo;
    private PublicIpFetcher.Request ipRequest;
    private ObjectAnimator ipRefreshAnimator;
    private int ipRequestGeneration;
    private int lastServiceStateBackgroundRes;
    private int lastServiceStateTextColor = Integer.MIN_VALUE;
    private int lastToggleBackgroundRes;
    private int lastToggleTintColor = Integer.MIN_VALUE;
    private boolean lastRuntimeNoticeVisible;

    @Nullable
    private String lastRuntimeNoticeError;

    private boolean lastPowerGlowConnected;
    private long lastPowerGlowBytesPerSecond = Long.MIN_VALUE;

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
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
        ProxyTunnelService.setFastTrafficStatsRequested(true);
        ProxyTunnelService.requestRuntimeSyncIfNeeded(requireContext());
        syncIpRefreshAnimation();
        requestPublicIpIfNeeded();
        handler.post(refreshRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        ProxyTunnelService.setFastTrafficStatsRequested(false);
        handler.removeCallbacks(refreshRunnable);
        stopIpRefreshAnimation();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelIpRefreshRequest();
        stopIpRefreshAnimation();
        resetUiDiffState();
        binding = null;
    }

    private void refreshUi() {
        if (binding == null || getContext() == null) {
            return;
        }

        Context context = requireContext();
        boolean running = ProxyTunnelService.isRunning();
        boolean connecting = ProxyTunnelService.isConnecting();
        boolean stopping = ProxyTunnelService.isStopping();
        boolean active = running || connecting || stopping;
        ProxySettings settings = AppPrefs.getSettings(context);
        BackendType visibleBackendType = ProxyTunnelService.getVisibleBackendType(context);
        if (settings != null) {
            settings.backendType = visibleBackendType;
        }

        if (connecting) {
            setTextIfChanged(binding.textServiceState, getString(R.string.service_connecting));
            long captchaLockoutRemainingMs = ProxyTunnelService.getProxyCaptchaLockoutRemainingMs();
            if (captchaLockoutRemainingMs > NO_DURATION_MS) {
                setTextIfChanged(
                    binding.textServiceHint,
                    getString(
                        R.string.service_connecting_lockout_hint,
                        wings.v.core.UiFormatter.formatDurationShort(captchaLockoutRemainingMs)
                    )
                );
            } else {
                setTextIfChanged(binding.textServiceHint, getString(R.string.service_connecting_hint));
            }
        } else if (stopping) {
            setTextIfChanged(
                binding.textServiceState,
                getString(
                    visibleBackendType != null && visibleBackendType.usesXrayCore()
                        ? R.string.service_stopping_xray
                        : R.string.service_stopping_vk_turn
                )
            );
            setTextIfChanged(binding.textServiceHint, getString(R.string.service_stopping_hint));
        } else {
            setTextIfChanged(binding.textServiceState, getString(running ? R.string.service_on : R.string.service_off));
            setTextIfChanged(
                binding.textServiceHint,
                getString(running ? R.string.tap_to_disconnect : R.string.tap_to_connect)
            );
        }
        if (running) {
            applyServiceStateStyle(
                R.drawable.bg_service_state_on,
                ContextCompat.getColor(context, android.R.color.white)
            );
        } else if (connecting || stopping) {
            applyServiceStateStyle(
                R.drawable.bg_service_state_warning,
                ContextCompat.getColor(context, R.color.wingsv_text_primary)
            );
        } else {
            applyServiceStateStyle(
                R.drawable.bg_surface_card,
                resolveThemeColor(
                    android.R.attr.textColorPrimary,
                    ContextCompat.getColor(context, R.color.wingsv_text_primary)
                )
            );
        }

        int tintColor = ContextCompat.getColor(context, android.R.color.white);
        if (!active) {
            tintColor = resolveThemeColor(android.R.attr.textColorPrimary, tintColor);
        }
        applyToggleStyle(active ? R.drawable.bg_power_button_on : R.drawable.bg_power_button_off, tintColor);

        long rxBytesPerSecond = ProxyTunnelService.getRxBytesPerSecond();
        long txBytesPerSecond = ProxyTunnelService.getTxBytesPerSecond();
        setTextIfChanged(binding.textDownlink, UiFormatter.formatBytesPerSecond(context, rxBytesPerSecond));
        setTextIfChanged(binding.textUplink, UiFormatter.formatBytesPerSecond(context, txBytesPerSecond));
        setTextIfChanged(binding.textRx, UiFormatter.formatBytes(context, ProxyTunnelService.getRxBytes()));
        setTextIfChanged(binding.textTx, UiFormatter.formatBytes(context, ProxyTunnelService.getTxBytes()));
        updatePowerGlow(running, rxBytesPerSecond + txBytesPerSecond);

        String ip = firstNonEmpty(ProxyTunnelService.getPublicIp(), fallbackIpInfo != null ? fallbackIpInfo.ip : null);
        setTextIfChanged(binding.textIp, TextUtils.isEmpty(ip) ? getString(R.string.ip_loading) : ip);

        String country = firstNonEmpty(
            ProxyTunnelService.getPublicCountry(),
            fallbackIpInfo != null ? fallbackIpInfo.country : null
        );
        setTextIfChanged(
            binding.textCountry,
            TextUtils.isEmpty(country) ? getString(R.string.ip_unknown) : formatCountryWithFlag(country)
        );

        String isp = firstNonEmpty(
            ProxyTunnelService.getPublicIsp(),
            fallbackIpInfo != null ? fallbackIpInfo.isp : null
        );
        setTextIfChanged(binding.textIsp, TextUtils.isEmpty(isp) ? getString(R.string.ip_unknown) : isp);

        String error = ProxyTunnelService.getVisibleErrorNotice();
        updateRuntimeNotice(error);

        syncIpRefreshAnimation();

        String summary = resolveConnectionSummary(settings);
        setTextIfChanged(binding.textConnectionSummary, summary);
    }

    private void applyServiceStateStyle(int backgroundResId, int textColor) {
        if (binding == null) {
            return;
        }
        if (lastServiceStateBackgroundRes != backgroundResId) {
            binding.textServiceState.setBackgroundResource(backgroundResId);
            lastServiceStateBackgroundRes = backgroundResId;
        }
        if (lastServiceStateTextColor != textColor) {
            binding.textServiceState.setTextColor(textColor);
            lastServiceStateTextColor = textColor;
        }
    }

    private void applyToggleStyle(int backgroundResId, int tintColor) {
        if (binding == null) {
            return;
        }
        if (lastToggleBackgroundRes != backgroundResId) {
            binding.buttonToggle.setBackgroundResource(backgroundResId);
            lastToggleBackgroundRes = backgroundResId;
        }
        if (lastToggleTintColor != tintColor) {
            binding.buttonToggle.setImageTintList(ColorStateList.valueOf(tintColor));
            lastToggleTintColor = tintColor;
        }
    }

    private void updateRuntimeNotice(@Nullable String error) {
        if (binding == null) {
            return;
        }
        boolean visible = !TextUtils.isEmpty(error);
        if (lastRuntimeNoticeVisible != visible) {
            binding.cardRuntimeNotice.setVisibility(visible ? View.VISIBLE : View.GONE);
            lastRuntimeNoticeVisible = visible;
        }
        if (!visible || TextUtils.equals(lastRuntimeNoticeError, error)) {
            lastRuntimeNoticeError = error;
            return;
        }
        binding.cardRuntimeNotice.setTitle(getString(R.string.runtime_notice_title));
        binding.cardRuntimeNotice.setSummary(getString(R.string.runtime_notice_message, error));
        lastRuntimeNoticeError = error;
    }

    private void updatePowerGlow(boolean connected, long bytesPerSecond) {
        if (binding == null) {
            return;
        }
        if (lastPowerGlowConnected == connected && lastPowerGlowBytesPerSecond == bytesPerSecond) {
            return;
        }
        binding.viewPowerGlow.setTrafficState(connected, bytesPerSecond);
        lastPowerGlowConnected = connected;
        lastPowerGlowBytesPerSecond = bytesPerSecond;
    }

    private void setTextIfChanged(@Nullable TextView view, CharSequence text) {
        if (view == null || TextUtils.equals(view.getText(), text)) {
            return;
        }
        view.setText(text);
    }

    private void resetUiDiffState() {
        lastServiceStateBackgroundRes = 0;
        lastServiceStateTextColor = Integer.MIN_VALUE;
        lastToggleBackgroundRes = 0;
        lastToggleTintColor = Integer.MIN_VALUE;
        lastRuntimeNoticeVisible = false;
        lastRuntimeNoticeError = null;
        lastPowerGlowConnected = false;
        lastPowerGlowBytesPerSecond = Long.MIN_VALUE;
    }

    private String resolveConnectionSummary(ProxySettings settings) {
        if (settings == null || settings.backendType == null) {
            return getString(R.string.backend_vk_turn_wireguard_title);
        }
        if (settings.backendType != null && settings.backendType.usesXrayCore()) {
            XrayProfile activeProfile = settings.activeXrayProfile;
            XrayTransportMode transportMode =
                settings.xraySettings != null && settings.xraySettings.transportMode != null
                    ? settings.xraySettings.transportMode
                    : XrayTransportMode.DIRECT;
            String relaySuffix = "";
            if (transportMode.usesTurnProxy()) {
                relaySuffix = " · VK TURN TCP";
            }
            if (activeProfile != null) {
                if (!TextUtils.isEmpty(activeProfile.title)) {
                    return activeProfile.title + relaySuffix;
                }
                if (!TextUtils.isEmpty(activeProfile.address) && activeProfile.port > 0) {
                    String endpoint = activeProfile.address + ":" + activeProfile.port;
                    return endpoint + relaySuffix;
                }
            }
            if (transportMode.usesTurnProxy()) {
                return getString(R.string.xray_transport_mode_vk_turn_tcp);
            }
            return getString(R.string.backend_xray_title);
        }
        if (settings.backendType != null && settings.backendType.usesAmneziaSettings()) {
            if (!TextUtils.isEmpty(settings.endpoint)) {
                return settings.endpoint;
            }
            String endpoint = AmneziaStore.getConfiguredEndpoint(requireContext());
            if (!TextUtils.isEmpty(endpoint)) {
                return endpoint;
            }
            return getString(
                settings.backendType == BackendType.AMNEZIAWG
                    ? R.string.backend_amneziawg_title
                    : R.string.backend_amneziawg_plain_title
            );
        }
        if (settings.backendType == BackendType.WIREGUARD) {
            if (!TextUtils.isEmpty(settings.endpoint)) {
                return settings.endpoint;
            }
            return getString(R.string.backend_wireguard_title);
        }
        if (!TextUtils.isEmpty(settings.endpoint)) {
            return settings.endpoint;
        }
        return getString(R.string.backend_vk_turn_wireguard_title);
    }

    private void requestPublicIpIfNeeded() {
        if (
            ProxyTunnelService.isRunning() &&
            !TextUtils.isEmpty(ProxyTunnelService.getPublicIp()) &&
            !TextUtils.isEmpty(ProxyTunnelService.getPublicCountry()) &&
            !TextUtils.isEmpty(ProxyTunnelService.getPublicIsp())
        ) {
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
        ipRequestGeneration++;
        final int requestGeneration = ipRequestGeneration;
        ipRequest = PublicIpFetcher.fetchAsyncCancelable(requireContext(), false, result -> {
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
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
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
            String rawText = text != null ? text.toString() : null;
            WingsImportParser.ImportedConfig importedConfig = WingsImportParser.parseFromText(rawText);
            if (WingsImportParser.isSubscriptionOnlyXrayImport(importedConfig)) {
                importSubscriptionUrls(context, rawText, importedConfig);
                return;
            }
            AppPrefs.applyImportedConfig(context, importedConfig);
            requestReconnectAfterImport(context, rawText);
            Haptics.softConfirm(binding.buttonImportClipboard);
            Toast.makeText(context, R.string.clipboard_import_success, Toast.LENGTH_SHORT).show();
            refreshUi();
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.clipboard_import_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void importSubscriptionUrls(
        Context context,
        @Nullable String importedText,
        WingsImportParser.ImportedConfig importedConfig
    ) {
        Context appContext = context.getApplicationContext();
        if (binding != null) {
            binding.buttonImportClipboard.setEnabled(false);
        }
        Toast.makeText(appContext, R.string.xray_subscriptions_import_started, Toast.LENGTH_SHORT).show();
        importExecutor.execute(() -> {
            String toastMessage;
            boolean importedSuccessfully = false;
            try {
                XraySubscriptionImportHelper.ImportResult result = XraySubscriptionImportHelper.importAndRefresh(
                    appContext,
                    importedConfig
                );
                if (!result.hasProfiles) {
                    toastMessage = appContext.getString(R.string.xray_subscriptions_import_no_profiles);
                } else if (TextUtils.isEmpty(result.refreshResult.error)) {
                    toastMessage = appContext.getString(R.string.clipboard_import_success);
                    importedSuccessfully = true;
                } else {
                    toastMessage = appContext.getString(
                        R.string.xray_subscriptions_refresh_partial,
                        result.refreshResult.error
                    );
                    importedSuccessfully = true;
                }
            } catch (Exception error) {
                toastMessage = appContext.getString(R.string.xray_subscriptions_refresh_failed, error.getMessage());
            }
            final boolean shouldReconnect = importedSuccessfully;
            final String resolvedToastMessage = toastMessage;
            handler.post(() -> {
                if (binding != null) {
                    binding.buttonImportClipboard.setEnabled(true);
                }
                if (shouldReconnect) {
                    requestReconnectAfterImport(appContext, importedText);
                    if (binding != null) {
                        Haptics.softConfirm(binding.buttonImportClipboard);
                    }
                    refreshUi();
                }
                Toast.makeText(appContext, resolvedToastMessage, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void requestReconnectAfterImport(Context context, @Nullable String importedText) {
        if (!ProxyTunnelService.isActive()) {
            return;
        }
        String normalized = importedText == null ? "" : importedText.trim().toLowerCase(Locale.ROOT);
        String reason = normalized.startsWith("vless://")
            ? "Imported vless configuration applied"
            : normalized.startsWith("http://") || normalized.startsWith("https://")
                ? "Imported xray subscription applied"
                : "Imported wingsv configuration applied";
        ProxyTunnelService.requestReconnect(context, reason);
    }

    private void copyCurrentConfiguration() {
        Context context = requireContext();
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            Toast.makeText(context, R.string.clipboard_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ProxySettings settings = AppPrefs.getSettings(context);
            String link;
            if (settings.backendType != null && settings.backendType.usesXrayCore()) {
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

    private static String formatCountryWithFlag(String country) {
        String value = trim(country);
        if (TextUtils.isEmpty(value) || startsWithFlagEmoji(value)) {
            return value;
        }
        String countryCode = resolveCountryCode(value);
        if (TextUtils.isEmpty(countryCode)) {
            return value;
        }
        return countryCodeToFlag(countryCode) + " " + value;
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static String resolveCountryCode(String country) {
        String value = trim(country);
        if (value.length() == 2 && Character.isLetter(value.charAt(0)) && Character.isLetter(value.charAt(1))) {
            return value.toUpperCase(Locale.US);
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "россия":
            case "russia":
            case "russian federation":
                return "RU";
            case "сша":
            case "соединенные штаты":
            case "соединённые штаты":
            case "united states":
            case "united states of america":
            case "usa":
                return "US";
            case "великобритания":
            case "united kingdom":
            case "great britain":
                return "GB";
            default:
                break;
        }
        Locale russianLocale = Locale.forLanguageTag("ru");
        for (String isoCode : Locale.getISOCountries()) {
            Locale locale = new Locale.Builder().setRegion(isoCode).build();
            if (
                normalized.equals(locale.getDisplayCountry(Locale.ENGLISH).toLowerCase(Locale.ROOT)) ||
                normalized.equals(locale.getDisplayCountry(russianLocale).toLowerCase(Locale.ROOT)) ||
                normalized.equals(locale.getDisplayCountry(Locale.getDefault()).toLowerCase(Locale.ROOT))
            ) {
                return isoCode;
            }
        }
        return "";
    }

    private static String countryCodeToFlag(String countryCode) {
        String code = trim(countryCode).toUpperCase(Locale.US);
        if (code.length() != COUNTRY_CODE_LENGTH) {
            return "";
        }
        int first = code.codePointAt(0) - 'A' + 0x1F1E6;
        int second = code.codePointAt(1) - 'A' + 0x1F1E6;
        if (first < 0x1F1E6 || first > 0x1F1FF || second < 0x1F1E6 || second > 0x1F1FF) {
            return "";
        }
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }

    private static boolean startsWithFlagEmoji(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        int first = value.codePointAt(0);
        if (first < 0x1F1E6 || first > 0x1F1FF) {
            return false;
        }
        int offset = Character.charCount(first);
        if (value.length() <= offset) {
            return false;
        }
        int second = value.codePointAt(offset);
        return second >= 0x1F1E6 && second <= 0x1F1FF;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
