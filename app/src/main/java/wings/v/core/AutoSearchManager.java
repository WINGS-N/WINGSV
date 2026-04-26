package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import wings.v.R;
import wings.v.byedpi.ByeDpiLocalRunner;
import wings.v.service.EmergencyVpnResetService;
import wings.v.service.ProxyTunnelService;
import wings.v.service.XrayVpnService;
import wings.v.xray.XrayAutoSearchConfigFactory;
import wings.v.xray.XrayBridge;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.AvoidUsingVolatile",
        "PMD.NullAssignment",
        "PMD.SignatureDeclareThrowsException",
        "PMD.AvoidUsingHardCodedIP",
        "PMD.ExceptionAsFlowControl",
        "PMD.AvoidSynchronizedStatement",
        "PMD.CommentRequired",
        "PMD.CommentDefaultAccessModifier",
        "PMD.ExcessiveImports",
        "PMD.CouplingBetweenObjects",
        "PMD.GodClass",
        "PMD.CyclomaticComplexity",
        "PMD.TooManyMethods",
        "PMD.CognitiveComplexity",
        "PMD.NPathComplexity",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.UseConcurrentHashMap",
        "PMD.ImplicitFunctionalInterface",
        "PMD.AvoidDuplicateLiterals",
        "PMD.SingularField",
        "PMD.SimplifyBooleanReturns",
    }
)
public final class AutoSearchManager {

    private static final int SOCKS5_VERSION = 0x05;
    private static final int SOCKS5_METHOD_NO_AUTH = 0x00;
    private static final int SOCKS5_METHOD_USERNAME_PASSWORD = 0x02;
    private static final int SOCKS5_METHOD_NOT_ACCEPTABLE = 0xff;
    private static final int SOCKS5_AUTH_VERSION = 0x01;
    private static final int SOCKS5_COMMAND_CONNECT = 0x01;
    private static final int SOCKS5_ADDRESS_TYPE_IPV4 = 0x01;
    private static final int SOCKS5_ADDRESS_TYPE_DOMAIN = 0x03;
    private static final int SOCKS5_ADDRESS_TYPE_IPV6 = 0x04;
    private static final int SOCKS5_HOST_MAX_LENGTH = 255;
    private static final int IPV4_ADDRESS_LENGTH = 4;
    private static final int IPV6_ADDRESS_LENGTH = 16;
    private static final int HTTP_STATUS_PARTS_MIN = 2;
    private static final int HTTP_STATUS_PARTS_LIMIT = 3;
    private static final String TAG = "WINGSV/AutoSearch";

    public static final String KEY_OPEN_SETTINGS = "pref_open_auto_search_settings";
    public static final String AUTOSEARCH_SUBSCRIPTION_ID = "__autosearch__";
    public static final String AUTOSEARCH_FILTER_ID = "sub:" + AUTOSEARCH_SUBSCRIPTION_ID;
    public static final String KEY_TARGET_COUNT = "pref_auto_search_target_count";
    public static final String KEY_TCPING_TIMEOUT_MS = "pref_auto_search_tcping_timeout_ms";
    public static final String KEY_DOWNLOAD_SIZE_MB = "pref_auto_search_download_size_mb";
    public static final String KEY_DOWNLOAD_TIMEOUT_SECONDS = "pref_auto_search_download_timeout_seconds";
    public static final String KEY_DOWNLOAD_ATTEMPTS = "pref_auto_search_download_attempts";

    private static final String AUTOSEARCH_SUBSCRIPTION_TITLE = "Автопоиск";
    private static final String DOWNLOAD_TEST_URL_PREFIX = "https://speed.cloudflare.com/__down?bytes=";
    private static final String[] TRAFFIC_PROBE_URLS = {
        "https://cp.cloudflare.com/generate_204",
        "https://cloudflare.com/cdn-cgi/trace",
        "https://1.1.1.1/cdn-cgi/trace",
    };
    private static final int DEFAULT_TCPING_TIMEOUT_MS = 1_000;
    private static final int TCPING_PARALLELISM = 5;
    private static final int TRAFFIC_PROBE_CONNECT_TIMEOUT_MS = 3_000;
    private static final int TRAFFIC_PROBE_READ_TIMEOUT_MS = 5_000;
    private static final int TRAFFIC_PROBE_RESPONSIVE_CONNECT_TIMEOUT_MS = 8_000;
    private static final int TRAFFIC_PROBE_RESPONSIVE_READ_TIMEOUT_MS = 12_000;
    private static final int TRAFFIC_PROBE_MAX_BYTES = 4 * 1024;
    private static final int DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_DOWNLOAD_ATTEMPTS = 2;
    private static final int DEFAULT_TARGET_COUNT = 5;
    private static final int DEFAULT_DOWNLOAD_SIZE_MB = 5;
    private static final long INTER_ATTEMPT_DELAY_MS = 3_000L;
    private static final long DOWNLOAD_SUCCESS_TOLERANCE_MIN_BYTES = 64L * 1024L;
    private static final long DOWNLOAD_SUCCESS_TOLERANCE_MAX_BYTES = 256L * 1024L;
    private static final long DOWNLOAD_SUCCESS_TOLERANCE_DIVISOR = 50L;
    private static final long SERVICE_STOP_TIMEOUT_MS = 8_000L;
    private static final long SERVICE_STOP_POLL_MS = 200L;
    private static final long VPN_RELEASE_GRACE_MS = 1_000L;
    private static final long EMERGENCY_VPN_RESET_HOLD_MS = 1_200L;
    private static final long EMERGENCY_VPN_RESET_TIMEOUT_MS = 4_000L;
    private static final long XRAY_PROXY_START_TIMEOUT_MS = 4_000L;
    private static final long XRAY_PROXY_START_POLL_MS = 100L;
    private static final long XRAY_PROXY_WARMUP_MS = 700L;
    private static final long BYEDPI_START_TIMEOUT_MS = 4_000L;
    private static final int BYEDPI_WARMUP_ATTEMPTS = 3;
    private static final long BYEDPI_WARMUP_DELAY_MS = 500L;
    private static final long MIN_SKIPPED_BYTES = 1L;
    private static final int LINE_FEED = '\n';
    private static final int CARRIAGE_RETURN = '\r';
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile AutoSearchManager instance;

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private volatile State state = State.idle();
    private volatile boolean running;
    private volatile long pendingActionToken;
    private PendingResult pendingResult;
    private PreparedSearch preparedSearch;

    private AutoSearchManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static AutoSearchManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AutoSearchManager.class) {
                if (instance == null) {
                    instance = new AutoSearchManager(context);
                }
            }
        }
        return instance;
    }

    public void registerListener(@NonNull Listener listener) {
        listeners.add(listener);
        notifyListener(listener, state);
    }

    public void unregisterListener(@Nullable Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @NonNull
    public State getState() {
        return state;
    }

    public boolean isRunning() {
        return running;
    }

    public void resetFinishedState() {
        if (running) {
            return;
        }
        Status status = state.status;
        if (status != Status.COMPLETED && status != Status.FAILED) {
            return;
        }
        preparedSearch = null;
        pendingResult = null;
        pendingActionToken = 0L;
        updateState(State.idle());
    }

    public static int getTargetProfileCount(@NonNull Context context) {
        return clamp(settings(context).getInt(KEY_TARGET_COUNT, DEFAULT_TARGET_COUNT), 1, 20);
    }

    public static void setTargetProfileCount(@NonNull Context context, int value) {
        settings(context).edit().putInt(KEY_TARGET_COUNT, clamp(value, 1, 20)).apply();
    }

    public static int getTcpingTimeoutMs(@NonNull Context context) {
        return clamp(settings(context).getInt(KEY_TCPING_TIMEOUT_MS, DEFAULT_TCPING_TIMEOUT_MS), 300, 10_000);
    }

    public static void setTcpingTimeoutMs(@NonNull Context context, int value) {
        settings(context).edit().putInt(KEY_TCPING_TIMEOUT_MS, clamp(value, 300, 10_000)).apply();
    }

    public static int getDownloadSizeMb(@NonNull Context context) {
        return clamp(settings(context).getInt(KEY_DOWNLOAD_SIZE_MB, DEFAULT_DOWNLOAD_SIZE_MB), 1, 100);
    }

    public static void setDownloadSizeMb(@NonNull Context context, int value) {
        settings(context).edit().putInt(KEY_DOWNLOAD_SIZE_MB, clamp(value, 1, 100)).apply();
    }

    public static int getDownloadTimeoutSeconds(@NonNull Context context) {
        return clamp(settings(context).getInt(KEY_DOWNLOAD_TIMEOUT_SECONDS, DEFAULT_DOWNLOAD_TIMEOUT_SECONDS), 3, 120);
    }

    public static void setDownloadTimeoutSeconds(@NonNull Context context, int value) {
        settings(context).edit().putInt(KEY_DOWNLOAD_TIMEOUT_SECONDS, clamp(value, 3, 120)).apply();
    }

    public static int getDownloadAttempts(@NonNull Context context) {
        return clamp(settings(context).getInt(KEY_DOWNLOAD_ATTEMPTS, DEFAULT_DOWNLOAD_ATTEMPTS), 1, 10);
    }

    public static void setDownloadAttempts(@NonNull Context context, int value) {
        settings(context).edit().putInt(KEY_DOWNLOAD_ATTEMPTS, clamp(value, 1, 10)).apply();
    }

    private static SharedPreferences settings(@NonNull Context context) {
        return AppPrefs.defaultSharedPreferences(context);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long getDownloadSizeBytes(@NonNull Context context) {
        return getDownloadSizeMb(context) * 1024L * 1024L;
    }

    public void startSearch() {
        startSearch(true);
    }

    public void startSearch(boolean useBuiltInSubscription) {
        if (running) {
            return;
        }
        preparedSearch = null;
        pendingResult = null;
        pendingActionToken = 0L;
        running = true;
        updateState(
            State.running(
                null,
                appContext.getString(R.string.auto_search_step_prepare),
                appContext.getString(R.string.auto_search_prepare_summary),
                true,
                0,
                0,
                "",
                "",
                0L,
                0,
                0
            )
        );
        executor.execute(() -> prepareSearch(useBuiltInSubscription));
    }

    public void continueSearch(@NonNull Mode mode) {
        PreparedSearch pending = preparedSearch;
        if (!running || pending == null) {
            return;
        }
        preparedSearch = null;
        updateState(
            State.running(
                mode,
                appContext.getString(R.string.auto_search_step_prepare_mode),
                appContext.getString(
                    mode == Mode.WHITELIST
                        ? R.string.auto_search_mode_whitelist_summary
                        : R.string.auto_search_mode_standard_summary
                ),
                true,
                0,
                0,
                "",
                "",
                0L,
                0,
                0
            )
        );
        executor.execute(() -> continuePreparedSearch(pending, mode));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void cancelPendingModeSelection() {
        PreparedSearch pending = preparedSearch;
        preparedSearch = null;
        pendingActionToken = 0L;
        if (!running || pending == null) {
            return;
        }
        executor.execute(() -> {
            try {
                restoreOriginalConfiguration(pending.session);
                updateState(State.completed(null, appContext.getString(R.string.auto_search_cancelled), 0, ""));
            } catch (RuntimeException error) {
                updateState(
                    State.failed(
                        null,
                        appContext.getString(
                            R.string.auto_search_failed_detail,
                            firstNonEmpty(error.getMessage(), "unknown error")
                        )
                    )
                );
            } finally {
                running = false;
            }
        });
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void applyPendingConfiguration(boolean apply) {
        PendingResult result = pendingResult;
        pendingResult = null;
        preparedSearch = null;
        pendingActionToken = 0L;
        if (result == null) {
            return;
        }
        try {
            updateState(
                State.running(
                    result.mode,
                    appContext.getString(R.string.auto_search_step_apply),
                    apply
                        ? appContext.getString(R.string.auto_search_apply_summary)
                        : appContext.getString(R.string.auto_search_restore_summary),
                    true,
                    0,
                    0,
                    "",
                    "",
                    0L,
                    result.foundProfiles.size(),
                    0
                )
            );
            applyOrRestoreConfiguration(result, apply);
            String message = apply
                ? appContext.getString(
                      R.string.auto_search_complete_applied,
                      result.bestProfile != null ? safeProfileTitle(result.bestProfile.profile) : ""
                  )
                : appContext.getString(R.string.auto_search_complete_not_applied);
            updateState(
                State.completed(
                    result.mode,
                    message,
                    result.foundProfiles.size(),
                    result.bestProfile != null ? safeProfileTitle(result.bestProfile.profile) : ""
                )
            );
        } catch (RuntimeException error) {
            updateState(
                State.failed(
                    result.mode,
                    appContext.getString(
                        R.string.auto_search_failed_detail,
                        firstNonEmpty(error.getMessage(), "unknown error")
                    )
                )
            );
        } finally {
            running = false;
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void prepareSearch(boolean useBuiltInSubscription) {
        SearchSession session = new SearchSession();
        session.originalBackend = XrayStore.getBackendType(appContext);
        XrayProfile originalActiveProfile = XrayStore.getActiveProfile(appContext);
        session.originalActiveProfileId = originalActiveProfile != null ? originalActiveProfile.id : "";
        session.originalByeDpiAutoStart = ByeDpiStore.getSettings(appContext).launchOnXrayStart;
        session.serviceWasActive = ProxyTunnelService.isActive();
        session.originalProfiles = new ArrayList<>(XrayStore.getProfiles(appContext));
        try {
            stopCurrentRuntime(session);
            XrayStore.setBackendType(appContext, BackendType.XRAY);
            if (useBuiltInSubscription) {
                XrayStore.ensureDefaultSubscriptionPresent(appContext);
            }

            updateState(
                State.running(
                    null,
                    appContext.getString(R.string.auto_search_step_refresh),
                    appContext.getString(R.string.auto_search_refresh_summary),
                    true,
                    0,
                    0,
                    "",
                    "",
                    0L,
                    0,
                    0
                )
            );
            XraySubscriptionUpdater.refreshAll(appContext, null, useBuiltInSubscription);
            List<XrayProfile> availableProfiles = new ArrayList<>(XrayStore.getProfiles(appContext));
            if (availableProfiles.isEmpty()) {
                availableProfiles = new ArrayList<>(session.originalProfiles);
            }
            if (availableProfiles.isEmpty()) {
                throw new IllegalStateException(appContext.getString(R.string.auto_search_failed_no_profiles));
            }

            preparedSearch = new PreparedSearch(
                session,
                availableProfiles,
                XrayStore.getXraySettings(appContext),
                ByeDpiStore.getSettings(appContext)
            );
            pendingActionToken = SystemClock.elapsedRealtime();
            preparedSearch.token = pendingActionToken;
            updateState(
                State.awaitingModeSelection(
                    appContext.getString(R.string.auto_search_mode_prompt_message),
                    availableProfiles.size(),
                    pendingActionToken
                )
            );
        } catch (Exception error) {
            preparedSearch = null;
            restoreOriginalConfigurationQuietly(session);
            updateState(
                State.failed(
                    null,
                    appContext.getString(
                        R.string.auto_search_failed_detail,
                        firstNonEmpty(error.getMessage(), "unknown error")
                    )
                )
            );
            running = false;
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void continuePreparedSearch(@NonNull PreparedSearch prepared, @NonNull Mode mode) {
        SearchSession session = prepared.session;
        session.mode = mode;
        ByeDpiLocalRunner byeDpiRunner = null;
        try {
            if (mode == Mode.WHITELIST) {
                updateState(
                    State.running(
                        mode,
                        appContext.getString(R.string.auto_search_step_bydpi),
                        appContext.getString(R.string.auto_search_bydpi_summary),
                        true,
                        0,
                        0,
                        "",
                        "",
                        0L,
                        0,
                        0
                    )
                );
                byeDpiRunner = startTemporaryByeDpi(prepared.byeDpiSettings);
                warmUpTemporaryByeDpi(mode, byeDpiRunner);
            }

            List<CandidateResult> pingCandidates = runPingPhase(mode, prepared.availableProfiles);

            List<CandidateResult> rankedCandidates = runDownloadPhase(
                mode,
                pingCandidates,
                prepared.xraySettings,
                prepared.byeDpiSettings
            );
            if (rankedCandidates.isEmpty()) {
                throw new IllegalStateException(appContext.getString(R.string.auto_search_failed_no_stable));
            }

            int targetCount = getTargetProfileCount(appContext);
            List<CandidateResult> selectedCandidates =
                rankedCandidates.size() > targetCount
                    ? new ArrayList<>(rankedCandidates.subList(0, targetCount))
                    : rankedCandidates;
            CandidateResult bestCandidate = chooseBestCandidate(selectedCandidates);
            if (bestCandidate == null) {
                throw new IllegalStateException(appContext.getString(R.string.auto_search_failed_no_stable));
            }

            persistAutoSearchProfiles(selectedCandidates, prepared.availableProfiles, session.originalProfiles);
            PendingResult pending = buildPendingResult(session, selectedCandidates, bestCandidate);
            if (!pending.configurationChanged) {
                pendingResult = pending;
                applyPendingConfiguration(true);
                return;
            }
            pendingActionToken = SystemClock.elapsedRealtime();
            pending.token = pendingActionToken;
            pendingResult = pending;
            updateState(
                State.awaitingApply(
                    mode,
                    appContext.getString(
                        R.string.auto_search_apply_prompt_message,
                        safeProfileTitle(bestCandidate.profile)
                    ),
                    selectedCandidates.size(),
                    safeProfileTitle(bestCandidate.profile),
                    pendingActionToken
                )
            );
        } catch (Exception error) {
            preparedSearch = null;
            restoreOriginalConfigurationQuietly(session);
            updateState(
                State.failed(
                    mode,
                    appContext.getString(
                        R.string.auto_search_failed_detail,
                        firstNonEmpty(error.getMessage(), "unknown error")
                    )
                )
            );
            running = false;
        } finally {
            stopTemporaryByeDpi(byeDpiRunner);
            stopXrayQuietly();
        }
    }

    private void stopCurrentRuntime(SearchSession session) throws Exception {
        updateState(
            State.running(
                session.mode,
                appContext.getString(R.string.auto_search_step_stop),
                appContext.getString(R.string.auto_search_stop_summary),
                true,
                0,
                0,
                "",
                "",
                0L,
                0,
                0
            )
        );
        ProxyTunnelService.requestStop(appContext);
        XrayVpnService.stopService(appContext);
        stopXrayQuietly();
        long deadline = SystemClock.elapsedRealtime() + SERVICE_STOP_TIMEOUT_MS;
        while (isRuntimeStillStopping(true) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(SERVICE_STOP_POLL_MS);
        }
        if (isRuntimeStillStopping(true)) {
            forceStopRuntime();
            long forceDeadline = SystemClock.elapsedRealtime() + 2_500L;
            while (isRuntimeStillStopping(true) && SystemClock.elapsedRealtime() < forceDeadline) {
                SystemClock.sleep(SERVICE_STOP_POLL_MS);
            }
        }
        displaceAnyActiveVpnServiceIfNeeded();
        if (isRuntimeStillStopping(true)) {
            throw new IllegalStateException(appContext.getString(R.string.auto_search_failed_stop_runtime));
        }
        SystemClock.sleep(VPN_RELEASE_GRACE_MS);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private boolean isRuntimeStillStopping(boolean includeProxyServiceState) {
        boolean xrayRunning = false;
        try {
            xrayRunning = XrayBridge.isRunning();
        } catch (RuntimeException ignored) {}
        boolean realRuntimeActive =
            XrayVpnService.hasActiveTunnel() || XrayVpnService.getServiceNow() != null || xrayRunning;
        if (realRuntimeActive) {
            return true;
        }
        return includeProxyServiceState && ProxyTunnelService.isActive();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void forceStopRuntime() {
        stopXrayQuietly();
        try {
            XrayVpnService.forceStopService(appContext);
        } catch (RuntimeException ignored) {}
        try {
            ProxyTunnelService.forceAbortRuntime(appContext);
        } catch (RuntimeException ignored) {}
        if (ProxyTunnelService.hasOwnedVpnServiceRuntime()) {
            try {
                EmergencyVpnResetService.pulse(appContext, EMERGENCY_VPN_RESET_HOLD_MS);
            } catch (RuntimeException ignored) {}
        }
    }

    private void displaceAnyActiveVpnServiceIfNeeded() {
        if (!isVpnTransportActive()) {
            return;
        }
        try {
            EmergencyVpnResetService.pulse(appContext, EMERGENCY_VPN_RESET_HOLD_MS);
        } catch (RuntimeException ignored) {
            return;
        }
        long deadline = SystemClock.elapsedRealtime() + EMERGENCY_VPN_RESET_TIMEOUT_MS;
        while (isVpnTransportActive() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(SERVICE_STOP_POLL_MS);
        }
    }

    private boolean isVpnTransportActive() {
        ConnectivityManager connectivityManager = appContext.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return false;
        }
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @NonNull
    private List<CandidateResult> runPingPhase(Mode mode, List<XrayProfile> profiles) throws Exception {
        List<CandidateResult> candidates = new ArrayList<>();
        int total = profiles.size();
        int completed = 0;
        try (ExecutorScope executorScope = new ExecutorScope(TCPING_PARALLELISM)) {
            ExecutorCompletionService<CandidateResult> completionService = new ExecutorCompletionService<>(
                executorScope.executor
            );
            for (XrayProfile profile : profiles) {
                completionService.submit(() -> tcpingCandidate(profile));
            }
            while (completed < total) {
                Future<CandidateResult> future = completionService.take();
                CandidateResult candidate;
                try {
                    candidate = future.get();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    candidate = failedCandidate();
                } catch (ExecutionException ignored) {
                    candidate = failedCandidate();
                }
                completed++;
                if (candidate.profile != null) {
                    XrayStore.putProfilePingResult(
                        appContext,
                        XrayStore.getProfilePingKey(candidate.profile),
                        candidate.pingResponsive,
                        candidate.latencyMs
                    );
                    if (candidate.pingResponsive) {
                        candidates.add(candidate);
                    }
                }
                updateState(
                    State.running(
                        mode,
                        appContext.getString(R.string.auto_search_step_ping),
                        appContext.getString(R.string.auto_search_ping_summary),
                        false,
                        completed,
                        total,
                        safeProfileTitle(candidate.profile),
                        candidate.pingResponsive
                            ? appContext.getString(R.string.auto_search_ping_metric, candidate.latencyMs)
                            : appContext.getString(R.string.auto_search_ping_failed_metric),
                        0L,
                        0,
                        candidate.latencyMs
                    )
                );
            }
        }
        candidates.sort((left, right) -> {
            int compareLatency = Integer.compare(left.latencyMs, right.latencyMs);
            if (compareLatency != 0) {
                return compareLatency;
            }
            return safeProfileTitle(left.profile).compareToIgnoreCase(safeProfileTitle(right.profile));
        });
        if (candidates.isEmpty()) {
            throw new IllegalStateException(appContext.getString(R.string.auto_search_failed_no_ping));
        }
        return candidates;
    }

    @NonNull
    private CandidateResult tcpingCandidate(@Nullable XrayProfile profile) {
        CandidateResult candidate = new CandidateResult(profile);
        if (profile == null || TextUtils.isEmpty(profile.address) || profile.port <= 0) {
            return candidate;
        }
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(profile.address, profile.port), getTcpingTimeoutMs(appContext));
            int elapsedMs = (int) ((System.nanoTime() - start) / 1_000_000L);
            candidate.latencyMs = Math.max(elapsedMs, 1);
            candidate.pingResponsive = true;
            candidate.live = true;
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            candidate.latencyMs = 0;
            candidate.pingResponsive = false;
        }
        return candidate;
    }

    @NonNull
    private List<CandidateResult> runDownloadPhase(
        Mode mode,
        List<CandidateResult> pingSuccess,
        XraySettings xraySettings,
        ByeDpiSettings byeDpiSettings
    ) throws Exception {
        List<CandidateResult> stable = new ArrayList<>();
        List<CandidateResult> successful = new ArrayList<>();
        int total = pingSuccess.size();
        int index = 0;
        for (CandidateResult candidate : pingSuccess) {
            index++;
            updateState(
                State.running(
                    mode,
                    appContext.getString(R.string.auto_search_step_download),
                    appContext.getString(R.string.auto_search_download_summary),
                    false,
                    index - 1,
                    total,
                    safeProfileTitle(candidate.profile),
                    appContext.getString(R.string.auto_search_ping_metric, candidate.latencyMs),
                    0L,
                    successful.size(),
                    candidate.latencyMs
                )
            );
            runCandidateDownloadTest(mode, candidate, xraySettings, byeDpiSettings, successful.size());
            if (candidate.stable) {
                stable.add(candidate);
            }
            if (isDownloadSuccessful(candidate)) {
                successful.add(candidate);
                updateState(
                    State.running(
                        mode,
                        appContext.getString(R.string.auto_search_step_download),
                        appContext.getString(R.string.auto_search_download_summary),
                        false,
                        index,
                        total,
                        safeProfileTitle(candidate.profile),
                        appContext.getString(
                            R.string.auto_search_download_ok_metric,
                            UiFormatter.formatBytes(appContext, candidate.downloadedBytes),
                            candidate.successfulAttempts
                        ),
                        0L,
                        successful.size(),
                        candidate.latencyMs
                    )
                );
            } else {
                updateState(
                    State.running(
                        mode,
                        appContext.getString(R.string.auto_search_step_download),
                        appContext.getString(R.string.auto_search_download_summary),
                        false,
                        index,
                        total,
                        safeProfileTitle(candidate.profile),
                        appContext.getString(R.string.auto_search_download_failed_metric),
                        0L,
                        successful.size(),
                        candidate.latencyMs
                    )
                );
            }
            if (successful.size() >= getTargetProfileCount(appContext)) {
                break;
            }
        }
        List<CandidateResult> result = new ArrayList<>();
        result.addAll(stable);
        for (CandidateResult candidate : successful) {
            if (!result.contains(candidate)) {
                result.add(candidate);
            }
        }
        result.sort((left, right) -> compareCandidateResults(right, left));
        return result;
    }

    private boolean isDownloadSuccessful(@Nullable CandidateResult candidate) {
        return (
            candidate != null &&
            candidate.profile != null &&
            candidate.successfulAttempts > 0 &&
            candidate.downloadedBytes > 0L
        );
    }

    private void runCandidateDownloadTest(
        Mode mode,
        CandidateResult candidate,
        XraySettings xraySettings,
        ByeDpiSettings byeDpiSettings,
        int foundProfilesCount
    ) throws Exception {
        int localPort = findAvailableTcpPort();
        String configJson = XrayAutoSearchConfigFactory.buildConfigJson(
            appContext,
            candidate.profile,
            xraySettings,
            localPort,
            byeDpiSettings,
            mode == Mode.WHITELIST
        );
        try {
            stopXrayQuietly();
            XrayBridge.prepareRuntimeDirect(
                xraySettings != null ? xraySettings.remoteDns : null,
                xraySettings != null ? xraySettings.directDns : null
            );
            XrayBridge.runFromJson(appContext, configJson, 0);
            waitForLocalProxy(localPort);
            SystemClock.sleep(XRAY_PROXY_WARMUP_MS);
            if (candidate.pingResponsive) {
                candidate.live = true;
                updateState(
                    State.running(
                        mode,
                        appContext.getString(R.string.auto_search_step_preflight),
                        appContext.getString(R.string.auto_search_preflight_summary),
                        false,
                        1,
                        1,
                        safeProfileTitle(candidate.profile),
                        appContext.getString(R.string.auto_search_ping_metric, candidate.latencyMs),
                        0L,
                        foundProfilesCount,
                        candidate.latencyMs
                    )
                );
            } else {
                ProbeResult probeResult = ensureTrafficUp(mode, candidate, xraySettings, localPort, foundProfilesCount);
                candidate.live = probeResult.success;
                if (!probeResult.success) {
                    return;
                }
            }
            int downloadAttempts = getDownloadAttempts(appContext);
            long downloadSizeBytes = getDownloadSizeBytes(appContext);
            long stableBytes = resolveStableBytes(downloadSizeBytes, downloadAttempts);
            for (int attempt = 1; attempt <= downloadAttempts; attempt++) {
                DownloadResult result = downloadThroughProxy(
                    mode,
                    candidate,
                    xraySettings,
                    localPort,
                    foundProfilesCount,
                    attempt,
                    downloadAttempts,
                    stableBytes
                );
                candidate.downloadedBytes = Math.max(candidate.downloadedBytes, result.bytesRead);
                candidate.successfulAttempts += result.success ? 1 : 0;
                candidate.completedRuns++;
                candidate.totalDownloadSpeedBytesPerSecond += result.averageSpeedBytesPerSecond;
                candidate.averageDownloadSpeedBytesPerSecond =
                    candidate.completedRuns > 0
                        ? candidate.totalDownloadSpeedBytesPerSecond / candidate.completedRuns
                        : 0L;
                candidate.live = candidate.live || result.bytesRead > 0L;
                candidate.stableRuns += meetsDownloadTarget(result.bytesRead, stableBytes) && result.success ? 1 : 0;
                candidate.stable = candidate.stableRuns >= downloadAttempts;
                if (attempt < downloadAttempts) {
                    SystemClock.sleep(INTER_ATTEMPT_DELAY_MS);
                }
            }
        } finally {
            stopXrayQuietly();
        }
    }

    private static long resolveStableBytes(long downloadSizeBytes, int downloadAttempts) {
        return Math.max(1L, downloadSizeBytes);
    }

    private static boolean meetsDownloadTarget(long actualBytes, long targetBytes) {
        long normalizedTarget = Math.max(1L, targetBytes);
        long tolerance = Math.max(
            DOWNLOAD_SUCCESS_TOLERANCE_MIN_BYTES,
            normalizedTarget / DOWNLOAD_SUCCESS_TOLERANCE_DIVISOR
        );
        tolerance = Math.min(DOWNLOAD_SUCCESS_TOLERANCE_MAX_BYTES, tolerance);
        long requiredBytes = Math.max(1L, normalizedTarget - tolerance);
        return Math.max(0L, actualBytes) >= requiredBytes;
    }

    private static int compareCandidateResults(@NonNull CandidateResult left, @NonNull CandidateResult right) {
        int compareAverageSpeed = Long.compare(
            left.averageDownloadSpeedBytesPerSecond,
            right.averageDownloadSpeedBytesPerSecond
        );
        if (compareAverageSpeed != 0) {
            return compareAverageSpeed;
        }
        int compareStableRuns = Integer.compare(left.stableRuns, right.stableRuns);
        if (compareStableRuns != 0) {
            return compareStableRuns;
        }
        int compareSuccessfulRuns = Integer.compare(left.successfulAttempts, right.successfulAttempts);
        if (compareSuccessfulRuns != 0) {
            return compareSuccessfulRuns;
        }
        int compareBytes = Long.compare(left.downloadedBytes, right.downloadedBytes);
        if (compareBytes != 0) {
            return compareBytes;
        }
        return Integer.compare(right.latencyMs, left.latencyMs);
    }

    @NonNull
    private ProbeResult ensureTrafficUp(
        Mode mode,
        CandidateResult candidate,
        XraySettings xraySettings,
        int localPort,
        int foundProfilesCount
    ) {
        for (String url : TRAFFIC_PROBE_URLS) {
            updateState(
                State.running(
                    mode,
                    appContext.getString(R.string.auto_search_step_preflight),
                    appContext.getString(R.string.auto_search_preflight_summary),
                    false,
                    0,
                    TRAFFIC_PROBE_URLS.length,
                    safeProfileTitle(candidate.profile),
                    appContext.getString(R.string.auto_search_preflight_checking_metric, url),
                    0L,
                    foundProfilesCount,
                    candidate.latencyMs
                )
            );
            ProbeResult result = probeTrafficThroughProxy(xraySettings, localPort, url, candidate.pingResponsive);
            if (result.success) {
                updateState(
                    State.running(
                        mode,
                        appContext.getString(R.string.auto_search_step_preflight),
                        appContext.getString(R.string.auto_search_preflight_summary),
                        false,
                        1,
                        1,
                        safeProfileTitle(candidate.profile),
                        appContext.getString(R.string.auto_search_preflight_ok_metric, result.responseCode),
                        0L,
                        foundProfilesCount,
                        candidate.latencyMs
                    )
                );
                return result;
            }
        }
        updateState(
            State.running(
                mode,
                appContext.getString(R.string.auto_search_step_preflight),
                appContext.getString(R.string.auto_search_preflight_summary),
                false,
                1,
                1,
                safeProfileTitle(candidate.profile),
                appContext.getString(R.string.auto_search_preflight_failed_metric),
                0L,
                foundProfilesCount,
                candidate.latencyMs
            )
        );
        return ProbeResult.failed();
    }

    @NonNull
    private ProbeResult probeTrafficThroughProxy(
        XraySettings xraySettings,
        int localPort,
        @NonNull String url,
        boolean responsiveCandidate
    ) {
        return probeTrafficThroughSocks(xraySettings, "127.0.0.1", localPort, url, responsiveCandidate);
    }

    @NonNull
    private ProbeResult probeTrafficThroughSocks(
        XraySettings xraySettings,
        @NonNull String host,
        int port,
        @NonNull String url,
        boolean responsiveCandidate
    ) {
        SocksHttpResult result = requestHttpViaSocks(
            host,
            port,
            resolveLocalSocksUsername(xraySettings),
            resolveLocalSocksPassword(xraySettings),
            url,
            responsiveCandidate ? TRAFFIC_PROBE_RESPONSIVE_CONNECT_TIMEOUT_MS : TRAFFIC_PROBE_CONNECT_TIMEOUT_MS,
            responsiveCandidate ? TRAFFIC_PROBE_RESPONSIVE_READ_TIMEOUT_MS : TRAFFIC_PROBE_READ_TIMEOUT_MS,
            TRAFFIC_PROBE_MAX_BYTES,
            null
        );
        if (!hasSuccessfulTrafficResponse(result)) {
            return ProbeResult.failed();
        }
        return new ProbeResult(true, result.responseCode, result.bytesRead);
    }

    private static boolean hasSuccessfulTrafficResponse(@Nullable SocksHttpResult result) {
        return (
            result != null &&
            result.responseCode >= 200 &&
            result.responseCode < 400 &&
            (result.success || result.bytesRead > 0L)
        );
    }

    @NonNull
    private DownloadResult downloadThroughProxy(
        Mode mode,
        CandidateResult candidate,
        XraySettings xraySettings,
        int localPort,
        int foundProfilesCount,
        int attempt,
        int attemptCount,
        long targetBytes
    ) {
        final long startedAtMs = SystemClock.elapsedRealtime();
        final long singleSuccessBytes = getDownloadSizeBytes(appContext);
        int timeoutMs = getDownloadTimeoutSeconds(appContext) * 1000;
        long readLimitBytes = Math.min(singleSuccessBytes, Math.max(1L, targetBytes));
        long deadlineMs = startedAtMs + timeoutMs;
        long attemptBytesRead = 0L;
        boolean success = false;
        int requestAttempt = 0;
        while (attemptBytesRead < readLimitBytes) {
            long nowMs = SystemClock.elapsedRealtime();
            long remainingTimeMs = deadlineMs - nowMs;
            if (remainingTimeMs <= 0L) {
                break;
            }
            requestAttempt++;
            final int currentRequestAttempt = requestAttempt;
            updateState(
                State.running(
                    mode,
                    appContext.getString(R.string.auto_search_step_download),
                    appContext.getString(R.string.auto_search_download_summary),
                    false,
                    attempt - 1,
                    attemptCount,
                    safeProfileTitle(candidate.profile),
                    appContext.getString(
                        R.string.auto_search_download_connecting_metric,
                        attempt,
                        attemptCount,
                        currentRequestAttempt
                    ),
                    -1L,
                    foundProfilesCount,
                    candidate.latencyMs
                )
            );
            long remainingBytes = readLimitBytes - attemptBytesRead;
            long baseAttemptBytes = attemptBytesRead;
            int requestTimeoutMs = (int) Math.max(1L, remainingTimeMs);
            SocksHttpResult result = requestHttpViaSocks(
                "127.0.0.1",
                localPort,
                resolveLocalSocksUsername(xraySettings),
                resolveLocalSocksPassword(xraySettings),
                DOWNLOAD_TEST_URL_PREFIX + singleSuccessBytes,
                requestTimeoutMs,
                requestTimeoutMs,
                remainingBytes,
                (requestBytes, totalAttemptLimitIgnored) -> {
                    long totalAttemptBytes = baseAttemptBytes + requestBytes;
                    long elapsedMs = Math.max(1L, SystemClock.elapsedRealtime() - startedAtMs);
                    long speed = (totalAttemptBytes * 1000L) / elapsedMs;
                    updateState(
                        State.running(
                            mode,
                            appContext.getString(R.string.auto_search_step_download),
                            appContext.getString(R.string.auto_search_download_summary),
                            false,
                            attempt,
                            attemptCount,
                            safeProfileTitle(candidate.profile),
                            appContext.getString(
                                R.string.auto_search_download_metric,
                                attempt,
                                attemptCount,
                                currentRequestAttempt,
                                UiFormatter.formatBytes(appContext, totalAttemptBytes),
                                UiFormatter.formatBytes(appContext, targetBytes),
                                UiFormatter.formatBytesPerSecond(appContext, speed)
                            ),
                            speed,
                            foundProfilesCount,
                            candidate.latencyMs
                        )
                    );
                }
            );
            attemptBytesRead += result.bytesRead;
            if (hasSuccessfulTrafficResponse(result)) {
                success = true;
            }
            if (attemptBytesRead >= readLimitBytes || result.bytesRead <= 0L) {
                break;
            }
        }
        long elapsedMs = Math.max(1L, SystemClock.elapsedRealtime() - startedAtMs);
        long averageSpeedBytesPerSecond = (attemptBytesRead * 1000L) / elapsedMs;
        return new DownloadResult(success, attemptBytesRead, averageSpeedBytesPerSecond);
    }

    @NonNull
    private SocksHttpResult requestHttpViaSocks(
        @NonNull String proxyHost,
        int proxyPort,
        @Nullable String username,
        @Nullable String password,
        @NonNull String urlValue,
        int connectTimeoutMs,
        int readTimeoutMs,
        long maxBodyBytes,
        @Nullable DownloadProgress progress
    ) {
        Socket socket = null;
        BufferedOutputStream outputStream = null;
        BufferedInputStream inputStream = null;
        int responseCode = -1;
        long bytesRead = 0L;
        try {
            URL url = new URL(urlValue);
            String scheme = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
            boolean tls = TextUtils.equals(scheme, "https");
            String targetHost = url.getHost();
            int targetPort = url.getPort() > 0 ? url.getPort() : tls ? 443 : 80;
            String path = TextUtils.isEmpty(url.getFile()) ? "/" : url.getFile();
            socket = openPreparedSocksSocket(
                proxyHost,
                proxyPort,
                targetHost,
                targetPort,
                username,
                password,
                connectTimeoutMs,
                readTimeoutMs,
                tls
            );
            outputStream = new BufferedOutputStream(socket.getOutputStream());
            inputStream = new BufferedInputStream(socket.getInputStream());

            String request =
                "GET " +
                path +
                " HTTP/1.1\r\n" +
                "Host: " +
                targetHost +
                "\r\n" +
                "User-Agent: " +
                resolveUserAgent() +
                "\r\n" +
                "Accept: */*\r\n" +
                "Accept-Encoding: identity\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            outputStream.write(request.getBytes(StandardCharsets.US_ASCII));
            outputStream.flush();

            String statusLine = readAsciiLine(inputStream, 4096);
            responseCode = parseHttpStatusCode(statusLine);
            if (responseCode <= 0) {
                return SocksHttpResult.failed();
            }
            while (true) {
                String header = readAsciiLine(inputStream, 16 * 1024);
                if (header == null || header.length() == 0) {
                    break;
                }
            }
            if (maxBodyBytes <= 0 || responseCode == 204 || responseCode == 304) {
                return new SocksHttpResult(responseCode >= 200 && responseCode < 400, responseCode, 0L);
            }

            byte[] buffer = new byte[16 * 1024];
            while (bytesRead < maxBodyBytes) {
                int limit = (int) Math.min(buffer.length, maxBodyBytes - bytesRead);
                int read;
                try {
                    read = inputStream.read(buffer, 0, limit);
                } catch (SocketTimeoutException timeout) {
                    return new SocksHttpResult(false, responseCode, bytesRead);
                } catch (IOException bodyReadFailure) {
                    if (responseCode > 0 && bytesRead > 0L) {
                        return new SocksHttpResult(false, responseCode, bytesRead);
                    }
                    throw bodyReadFailure;
                }
                if (read == -1) {
                    break;
                }
                bytesRead += read;
                if (progress != null) {
                    progress.onBytesRead(bytesRead, maxBodyBytes);
                }
            }
            return new SocksHttpResult(responseCode >= 200 && responseCode < 400, responseCode, bytesRead);
        } catch (IOException | IllegalArgumentException ignored) {
            if (responseCode > 0 && bytesRead > 0L) {
                return new SocksHttpResult(false, responseCode, bytesRead);
            }
            return SocksHttpResult.failed();
        } finally {
            closeQuietly(inputStream);
            closeQuietly(outputStream);
            closeQuietly(socket);
        }
    }

    @NonNull
    @SuppressWarnings({ "PMD.CloseResource", "PMD.UseTryWithResources" })
    private Socket openPreparedSocksSocket(
        @NonNull String proxyHost,
        int proxyPort,
        @NonNull String targetHost,
        int targetPort,
        @Nullable String username,
        @Nullable String password,
        int connectTimeoutMs,
        int readTimeoutMs,
        boolean tls
    ) throws IOException {
        Socket socket = null;
        try {
            socket = openSocksTunnel(
                proxyHost,
                proxyPort,
                targetHost,
                targetPort,
                username,
                password,
                connectTimeoutMs,
                readTimeoutMs
            );
            if (tls) {
                socket = wrapTlsSocket(socket, targetHost, targetPort);
                socket.setSoTimeout(readTimeoutMs);
            }
            Socket preparedSocket = socket;
            socket = null;
            return preparedSocket;
        } finally {
            closeQuietly(socket);
        }
    }

    @NonNull
    @SuppressWarnings("PMD.CloseResource")
    private Socket openSocksTunnel(
        @NonNull String proxyHost,
        int proxyPort,
        @NonNull String targetHost,
        int targetPort,
        @Nullable String username,
        @Nullable String password,
        int connectTimeoutMs,
        int readTimeoutMs
    ) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
        byte[] usernameBytes = bytesForSocksAuth(username);
        byte[] passwordBytes = bytesForSocksAuth(password);
        boolean auth = usernameBytes.length > 0 && passwordBytes.length > 0;

        outputStream.write(
            new byte[] {
                SOCKS5_VERSION,
                SOCKS5_COMMAND_CONNECT,
                (byte) (auth ? SOCKS5_METHOD_USERNAME_PASSWORD : SOCKS5_METHOD_NO_AUTH),
            }
        );
        outputStream.flush();
        int version = readByte(inputStream);
        int method = readByte(inputStream);
        if (version != SOCKS5_VERSION || method == SOCKS5_METHOD_NOT_ACCEPTABLE) {
            throw new IOException("SOCKS5 authentication method rejected");
        }
        if (method == SOCKS5_METHOD_USERNAME_PASSWORD) {
            outputStream.write(SOCKS5_AUTH_VERSION);
            outputStream.write(usernameBytes.length);
            outputStream.write(usernameBytes);
            outputStream.write(passwordBytes.length);
            outputStream.write(passwordBytes);
            outputStream.flush();
            int authVersion = readByte(inputStream);
            int authStatus = readByte(inputStream);
            if (authVersion != SOCKS5_AUTH_VERSION || authStatus != SOCKS5_METHOD_NO_AUTH) {
                throw new IOException("SOCKS5 username/password rejected");
            }
        } else if (method != SOCKS5_METHOD_NO_AUTH) {
            throw new IOException("Unsupported SOCKS5 method: " + method);
        }

        byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length == 0 || hostBytes.length > SOCKS5_HOST_MAX_LENGTH) {
            throw new IOException("Invalid SOCKS target host");
        }
        outputStream.write(
            new byte[] {
                SOCKS5_VERSION,
                SOCKS5_COMMAND_CONNECT,
                SOCKS5_METHOD_NO_AUTH,
                SOCKS5_ADDRESS_TYPE_DOMAIN,
                (byte) hostBytes.length,
            }
        );
        outputStream.write(hostBytes);
        outputStream.write((targetPort >>> 8) & 0xff);
        outputStream.write(targetPort & 0xff);
        outputStream.flush();

        int replyVersion = readByte(inputStream);
        int replyCode = readByte(inputStream);
        readByte(inputStream); // RSV
        int addressType = readByte(inputStream);
        if (replyVersion != SOCKS5_VERSION || replyCode != SOCKS5_METHOD_NO_AUTH) {
            throw new IOException("SOCKS5 connect failed: " + replyCode);
        }
        int addressBytes;
        if (addressType == SOCKS5_ADDRESS_TYPE_IPV4) {
            addressBytes = IPV4_ADDRESS_LENGTH;
        } else if (addressType == SOCKS5_ADDRESS_TYPE_IPV6) {
            addressBytes = IPV6_ADDRESS_LENGTH;
        } else if (addressType == SOCKS5_ADDRESS_TYPE_DOMAIN) {
            addressBytes = readByte(inputStream);
        } else {
            throw new IOException("Invalid SOCKS5 bind address type: " + addressType);
        }
        skipFully(inputStream, addressBytes + 2);
        return socket;
    }

    @NonNull
    private Socket wrapTlsSocket(@NonNull Socket socket, @NonNull String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(socket, host, port, true);
        try {
            SSLParameters parameters = sslSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (!isIpLiteral(host)) {
                parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
            }
            sslSocket.setSSLParameters(parameters);
        } catch (IllegalArgumentException | UnsupportedOperationException ignored) {}
        sslSocket.startHandshake();
        return sslSocket;
    }

    @NonNull
    private static byte[] bytesForSocksAuth(@Nullable String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return new byte[0];
        }
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= SOCKS5_HOST_MAX_LENGTH) {
            return bytes;
        }
        byte[] truncated = new byte[SOCKS5_HOST_MAX_LENGTH];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);
        return truncated;
    }

    private static int readByte(@NonNull BufferedInputStream inputStream) throws IOException {
        int value = inputStream.read();
        if (value < 0) {
            throw new IOException("Unexpected EOF");
        }
        return value;
    }

    private static void skipFully(@NonNull BufferedInputStream inputStream, int bytes) throws IOException {
        int remaining = Math.max(0, bytes);
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped < MIN_SKIPPED_BYTES) {
                readByte(inputStream);
                skipped = MIN_SKIPPED_BYTES;
            }
            remaining -= (int) skipped;
        }
    }

    @Nullable
    private static String readAsciiLine(@NonNull BufferedInputStream inputStream, int maxBytes) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < maxBytes; index++) {
            int value = inputStream.read();
            if (value < 0) {
                return builder.length() > 0 ? builder.toString() : null;
            }
            if (value == LINE_FEED) {
                break;
            }
            if (value != CARRIAGE_RETURN) {
                builder.append((char) value);
            }
        }
        return builder.toString();
    }

    private static int parseHttpStatusCode(@Nullable String statusLine) {
        if (statusLine == null || !statusLine.startsWith("HTTP/")) {
            return -1;
        }
        String[] parts = statusLine.split(" ", HTTP_STATUS_PARTS_LIMIT);
        if (parts.length < HTTP_STATUS_PARTS_MIN) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean isIpLiteral(@NonNull String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d+(\\.\\d+){3}");
    }

    @NonNull
    private String resolveUserAgent() {
        try {
            String versionName = appContext
                .getPackageManager()
                .getPackageInfo(appContext.getPackageName(), 0)
                .versionName;
            if (!TextUtils.isEmpty(versionName)) {
                return "WINGSV/" + versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {}
        return "WINGSV";
    }

    private static void closeQuietly(@Nullable AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ignored) {}
    }

    private static final class ExecutorScope implements AutoCloseable {

        private final ExecutorService executor;

        private ExecutorScope(int threadCount) {
            this.executor = Executors.newFixedThreadPool(Math.max(1, threadCount));
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    @NonNull
    private String resolveLocalSocksUsername(@Nullable XraySettings settings) {
        if (settings == null || !settings.localProxyAuthEnabled) {
            return "";
        }
        return TextUtils.isEmpty(settings.localProxyUsername) ? "" : settings.localProxyUsername;
    }

    @NonNull
    private String resolveLocalSocksPassword(@Nullable XraySettings settings) {
        if (settings == null || !settings.localProxyAuthEnabled) {
            return "";
        }
        return TextUtils.isEmpty(settings.localProxyPassword) ? "" : settings.localProxyPassword;
    }

    private void persistAutoSearchProfiles(
        List<CandidateResult> foundCandidates,
        List<XrayProfile> allProfiles,
        List<XrayProfile> originalProfiles
    ) {
        List<XrayProfile> updatedProfiles = new ArrayList<>();
        List<XrayProfile> baseProfiles = mergeProfileLists(allProfiles, originalProfiles);
        java.util.Map<String, CandidateResult> candidateByKey = new LinkedHashMap<>();
        for (CandidateResult candidate : foundCandidates) {
            candidateByKey.put(candidate.profile.stableDedupKey(), candidate);
        }
        for (XrayProfile profile : baseProfiles) {
            String stableKey = profile.stableDedupKey();
            CandidateResult candidate = candidateByKey.get(stableKey);
            if (candidate != null) {
                XrayProfile tagged = tagAutoSearchProfile(profile);
                updatedProfiles.add(tagged);
                XrayStore.putProfilePingResult(
                    appContext,
                    XrayStore.getProfilePingKey(tagged),
                    true,
                    candidate.latencyMs
                );
                candidate.profile = tagged;
            } else {
                updatedProfiles.add(profile);
            }
        }
        XrayStore.setProfiles(appContext, updatedProfiles);
    }

    @NonNull
    private static XrayProfile tagAutoSearchProfile(@NonNull XrayProfile profile) {
        return new XrayProfile(
            profile.id,
            profile.title,
            profile.rawLink,
            AUTOSEARCH_SUBSCRIPTION_ID,
            AUTOSEARCH_SUBSCRIPTION_TITLE,
            profile.address,
            profile.port
        );
    }

    @NonNull
    private static List<XrayProfile> mergeProfileLists(
        @Nullable List<XrayProfile> primary,
        @Nullable List<XrayProfile> fallback
    ) {
        List<XrayProfile> result = new ArrayList<>();
        java.util.Map<String, XrayProfile> byKey = new LinkedHashMap<>();
        if (primary != null) {
            for (XrayProfile profile : primary) {
                if (profile != null && !TextUtils.isEmpty(profile.rawLink)) {
                    byKey.put(profile.stableDedupKey(), profile);
                }
            }
        }
        if (fallback != null) {
            for (XrayProfile profile : fallback) {
                if (
                    profile != null &&
                    !TextUtils.isEmpty(profile.rawLink) &&
                    !byKey.containsKey(profile.stableDedupKey())
                ) {
                    byKey.put(profile.stableDedupKey(), profile);
                }
            }
        }
        result.addAll(byKey.values());
        return result;
    }

    private PendingResult buildPendingResult(
        SearchSession session,
        List<CandidateResult> foundCandidates,
        CandidateResult bestCandidate
    ) {
        PendingResult result = new PendingResult();
        result.mode = session.mode;
        result.serviceWasActive = session.serviceWasActive;
        result.originalBackend = session.originalBackend;
        result.originalActiveProfileId = session.originalActiveProfileId;
        result.originalByeDpiAutoStart = session.originalByeDpiAutoStart;
        result.bestProfile = bestCandidate;
        result.foundProfiles = new ArrayList<>(foundCandidates);
        result.targetBackend = BackendType.XRAY;
        result.targetActiveProfileId = bestCandidate.profile.id;
        result.targetByeDpiAutoStart = session.mode == Mode.WHITELIST;
        result.configurationChanged =
            session.originalBackend != result.targetBackend ||
            !TextUtils.equals(session.originalActiveProfileId, result.targetActiveProfileId) ||
            session.originalByeDpiAutoStart != result.targetByeDpiAutoStart;
        return result;
    }

    private void applyOrRestoreConfiguration(PendingResult result, boolean apply) {
        BackendType backendType = apply ? result.targetBackend : result.originalBackend;
        String activeProfileId = apply ? result.targetActiveProfileId : result.originalActiveProfileId;
        boolean byeDpiEnabled = apply ? result.targetByeDpiAutoStart : result.originalByeDpiAutoStart;

        XrayStore.setBackendType(appContext, backendType);
        if (!TextUtils.isEmpty(activeProfileId)) {
            XrayStore.setActiveProfileId(appContext, activeProfileId);
        }
        SharedPreferences preferences = AppPrefs.defaultSharedPreferences(appContext);
        preferences.edit().putBoolean(ByeDpiStore.KEY_AUTO_START_WITH_XRAY, byeDpiEnabled).apply();

        if (result.serviceWasActive) {
            startProxyTunnelService();
        }
    }

    private void restoreOriginalConfiguration(SearchSession session) {
        XrayStore.setBackendType(appContext, session.originalBackend);
        if (!TextUtils.isEmpty(session.originalActiveProfileId)) {
            XrayStore.setActiveProfileId(appContext, session.originalActiveProfileId);
        }
        AppPrefs.defaultSharedPreferences(appContext)
            .edit()
            .putBoolean(ByeDpiStore.KEY_AUTO_START_WITH_XRAY, session.originalByeDpiAutoStart)
            .apply();
        if (session.serviceWasActive) {
            startProxyTunnelService();
        }
    }

    private void startProxyTunnelService() {
        BackendType targetBackend = XrayStore.getBackendType(appContext);
        try {
            ContextCompat.startForegroundService(
                appContext,
                ProxyTunnelService.createStartIntent(appContext, targetBackend)
            );
        } catch (IllegalStateException | SecurityException ignored) {
            try {
                appContext.startService(ProxyTunnelService.createStartIntent(appContext, targetBackend));
            } catch (IllegalStateException | SecurityException ignoredAgain) {
                Log.w(TAG, "Unable to start ProxyTunnelService for autosearch", ignoredAgain);
            }
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private ByeDpiLocalRunner startTemporaryByeDpi(ByeDpiSettings settings) throws Exception {
        ByeDpiLocalRunner runner = new ByeDpiLocalRunner();
        try {
            runner.start(settings, null, BYEDPI_START_TIMEOUT_MS);
            return runner;
        } catch (Exception error) {
            runner.close();
            throw new IllegalStateException(appContext.getString(R.string.auto_search_failed_bydpi), error);
        }
    }

    private void warmUpTemporaryByeDpi(Mode mode, @Nullable ByeDpiLocalRunner runner) throws Exception {
        if (runner == null || !runner.isRunning()) {
            throw new IllegalStateException(appContext.getString(R.string.auto_search_failed_bydpi));
        }
        String host = runner.getDialHost();
        int port = runner.getDialPort();
        for (int attempt = 1; attempt <= BYEDPI_WARMUP_ATTEMPTS; attempt++) {
            updateState(
                State.running(
                    mode,
                    appContext.getString(R.string.auto_search_step_bydpi),
                    appContext.getString(R.string.auto_search_bydpi_summary),
                    false,
                    attempt - 1,
                    BYEDPI_WARMUP_ATTEMPTS,
                    "",
                    appContext.getString(R.string.auto_search_preflight_checking_metric, host + ":" + port),
                    0L,
                    0,
                    0
                )
            );
            if (isLocalTcpPortReady(host, port)) {
                return;
            }
            if (attempt < BYEDPI_WARMUP_ATTEMPTS) {
                SystemClock.sleep(BYEDPI_WARMUP_DELAY_MS);
            }
        }
        throw new IllegalStateException(appContext.getString(R.string.auto_search_failed_bydpi));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void stopTemporaryByeDpi(@Nullable ByeDpiLocalRunner runner) {
        if (runner == null) {
            return;
        }
        try {
            runner.close();
        } catch (RuntimeException ignored) {}
    }

    private void waitForLocalProxy(int port) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + XRAY_PROXY_START_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isLocalTcpPortReady("127.0.0.1", port)) {
                return;
            }
            SystemClock.sleep(XRAY_PROXY_START_POLL_MS);
        }
        throw new IllegalStateException(appContext.getString(R.string.auto_search_failed_local_proxy));
    }

    private boolean isLocalTcpPortReady(String host, int port) {
        if (TextUtils.isEmpty(host) || port <= 0) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private int findAvailableTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void restoreOriginalConfigurationQuietly(@NonNull SearchSession session) {
        try {
            restoreOriginalConfiguration(session);
        } catch (RuntimeException ignored) {}
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void stopXrayQuietly() {
        try {
            XrayBridge.stop();
        } catch (Exception ignored) {}
    }

    private CandidateResult chooseBestCandidate(List<CandidateResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return Collections.max(candidates, AutoSearchManager::compareCandidateResults);
    }

    private void updateState(@NonNull State newState) {
        state = newState;
        for (Listener listener : listeners) {
            notifyListener(listener, newState);
        }
    }

    private void notifyListener(@NonNull Listener listener, @NonNull State currentState) {
        MAIN_HANDLER.post(() -> listener.onStateChanged(currentState));
    }

    @NonNull
    private static String safeProfileTitle(@Nullable XrayProfile profile) {
        if (profile == null || TextUtils.isEmpty(profile.title)) {
            return "";
        }
        return profile.title;
    }

    @NonNull
    private static String firstNonEmpty(@Nullable String value, @NonNull String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    public interface Listener {
        void onStateChanged(@NonNull State state);
    }

    public enum Mode {
        STANDARD,
        WHITELIST,
    }

    public enum Status {
        IDLE,
        RUNNING,
        AWAITING_MODE_SELECTION,
        AWAITING_APPLY,
        COMPLETED,
        FAILED,
    }

    public static final class State {

        public final Status status;
        public final Mode mode;
        public final String stepTitle;
        public final String stepSummary;
        public final boolean indeterminate;
        public final int progressCurrent;
        public final int progressMax;
        public final String currentProfileTitle;
        public final String currentMetric;
        public final long currentSpeedBytesPerSecond;
        public final int foundProfilesCount;
        public final int currentLatencyMs;
        public final long token;

        private State(
            Status status,
            Mode mode,
            String stepTitle,
            String stepSummary,
            boolean indeterminate,
            int progressCurrent,
            int progressMax,
            String currentProfileTitle,
            String currentMetric,
            long currentSpeedBytesPerSecond,
            int foundProfilesCount,
            int currentLatencyMs,
            long token
        ) {
            this.status = status;
            this.mode = mode;
            this.stepTitle = stepTitle;
            this.stepSummary = stepSummary;
            this.indeterminate = indeterminate;
            this.progressCurrent = progressCurrent;
            this.progressMax = progressMax;
            this.currentProfileTitle = currentProfileTitle;
            this.currentMetric = currentMetric;
            this.currentSpeedBytesPerSecond = currentSpeedBytesPerSecond;
            this.foundProfilesCount = foundProfilesCount;
            this.currentLatencyMs = currentLatencyMs;
            this.token = token;
        }

        static State idle() {
            return new State(Status.IDLE, Mode.STANDARD, "", "", true, 0, 0, "", "", 0L, 0, 0, 0L);
        }

        @SuppressWarnings("PMD.ExcessiveParameterList")
        static State running(
            Mode mode,
            String stepTitle,
            String stepSummary,
            boolean indeterminate,
            int progressCurrent,
            int progressMax,
            String currentProfileTitle,
            String currentMetric,
            long currentSpeedBytesPerSecond,
            int foundProfilesCount,
            int currentLatencyMs
        ) {
            return new State(
                Status.RUNNING,
                mode,
                stepTitle,
                stepSummary,
                indeterminate,
                progressCurrent,
                progressMax,
                currentProfileTitle,
                currentMetric,
                currentSpeedBytesPerSecond,
                foundProfilesCount,
                currentLatencyMs,
                0L
            );
        }

        static State awaitingApply(
            Mode mode,
            String message,
            int foundProfilesCount,
            String currentProfileTitle,
            long token
        ) {
            return new State(
                Status.AWAITING_APPLY,
                mode,
                "",
                message,
                true,
                0,
                0,
                currentProfileTitle,
                "",
                0L,
                foundProfilesCount,
                0,
                token
            );
        }

        static State awaitingModeSelection(String message, int foundProfilesCount, long token) {
            return new State(
                Status.AWAITING_MODE_SELECTION,
                null,
                "",
                message,
                true,
                0,
                0,
                "",
                "",
                0L,
                foundProfilesCount,
                0,
                token
            );
        }

        static State completed(Mode mode, String message, int foundProfilesCount, String currentProfileTitle) {
            return new State(
                Status.COMPLETED,
                mode,
                "",
                message,
                true,
                0,
                0,
                currentProfileTitle,
                "",
                0L,
                foundProfilesCount,
                0,
                0L
            );
        }

        static State failed(Mode mode, String message) {
            return new State(Status.FAILED, mode, "", message, true, 0, 0, "", "", 0L, 0, 0, 0L);
        }
    }

    private static final class SearchSession {

        Mode mode;
        BackendType originalBackend;
        String originalActiveProfileId;
        boolean originalByeDpiAutoStart;
        boolean serviceWasActive;
        List<XrayProfile> originalProfiles = Collections.emptyList();
    }

    private static final class PreparedSearch {

        final SearchSession session;
        final List<XrayProfile> availableProfiles;
        final XraySettings xraySettings;
        final ByeDpiSettings byeDpiSettings;
        long token;

        PreparedSearch(
            SearchSession session,
            List<XrayProfile> availableProfiles,
            XraySettings xraySettings,
            ByeDpiSettings byeDpiSettings
        ) {
            this.session = session;
            this.availableProfiles =
                availableProfiles != null ? new ArrayList<>(availableProfiles) : Collections.emptyList();
            this.xraySettings = xraySettings != null ? xraySettings : new XraySettings();
            this.byeDpiSettings = byeDpiSettings != null ? byeDpiSettings : new ByeDpiSettings();
        }
    }

    private static final class PendingResult {

        Mode mode;
        boolean serviceWasActive;
        BackendType originalBackend;
        String originalActiveProfileId;
        boolean originalByeDpiAutoStart;
        BackendType targetBackend;
        String targetActiveProfileId;
        boolean targetByeDpiAutoStart;
        boolean configurationChanged;
        CandidateResult bestProfile;
        List<CandidateResult> foundProfiles = Collections.emptyList();
        long token;
    }

    private static final class CandidateResult {

        XrayProfile profile;
        int latencyMs;
        boolean pingResponsive;
        long downloadedBytes;
        int successfulAttempts;
        int completedRuns;
        int stableRuns;
        long totalDownloadSpeedBytesPerSecond;
        long averageDownloadSpeedBytesPerSecond;
        boolean live;
        boolean stable;

        CandidateResult(XrayProfile profile) {
            this.profile = profile;
        }
    }

    @NonNull
    private static CandidateResult failedCandidate() {
        return new CandidateResult(null);
    }

    private static final class DownloadResult {

        final boolean success;
        final long bytesRead;
        final long averageSpeedBytesPerSecond;

        DownloadResult(boolean success, long bytesRead, long averageSpeedBytesPerSecond) {
            this.success = success;
            this.bytesRead = Math.max(0L, bytesRead);
            this.averageSpeedBytesPerSecond = Math.max(0L, averageSpeedBytesPerSecond);
        }
    }

    private interface DownloadProgress {
        void onBytesRead(long bytesRead, long targetBytes);
    }

    private static final class SocksHttpResult {

        final boolean success;
        final int responseCode;
        final long bytesRead;

        SocksHttpResult(boolean success, int responseCode, long bytesRead) {
            this.success = success;
            this.responseCode = responseCode;
            this.bytesRead = Math.max(0L, bytesRead);
        }

        static SocksHttpResult failed() {
            return new SocksHttpResult(false, -1, 0L);
        }
    }

    private static final class ProbeResult {

        final boolean success;
        final int responseCode;
        final long bytesRead;

        ProbeResult(boolean success, int responseCode, long bytesRead) {
            this.success = success;
            this.responseCode = responseCode;
            this.bytesRead = Math.max(0L, bytesRead);
        }

        static ProbeResult failed() {
            return new ProbeResult(false, 0, 0L);
        }
    }
}
