package wings.v;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import wings.v.core.AutoSearchManager;
import wings.v.core.Haptics;
import wings.v.core.UiFormatter;
import wings.v.core.XrayStore;
import wings.v.databinding.FragmentFirstLaunchAutoSearchRunBinding;
import wings.v.databinding.ItemFirstLaunchAutoSearchProfileBinding;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class FirstLaunchAutoSearchRunFragment extends Fragment {

    public interface Host {
        @Nullable
        AutoSearchManager.Mode getFirstLaunchAutoSearchMode();

        long getFirstLaunchAutoSearchGeneration();

        boolean isFirstLaunchAutoSearchRunPageActive();

        void onFirstLaunchAutoSearchFinished();

        void onFirstLaunchAutoSearchBackToMode();
    }

    private static final int PROFILE_CHAIN_LIMIT = 50;
    private static final long FAILED_PROFILE_REMOVE_DELAY_MS = 5_000L;
    private static final int PING_GOOD_THRESHOLD_MS = 150;
    private static final int PING_WARNING_THRESHOLD_MS = 350;

    @Nullable
    private FragmentFirstLaunchAutoSearchRunBinding binding;

    private AutoSearchManager manager;
    private AutoSearchManager.Mode mode;
    private boolean started;
    private boolean whitelistConfirmed;
    private boolean finishedDispatched;
    private long generation;
    private long lastModeToken;
    private long lastApplyToken;
    private final LinkedHashMap<String, ItemFirstLaunchAutoSearchProfileBinding> rows = new LinkedHashMap<>();
    private final LinkedHashSet<String> pendingRemoval = new LinkedHashSet<>();
    private final AutoSearchManager.Listener listener = this::renderState;

    public static FirstLaunchAutoSearchRunFragment create() {
        return new FirstLaunchAutoSearchRunFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFirstLaunchAutoSearchRunBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = AutoSearchManager.getInstance(requireContext());
        mode =
            getActivity() instanceof Host
                ? ((Host) getActivity()).getFirstLaunchAutoSearchMode()
                : AutoSearchManager.Mode.STANDARD;
        if (mode == null) {
            mode = AutoSearchManager.Mode.STANDARD;
        }
        generation = getActivity() instanceof Host ? ((Host) getActivity()).getFirstLaunchAutoSearchGeneration() : 0L;
    }

    @Override
    public void onStart() {
        super.onStart();
        manager.registerListener(listener);
        maybeStart();
        renderState(manager.getState());
    }

    @Override
    public void onResume() {
        super.onResume();
        maybeStart();
        if (manager != null) {
            renderState(manager.getState());
        }
    }

    @Override
    public void onStop() {
        manager.unregisterListener(listener);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        rows.clear();
        pendingRemoval.clear();
        binding = null;
        super.onDestroyView();
    }

    private void maybeStart() {
        refreshModeFromHost();
        if (!isRunPageActive()) {
            return;
        }
        if (mode == null) {
            return;
        }
        if (started || manager.isRunning()) {
            return;
        }
        if (XrayStore.getProfiles(requireContext()).isEmpty() && !hasValidatedInternet(requireContext())) {
            showOfflineNoProfiles();
            return;
        }
        started = true;
        whitelistConfirmed = false;
        finishedDispatched = false;
        rows.clear();
        if (binding != null) {
            binding.containerAutoSearchProfiles.removeAllViews();
        }
        manager.startSearch();
    }

    private void renderState(@NonNull AutoSearchManager.State state) {
        if (binding == null) {
            return;
        }
        if (
            state.status == AutoSearchManager.Status.RUNNING ||
            state.status == AutoSearchManager.Status.IDLE ||
            state.status == AutoSearchManager.Status.COMPLETED
        ) {
            hideActions();
        }
        binding.layoutAutoSearchProgress.setVisibility(View.VISIBLE);
        binding.imageAutoSearchRun.setVisibility(View.VISIBLE);

        binding.textAutoSearchStatus.setText(statusLabel(state));
        binding.textAutoSearchTitle.setText(titleFor(state));
        binding.textAutoSearchSummary.setText(summaryFor(state));
        binding.progressAutoSearchState.setVisibility(
            state.status == AutoSearchManager.Status.RUNNING ? View.VISIBLE : View.GONE
        );

        binding.progressAutoSearch.setIndeterminate(state.indeterminate);
        if (!state.indeterminate && state.progressMax > 0) {
            binding.progressAutoSearch.setMax(state.progressMax);
            binding.progressAutoSearch.setProgress(Math.max(0, state.progressCurrent));
            binding.textAutoSearchProgress.setText(
                getString(R.string.auto_search_progress_value, state.progressCurrent, state.progressMax) +
                    " · " +
                    getString(
                        R.string.auto_search_found_value,
                        state.foundProfilesCount,
                        AutoSearchManager.getTargetProfileCount(requireContext())
                    )
            );
        } else {
            binding.textAutoSearchProgress.setText(
                getString(
                    R.string.auto_search_found_value,
                    state.foundProfilesCount,
                    AutoSearchManager.getTargetProfileCount(requireContext())
                )
            );
        }

        if (state.currentSpeedBytesPerSecond > 0L) {
            binding.textAutoSearchSpeed.setText(
                getString(R.string.auto_search_speed_label) +
                    ": " +
                    UiFormatter.formatBytesPerSecond(requireContext(), state.currentSpeedBytesPerSecond)
            );
            binding.textAutoSearchSpeed.setVisibility(View.VISIBLE);
        } else if (state.currentSpeedBytesPerSecond < 0L) {
            binding.textAutoSearchSpeed.setText(R.string.auto_search_speed_waiting);
            binding.textAutoSearchSpeed.setVisibility(View.VISIBLE);
        } else {
            binding.textAutoSearchSpeed.setVisibility(View.GONE);
        }

        updateProfileChain(state);
        handleActionState(state);
    }

    private void handleActionState(@NonNull AutoSearchManager.State state) {
        if (binding == null) {
            return;
        }
        if (
            state.status == AutoSearchManager.Status.AWAITING_MODE_SELECTION &&
            state.token != 0L &&
            mode == AutoSearchManager.Mode.WHITELIST
        ) {
            if (!whitelistConfirmed) {
                lastModeToken = state.token;
                showWhitelistWait();
            }
            return;
        }
        if (
            state.status == AutoSearchManager.Status.AWAITING_MODE_SELECTION &&
            state.token != 0L &&
            state.token != lastModeToken
        ) {
            lastModeToken = state.token;
            manager.continueSearch(AutoSearchManager.Mode.STANDARD);
            return;
        }
        if (
            state.status == AutoSearchManager.Status.AWAITING_APPLY &&
            state.token != 0L &&
            state.token != lastApplyToken
        ) {
            lastApplyToken = state.token;
            showApplyActions();
            return;
        }
        if (state.status == AutoSearchManager.Status.FAILED) {
            showRetryActions();
            return;
        }
        if (state.status == AutoSearchManager.Status.COMPLETED && state.foundProfilesCount > 0 && !finishedDispatched) {
            finishedDispatched = true;
            binding
                .getRoot()
                .postDelayed(
                    () -> {
                        if (getActivity() instanceof Host) {
                            ((Host) getActivity()).onFirstLaunchAutoSearchFinished();
                        }
                    },
                    650L
                );
        }
    }

    private void showOfflineNoProfiles() {
        if (binding == null) {
            return;
        }
        binding.textAutoSearchStatus.setText(R.string.auto_search_status_failed);
        binding.textAutoSearchTitle.setText(R.string.first_launch_auto_search_need_internet_title);
        binding.textAutoSearchSummary.setText(R.string.first_launch_auto_search_need_internet_summary);
        binding.progressAutoSearchState.setVisibility(View.GONE);
        binding.layoutAutoSearchProgress.setVisibility(View.GONE);
        binding.containerAutoSearchProfiles.removeAllViews();
        showTwoActions(
            R.string.first_launch_auto_search_retry,
            R.string.first_launch_auto_search_back,
            view -> {
                Haptics.softConfirm(view);
                maybeStart();
            },
            view -> {
                Haptics.softSelection(view);
                if (getActivity() instanceof Host) {
                    ((Host) getActivity()).onFirstLaunchAutoSearchBackToMode();
                }
            }
        );
    }

    private void showWhitelistWait() {
        if (binding == null) {
            return;
        }
        showTwoActions(
            R.string.first_launch_auto_search_whitelist_ready,
            R.string.auto_search_cancel_action,
            view -> {
                Haptics.softConfirm(view);
                whitelistConfirmed = true;
                manager.continueSearch(AutoSearchManager.Mode.WHITELIST);
                hideActions();
            },
            view -> {
                Haptics.softSelection(view);
                manager.cancelPendingModeSelection();
                if (getActivity() instanceof Host) {
                    ((Host) getActivity()).onFirstLaunchAutoSearchBackToMode();
                }
            }
        );
        binding.textAutoSearchTitle.setText(R.string.first_launch_auto_search_whitelist_wait_title);
        binding.textAutoSearchSummary.setText(R.string.first_launch_auto_search_whitelist_wait_summary);
        binding.progressAutoSearchState.setVisibility(View.GONE);
        binding.layoutAutoSearchProgress.setVisibility(View.GONE);
    }

    private void showApplyActions() {
        showTwoActions(
            R.string.auto_search_apply_action,
            R.string.auto_search_restore_action,
            view -> {
                Haptics.softConfirm(view);
                manager.applyPendingConfiguration(true);
                hideActions();
            },
            view -> {
                Haptics.softSelection(view);
                manager.applyPendingConfiguration(false);
                hideActions();
            }
        );
    }

    private void showRetryActions() {
        showTwoActions(
            R.string.first_launch_auto_search_retry,
            R.string.first_launch_auto_search_back,
            view -> {
                Haptics.softConfirm(view);
                started = false;
                maybeStart();
            },
            view -> {
                Haptics.softSelection(view);
                if (getActivity() instanceof Host) {
                    ((Host) getActivity()).onFirstLaunchAutoSearchBackToMode();
                }
            }
        );
    }

    private void showTwoActions(
        int primaryText,
        int secondaryText,
        View.OnClickListener primaryClick,
        View.OnClickListener secondaryClick
    ) {
        if (binding == null) {
            return;
        }
        binding.buttonAutoSearchPrimary.setText(primaryText);
        binding.buttonAutoSearchSecondary.setText(secondaryText);
        binding.buttonAutoSearchPrimary.setOnClickListener(primaryClick);
        binding.buttonAutoSearchSecondary.setOnClickListener(secondaryClick);
        binding.buttonAutoSearchPrimary.setVisibility(View.VISIBLE);
        binding.buttonAutoSearchSecondary.setVisibility(View.VISIBLE);
    }

    private void hideActions() {
        if (binding == null) {
            return;
        }
        binding.buttonAutoSearchPrimary.setVisibility(View.GONE);
        binding.buttonAutoSearchSecondary.setVisibility(View.GONE);
    }

    private void refreshModeFromHost() {
        if (!(getActivity() instanceof Host)) {
            return;
        }
        Host host = (Host) getActivity();
        long currentGeneration = host.getFirstLaunchAutoSearchGeneration();
        if (currentGeneration == generation) {
            return;
        }
        generation = currentGeneration;
        mode = host.getFirstLaunchAutoSearchMode();
        if (mode == null) {
            mode = AutoSearchManager.Mode.STANDARD;
        }
        started = false;
        whitelistConfirmed = false;
        finishedDispatched = false;
        lastModeToken = 0L;
        lastApplyToken = 0L;
    }

    private void updateProfileChain(@NonNull AutoSearchManager.State state) {
        String title = state.currentProfileTitle == null ? "" : state.currentProfileTitle.trim();
        if (binding == null || title.isEmpty()) {
            return;
        }
        ItemFirstLaunchAutoSearchProfileBinding row = rows.get(title);
        boolean newRow = false;
        if (row == null) {
            row = ItemFirstLaunchAutoSearchProfileBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.containerAutoSearchProfiles,
                false
            );
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, dp(8));
            binding.containerAutoSearchProfiles.addView(row.getRoot(), 0, params);
            rows.put(title, row);
            trimProfileRows();
            newRow = true;
        }
        row.textAutoSearchProfileTitle.setText(title);
        row.textAutoSearchProfileSummary.setText(
            TextUtils.isEmpty(state.currentMetric) ? titleFor(state) : state.currentMetric
        );
        boolean failedProfileState = isFailedProfileState(state);
        if (failedProfileState) {
            row.progressAutoSearchProfile.setVisibility(View.GONE);
            row.textAutoSearchProfileBadge.setVisibility(View.VISIBLE);
            row.textAutoSearchProfileBadge.setText(R.string.xray_profiles_ping_error_badge);
            row.textAutoSearchProfileBadge.setBackgroundResource(R.drawable.bg_profile_ping_bad);
        } else if (state.currentLatencyMs > 0) {
            row.progressAutoSearchProfile.setVisibility(View.GONE);
            row.textAutoSearchProfileBadge.setVisibility(View.VISIBLE);
            row.textAutoSearchProfileBadge.setText(getString(R.string.auto_search_ping_metric, state.currentLatencyMs));
            row.textAutoSearchProfileBadge.setBackgroundResource(pingBackground(state.currentLatencyMs));
        } else if (state.status == AutoSearchManager.Status.RUNNING) {
            row.progressAutoSearchProfile.setVisibility(View.VISIBLE);
            row.textAutoSearchProfileBadge.setVisibility(View.GONE);
        } else {
            row.progressAutoSearchProfile.setVisibility(View.GONE);
            row.textAutoSearchProfileBadge.setVisibility(View.GONE);
        }
        if (newRow) {
            row.getRoot().setAlpha(0f);
            row.getRoot().setTranslationY(-dp(10));
            row.getRoot().animate().alpha(1f).translationY(0f).setDuration(180L).start();
        }
        if (failedProfileState) {
            scheduleFailedProfileRemoval(title);
        } else {
            pendingRemoval.remove(title);
        }
    }

    private void trimProfileRows() {
        while (rows.size() > PROFILE_CHAIN_LIMIT) {
            String oldest = null;
            for (String key : rows.keySet()) {
                oldest = key;
                break;
            }
            if (oldest == null) {
                return;
            }
            ItemFirstLaunchAutoSearchProfileBinding row = rows.remove(oldest);
            if (row != null && binding != null) {
                binding.containerAutoSearchProfiles.removeView(row.getRoot());
            }
        }
    }

    private void scheduleFailedProfileRemoval(@NonNull String title) {
        if (binding == null || !pendingRemoval.add(title)) {
            return;
        }
        binding
            .getRoot()
            .postDelayed(
                () -> {
                    if (binding == null || !pendingRemoval.remove(title)) {
                        return;
                    }
                    ItemFirstLaunchAutoSearchProfileBinding row = rows.remove(title);
                    if (row == null) {
                        return;
                    }
                    row
                        .getRoot()
                        .animate()
                        .alpha(0f)
                        .translationY(-dp(10))
                        .setDuration(220L)
                        .withEndAction(() -> {
                            if (binding != null) {
                                binding.containerAutoSearchProfiles.removeView(row.getRoot());
                            }
                        })
                        .start();
                },
                FAILED_PROFILE_REMOVE_DELAY_MS
            );
    }

    private boolean isFailedProfileState(@NonNull AutoSearchManager.State state) {
        String metric = state.currentMetric == null ? "" : state.currentMetric.trim();
        return (
            TextUtils.equals(metric, getString(R.string.auto_search_ping_failed_metric)) ||
            TextUtils.equals(metric, getString(R.string.auto_search_preflight_failed_metric)) ||
            TextUtils.equals(metric, getString(R.string.auto_search_download_failed_metric))
        );
    }

    private int pingBackground(int latencyMs) {
        if (latencyMs <= PING_GOOD_THRESHOLD_MS) {
            return R.drawable.bg_profile_ping_good;
        }
        if (latencyMs <= PING_WARNING_THRESHOLD_MS) {
            return R.drawable.bg_profile_ping_warning;
        }
        return R.drawable.bg_profile_ping_bad;
    }

    private String statusLabel(@NonNull AutoSearchManager.State state) {
        switch (state.status) {
            case RUNNING:
                return getString(R.string.auto_search_status_running);
            case AWAITING_MODE_SELECTION:
            case AWAITING_APPLY:
                return getString(R.string.auto_search_status_waiting);
            case COMPLETED:
                return getString(R.string.auto_search_status_completed);
            case FAILED:
                return getString(R.string.auto_search_status_failed);
            case IDLE:
            default:
                return getString(R.string.auto_search_status_idle);
        }
    }

    private String titleFor(@NonNull AutoSearchManager.State state) {
        if (!TextUtils.isEmpty(state.stepTitle)) {
            return state.stepTitle;
        }
        if (state.status == AutoSearchManager.Status.FAILED) {
            return getString(R.string.auto_search_state_failed_title);
        }
        if (state.status == AutoSearchManager.Status.COMPLETED) {
            return getString(R.string.auto_search_state_completed_title);
        }
        if (
            state.status == AutoSearchManager.Status.AWAITING_APPLY ||
            state.status == AutoSearchManager.Status.AWAITING_MODE_SELECTION
        ) {
            return getString(R.string.auto_search_state_waiting_title);
        }
        return getString(R.string.auto_search_state_idle_title);
    }

    private String summaryFor(@NonNull AutoSearchManager.State state) {
        if (!TextUtils.isEmpty(state.stepSummary)) {
            return state.stepSummary;
        }
        return getString(R.string.auto_search_state_idle_summary);
    }

    private boolean hasValidatedInternet(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return (
            capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isRunPageActive() {
        return getActivity() instanceof Host && ((Host) getActivity()).isFirstLaunchAutoSearchRunPageActive();
    }
}
