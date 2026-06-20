package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import wings.v.R;
import wings.v.byedpi.ByeDpiLocalRunner;
import wings.v.service.EmergencyVpnResetService;
import wings.v.service.ProxyTunnelService;
import wings.v.service.XrayVpnService;
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

    private static final String TAG = "WINGSV/AutoSearch";

    public static final String KEY_OPEN_SETTINGS = "pref_open_auto_search_settings";
    public static final String AUTOSEARCH_SUBSCRIPTION_ID = "__autosearch__";
    public static final String AUTOSEARCH_FILTER_ID = "sub:" + AUTOSEARCH_SUBSCRIPTION_ID;
    public static final String KEY_TARGET_COUNT = "pref_auto_search_target_count";
    public static final String KEY_TCPING_TIMEOUT_MS = "pref_auto_search_tcping_timeout_ms";
    public static final String KEY_DOWNLOAD_SIZE_MB = "pref_auto_search_download_size_mb";
    public static final String KEY_DOWNLOAD_TIMEOUT_SECONDS = "pref_auto_search_download_timeout_seconds";
    public static final String KEY_DOWNLOAD_ATTEMPTS = "pref_auto_search_download_attempts";

    private static final int DEFAULT_TCPING_TIMEOUT_MS = 1_000;
    private static final int TCPING_PARALLELISM = 5;
    private static final int DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_DOWNLOAD_ATTEMPTS = 2;
    private static final int DEFAULT_TARGET_COUNT = 5;
    private static final int DEFAULT_DOWNLOAD_SIZE_MB = 5;
    private static final long SERVICE_STOP_TIMEOUT_MS = 8_000L;
    private static final long SERVICE_STOP_POLL_MS = 200L;
    private static final long VPN_RELEASE_GRACE_MS = 1_000L;
    private static final long EMERGENCY_VPN_RESET_HOLD_MS = 1_200L;
    private static final long EMERGENCY_VPN_RESET_TIMEOUT_MS = 4_000L;
    private static final long BYEDPI_START_TIMEOUT_MS = 4_000L;
    private static final int BYEDPI_WARMUP_ATTEMPTS = 3;
    private static final long BYEDPI_WARMUP_DELAY_MS = 500L;
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
        int total = pingSuccess.size();
        if (total == 0) {
            return new ArrayList<>();
        }
        int targetCount = getTargetProfileCount(appContext);
        int parallelism = Math.min(wings.v.service.XrayAutoSearchProbeService.workerCount(), total);
        long stableBytes = resolveStableBytes(getDownloadSizeBytes(appContext));
        List<CandidateResult> stable = new ArrayList<>();
        List<CandidateResult> successful = new ArrayList<>();

        ExecutorService dispatchPool = Executors.newFixedThreadPool(parallelism);
        ExecutorCompletionService<CandidatePair> completion = new ExecutorCompletionService<>(dispatchPool);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger successfulCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        int submitted = 0;
        try {
            for (int i = 0; i < pingSuccess.size(); i++) {
                CandidateResult candidate = pingSuccess.get(i);
                int workerIndex = i % parallelism;
                int candidateOrdinal = i + 1;
                completion.submit(() -> {
                    if (stop.get()) {
                        return new CandidatePair(candidate, AutoSearchProbeResult.failure("cancelled"));
                    }
                    AutoSearchProbeRequest request = buildProbeRequest(
                        candidate,
                        xraySettings,
                        byeDpiSettings,
                        mode == Mode.WHITELIST,
                        candidateOrdinal,
                        total,
                        stableBytes
                    );
                    AutoSearchProbeResult result = invokeWorker(
                        workerIndex,
                        request,
                        candidate,
                        mode,
                        total,
                        successfulCounter
                    );
                    return new CandidatePair(candidate, result);
                });
                submitted++;
                updateState(
                    State.running(
                        mode,
                        appContext.getString(R.string.auto_search_step_download),
                        appContext.getString(R.string.auto_search_download_summary),
                        false,
                        Math.min(submitted, total),
                        total,
                        safeProfileTitle(candidate.profile),
                        appContext.getString(R.string.auto_search_ping_metric, candidate.latencyMs),
                        0L,
                        successful.size(),
                        candidate.latencyMs
                    )
                );
            }

            int completed = 0;
            while (completed < submitted) {
                Future<CandidatePair> future = completion.take();
                CandidatePair pair;
                try {
                    pair = future.get();
                } catch (ExecutionException ignored) {
                    pair = null;
                }
                completed++;
                if (pair == null || pair.candidate == null) {
                    continue;
                }
                applyProbeResultToCandidate(pair.candidate, pair.result);
                if (pair.candidate.stable && !stable.contains(pair.candidate)) {
                    stable.add(pair.candidate);
                }
                if (isDownloadSuccessful(pair.candidate) && !successful.contains(pair.candidate)) {
                    successful.add(pair.candidate);
                    successfulCounter.set(successful.size());
                }
                updateState(
                    State.running(
                        mode,
                        appContext.getString(R.string.auto_search_step_download),
                        appContext.getString(R.string.auto_search_download_summary),
                        false,
                        completed,
                        total,
                        safeProfileTitle(pair.candidate.profile),
                        isDownloadSuccessful(pair.candidate)
                            ? appContext.getString(
                                  R.string.auto_search_download_ok_metric,
                                  UiFormatter.formatBytes(appContext, pair.candidate.downloadedBytes),
                                  pair.candidate.successfulAttempts
                              )
                            : appContext.getString(R.string.auto_search_download_failed_metric),
                        0L,
                        successful.size(),
                        pair.candidate.latencyMs
                    )
                );
                if (successful.size() >= targetCount && !stop.get()) {
                    // Soft cancel: queued tasks bail at lambda entry via stop.get(),
                    // in-flight probes see CANCEL via the kernel cancelSignal at the
                    // next safe checkpoint. We do NOT break the while loop or shut
                    // down the dispatch pool early: those introduced a race with the
                    // probe services that froze the UI on low target counts.
                    stop.set(true);
                    for (int idx = 0; idx < parallelism; idx++) {
                        wings.v.service.XrayAutoSearchProbeService.cancelProbe(appContext, idx);
                    }
                }
            }
        } finally {
            dispatchPool.shutdownNow();
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

    @NonNull
    private AutoSearchProbeRequest buildProbeRequest(
        @NonNull CandidateResult candidate,
        @Nullable XraySettings xraySettings,
        @Nullable ByeDpiSettings byeDpiSettings,
        boolean whitelistMode,
        int candidateOrdinal,
        int totalCandidates,
        long stableBytes
    ) {
        AutoSearchProbeRequest request = new AutoSearchProbeRequest();
        request.profile = candidate.profile;
        request.xraySettings = xraySettings;
        request.byeDpiSettings = whitelistMode ? byeDpiSettings : null;
        request.whitelistMode = whitelistMode;
        request.pingResponsive = candidate.pingResponsive;
        request.latencyMs = candidate.latencyMs;
        request.downloadAttempts = getDownloadAttempts(appContext);
        request.downloadSizeBytes = getDownloadSizeBytes(appContext);
        request.downloadTimeoutSeconds = getDownloadTimeoutSeconds(appContext);
        request.stableBytes = stableBytes;
        request.candidateOrdinal = candidateOrdinal;
        request.totalCandidates = totalCandidates;
        return request;
    }

    @NonNull
    private AutoSearchProbeResult invokeWorker(
        int workerIndex,
        @NonNull AutoSearchProbeRequest request,
        @NonNull CandidateResult candidate,
        @NonNull Mode mode,
        int totalCandidates,
        @NonNull java.util.concurrent.atomic.AtomicInteger successfulCounter
    ) {
        java.util.concurrent.atomic.AtomicReference<AutoSearchProbeResult> sink =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        android.os.ResultReceiver receiver = new android.os.ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, android.os.Bundle resultData) {
                if (resultCode == wings.v.service.XrayAutoSearchProbeService.RESULT_PROGRESS) {
                    handleWorkerProgress(mode, candidate, request, totalCandidates, successfulCounter, resultData);
                } else if (resultCode == wings.v.service.XrayAutoSearchProbeService.RESULT_DELIVERED) {
                    sink.set(AutoSearchProbeResult.fromBundle(resultData));
                    latch.countDown();
                }
            }
        };
        boolean started = wings.v.service.XrayAutoSearchProbeService.startProbe(
            appContext,
            workerIndex,
            request,
            receiver
        );
        if (!started) {
            return AutoSearchProbeResult.failure("worker dispatch failed");
        }
        long perCandidateBudgetMs =
            (long) Math.max(1, request.downloadAttempts) * (long) Math.max(1, request.downloadTimeoutSeconds) * 1000L +
            30_000L;
        try {
            if (!latch.await(perCandidateBudgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return AutoSearchProbeResult.failure("worker timeout");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return AutoSearchProbeResult.failure("interrupted");
        }
        AutoSearchProbeResult result = sink.get();
        return result != null ? result : AutoSearchProbeResult.failure("empty result");
    }

    private void handleWorkerProgress(
        @NonNull Mode mode,
        @NonNull CandidateResult candidate,
        @NonNull AutoSearchProbeRequest request,
        int totalCandidates,
        @NonNull java.util.concurrent.atomic.AtomicInteger successfulCounter,
        @Nullable android.os.Bundle bundle
    ) {
        if (bundle == null) {
            return;
        }
        String stage = bundle.getString(AutoSearchProbeKernel.PROGRESS_KEY_STAGE, "");
        int attempt = bundle.getInt(AutoSearchProbeKernel.PROGRESS_KEY_ATTEMPT, 0);
        int attemptCount = bundle.getInt(AutoSearchProbeKernel.PROGRESS_KEY_ATTEMPT_COUNT, request.downloadAttempts);
        long bytesRead = bundle.getLong(AutoSearchProbeKernel.PROGRESS_KEY_BYTES_READ, 0L);
        long targetBytes = bundle.getLong(AutoSearchProbeKernel.PROGRESS_KEY_TARGET_BYTES, request.downloadSizeBytes);
        long speed = bundle.getLong(AutoSearchProbeKernel.PROGRESS_KEY_SPEED, 0L);

        String metric;
        switch (stage) {
            case AutoSearchProbeKernel.STAGE_PREFLIGHT:
                metric = appContext.getString(R.string.auto_search_preflight_summary);
                break;
            case AutoSearchProbeKernel.STAGE_DOWNLOAD:
                if (bytesRead > 0L) {
                    metric = appContext.getString(
                        R.string.auto_search_download_metric,
                        attempt,
                        attemptCount,
                        attempt,
                        UiFormatter.formatBytes(appContext, bytesRead),
                        UiFormatter.formatBytes(appContext, targetBytes),
                        UiFormatter.formatBytesPerSecond(appContext, speed)
                    );
                } else {
                    metric = appContext.getString(
                        R.string.auto_search_download_connecting_metric,
                        attempt,
                        attemptCount,
                        attempt
                    );
                }
                break;
            case AutoSearchProbeKernel.STAGE_WARMUP:
            default:
                metric = appContext.getString(R.string.auto_search_ping_metric, candidate.latencyMs);
                break;
        }

        updateState(
            State.running(
                mode,
                appContext.getString(R.string.auto_search_step_download),
                appContext.getString(R.string.auto_search_download_summary),
                false,
                request.candidateOrdinal,
                totalCandidates,
                safeProfileTitle(candidate.profile),
                metric,
                speed,
                successfulCounter.get(),
                candidate.latencyMs
            )
        );
    }

    private static void applyProbeResultToCandidate(
        @NonNull CandidateResult candidate,
        @NonNull AutoSearchProbeResult probe
    ) {
        candidate.live = candidate.live || probe.live;
        candidate.downloadedBytes = Math.max(candidate.downloadedBytes, probe.downloadedBytes);
        candidate.successfulAttempts = probe.successfulAttempts;
        candidate.completedRuns = probe.completedRuns;
        candidate.stableRuns = probe.stableRuns;
        candidate.totalDownloadSpeedBytesPerSecond = probe.totalSpeedBytesPerSecond;
        candidate.averageDownloadSpeedBytesPerSecond = probe.averageSpeedBytesPerSecond;
        candidate.stable = probe.stable;
    }

    private static final class CandidatePair {

        final CandidateResult candidate;
        final AutoSearchProbeResult result;

        CandidatePair(CandidateResult candidate, AutoSearchProbeResult result) {
            this.candidate = candidate;
            this.result = result != null ? result : AutoSearchProbeResult.failure("null result");
        }
    }

    private boolean isDownloadSuccessful(@Nullable CandidateResult candidate) {
        return (
            candidate != null &&
            candidate.profile != null &&
            candidate.successfulAttempts > 0 &&
            candidate.downloadedBytes > 0L
        );
    }

    private static long resolveStableBytes(long downloadSizeBytes) {
        return Math.max(1L, downloadSizeBytes);
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
                XrayProfile tagged = tagAutoSearchProfile(appContext, profile);
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
    private static XrayProfile tagAutoSearchProfile(@NonNull Context context, @NonNull XrayProfile profile) {
        return new XrayProfile(
            profile.id,
            profile.title,
            profile.rawLink,
            AUTOSEARCH_SUBSCRIPTION_ID,
            context.getString(wings.v.R.string.auto_search_subscription_title),
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
}
