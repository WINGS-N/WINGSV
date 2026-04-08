package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatCheckBox;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import wings.v.core.AppPrefs;
import wings.v.core.AutoSearchManager;
import wings.v.core.Haptics;
import wings.v.core.UiFormatter;
import wings.v.databinding.ActivityAutoSearchBinding;
import wings.v.databinding.ItemProfileEntryBinding;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.CommentDefaultAccessModifier",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class AutoSearchActivity extends AppCompatActivity {

    private static final String EXTRA_EXECUTION_MODE = "wings.v.extra.AUTO_SEARCH_EXECUTION_MODE";
    private static final int PING_GOOD_THRESHOLD_MS = 150;
    private static final int PING_WARNING_THRESHOLD_MS = 350;
    private static final int PROFILE_CHAIN_LIMIT = 50;
    private static final long FAILED_PROFILE_REMOVE_DELAY_MS = 5_000L;

    private final AutoSearchManager.Listener stateListener = this::renderState;
    private final LinkedHashMap<String, ItemProfileEntryBinding> profileChainRows = new LinkedHashMap<>();
    private final LinkedHashSet<String> pendingFailedProfileRemoval = new LinkedHashSet<>();

    private ActivityAutoSearchBinding binding;
    private AutoSearchManager manager;
    private long lastApplyDialogToken;
    private long lastModeDialogToken;
    private boolean executionMode;
    private boolean executionSearchStarted;
    private boolean initialModeDialogShown;
    private boolean navigatedToProfiles;
    private AutoSearchManager.Mode requestedMode;

    public static Intent createIntent(Context context) {
        return new Intent(context, AutoSearchActivity.class);
    }

    public static Intent createRunIntent(Context context) {
        return new Intent(context, AutoSearchActivity.class).putExtra(EXTRA_EXECUTION_MODE, true);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAutoSearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        manager = AutoSearchManager.getInstance(this);
        executionMode = getIntent().getBooleanExtra(EXTRA_EXECUTION_MODE, false);
        if (executionMode && savedInstanceState == null) {
            manager.resetFinishedState();
        }
        executionSearchStarted =
            savedInstanceState != null && savedInstanceState.getBoolean("execution_search_started", false);

        binding.rowStartAutoSearch.setOnClickListener(view -> {
            Haptics.softSelection(view);
            startActivity(createRunIntent(this));
        });
        binding.rowAutoSearchTargetCount.setOnClickListener(view ->
            showNumberSettingDialog(
                R.string.auto_search_setting_target_count,
                AutoSearchManager.getTargetProfileCount(this),
                1,
                20,
                value -> AutoSearchManager.setTargetProfileCount(this, value)
            )
        );
        binding.rowAutoSearchTcpingTimeout.setOnClickListener(view ->
            showNumberSettingDialog(
                R.string.auto_search_setting_tcping_timeout,
                AutoSearchManager.getTcpingTimeoutMs(this),
                300,
                10_000,
                value -> AutoSearchManager.setTcpingTimeoutMs(this, value)
            )
        );
        binding.rowAutoSearchDownloadSize.setOnClickListener(view ->
            showNumberSettingDialog(
                R.string.auto_search_setting_download_size,
                AutoSearchManager.getDownloadSizeMb(this),
                1,
                100,
                value -> AutoSearchManager.setDownloadSizeMb(this, value)
            )
        );
        binding.rowAutoSearchDownloadTimeout.setOnClickListener(view ->
            showNumberSettingDialog(
                R.string.auto_search_setting_download_timeout,
                AutoSearchManager.getDownloadTimeoutSeconds(this),
                3,
                120,
                value -> AutoSearchManager.setDownloadTimeoutSeconds(this, value)
            )
        );
        binding.rowAutoSearchDownloadAttempts.setOnClickListener(view ->
            showNumberSettingDialog(
                R.string.auto_search_setting_download_attempts,
                AutoSearchManager.getDownloadAttempts(this),
                1,
                10,
                value -> AutoSearchManager.setDownloadAttempts(this, value)
            )
        );
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("execution_search_started", executionSearchStarted);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        executionSearchStarted = savedInstanceState.getBoolean("execution_search_started", executionSearchStarted);
    }

    @Override
    protected void onStart() {
        super.onStart();
        manager.registerListener(stateListener);
        maybeStartExecutionSearch();
        renderState(manager.getState());
    }

    @Override
    protected void onStop() {
        manager.unregisterListener(stateListener);
        super.onStop();
    }

    private void renderState(@NonNull AutoSearchManager.State state) {
        if (binding == null) {
            return;
        }

        binding.layoutAutoSearchSetupContent.setVisibility(executionMode ? View.GONE : View.VISIBLE);
        binding.layoutAutoSearchRunContent.setVisibility(executionMode ? View.VISIBLE : View.GONE);

        boolean running = state.status == AutoSearchManager.Status.RUNNING;
        boolean awaitingAction =
            state.status == AutoSearchManager.Status.AWAITING_MODE_SELECTION ||
            state.status == AutoSearchManager.Status.AWAITING_APPLY;
        boolean startEnabled =
            state.status == AutoSearchManager.Status.IDLE ||
            state.status == AutoSearchManager.Status.COMPLETED ||
            state.status == AutoSearchManager.Status.FAILED;

        binding.rowStartAutoSearch.setEnabled(!executionMode || startEnabled);
        binding.rowStartAutoSearch.setSummary(
            running || awaitingAction
                ? getString(R.string.auto_search_running_summary)
                : getString(R.string.auto_search_start_summary)
        );
        binding.progressStartAutoSearch.setVisibility(running ? View.VISIBLE : View.GONE);
        renderSettingsRows();

        String title;
        String summary;
        switch (state.status) {
            case RUNNING:
                title = safe(state.stepTitle, R.string.auto_search_step_prepare);
                summary = safe(state.stepSummary, R.string.auto_search_prepare_summary);
                break;
            case AWAITING_MODE_SELECTION:
            case AWAITING_APPLY:
                title = getString(R.string.auto_search_state_waiting_title);
                summary = safe(state.stepSummary, R.string.auto_search_mode_prompt_message);
                break;
            case COMPLETED:
                title = getString(R.string.auto_search_state_completed_title);
                summary = safe(state.stepSummary, R.string.auto_search_complete_not_applied);
                break;
            case FAILED:
                title = getString(R.string.auto_search_state_failed_title);
                summary = safe(state.stepSummary, R.string.auto_search_failed_refresh);
                break;
            case IDLE:
            default:
                title = getString(R.string.auto_search_state_idle_title);
                summary = getString(R.string.auto_search_state_idle_summary);
                break;
        }
        binding.textAutoSearchStatus.setText(statusLabel(state));
        binding.textAutoSearchStateTitle.setText(title);
        binding.textAutoSearchStateSummary.setText(summary);
        binding.progressAutoSearchState.setVisibility(running ? View.VISIBLE : View.GONE);

        binding.progressAutoSearchOverall.setIndeterminate(state.indeterminate);
        if (!state.indeterminate && state.progressMax > 0) {
            binding.progressAutoSearchOverall.setMax(state.progressMax);
            binding.progressAutoSearchOverall.setProgress(Math.max(0, state.progressCurrent));
        } else if (!state.indeterminate) {
            binding.progressAutoSearchOverall.setMax(100);
            binding.progressAutoSearchOverall.setProgress(0);
        }

        if (!state.indeterminate && state.progressMax > 0) {
            binding.textAutoSearchProgressValue.setText(
                getString(R.string.auto_search_progress_value, state.progressCurrent, state.progressMax)
            );
        } else {
            binding.textAutoSearchProgressValue.setText(getString(R.string.auto_search_metric_empty));
        }

        binding.textAutoSearchMetricValue.setText(
            state.currentMetric == null || state.currentMetric.trim().isEmpty()
                ? getString(R.string.auto_search_metric_empty)
                : state.currentMetric
        );
        if (state.currentSpeedBytesPerSecond > 0L) {
            binding.textAutoSearchSpeedValue.setText(
                getString(R.string.auto_search_speed_label) +
                    ": " +
                    UiFormatter.formatBytesPerSecond(this, state.currentSpeedBytesPerSecond)
            );
            binding.textAutoSearchSpeedValue.setVisibility(View.VISIBLE);
        } else if (state.currentSpeedBytesPerSecond < 0L) {
            binding.textAutoSearchSpeedValue.setText(R.string.auto_search_speed_waiting);
            binding.textAutoSearchSpeedValue.setVisibility(View.VISIBLE);
        } else {
            binding.textAutoSearchSpeedValue.setText("");
            binding.textAutoSearchSpeedValue.setVisibility(View.GONE);
        }
        binding.textAutoSearchFoundValue.setText(
            getString(
                R.string.auto_search_found_value,
                state.foundProfilesCount,
                AutoSearchManager.getTargetProfileCount(this)
            )
        );

        if (executionMode) {
            updateProfileChain(state);
            maybeContinueWithRequestedMode(state);
            maybeShowApplyDialog(state);
            maybeNavigateToProfiles(state);
        }
    }

    private void maybeStartExecutionSearch() {
        if (!executionMode || executionSearchStarted || manager.isRunning()) {
            return;
        }
        AutoSearchManager.State state = manager.getState();
        if (
            state.status == AutoSearchManager.Status.AWAITING_APPLY ||
            state.status == AutoSearchManager.Status.AWAITING_MODE_SELECTION
        ) {
            executionSearchStarted = true;
            return;
        }
        showInitialModeDialog();
    }

    private void showInitialModeDialog() {
        if (initialModeDialogShown) {
            return;
        }
        initialModeDialogShown = true;
        AppCompatCheckBox builtinSubscriptionCheckbox = new AppCompatCheckBox(this);
        builtinSubscriptionCheckbox.setText(R.string.auto_search_use_builtin_subscription);
        builtinSubscriptionCheckbox.setChecked(true);
        int padding = dp(20);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, 0, padding, 0);
        container.addView(
            builtinSubscriptionCheckbox,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        );
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.auto_search_mode_prompt_title)
            .setMessage(R.string.auto_search_mode_prompt_message)
            .setView(container)
            .setPositiveButton(R.string.auto_search_mode_standard_title, (dialog, which) ->
                startExecutionSearch(AutoSearchManager.Mode.STANDARD, builtinSubscriptionCheckbox.isChecked())
            )
            .setNegativeButton(R.string.auto_search_mode_whitelist_title, (dialog, which) ->
                startExecutionSearch(AutoSearchManager.Mode.WHITELIST, builtinSubscriptionCheckbox.isChecked())
            )
            .setNeutralButton(R.string.auto_search_cancel_action, (dialog, which) -> finish())
            .setOnCancelListener(dialog -> finish())
            .show();
    }

    private void startExecutionSearch(@NonNull AutoSearchManager.Mode mode, boolean useBuiltInSubscription) {
        requestedMode = mode;
        executionSearchStarted = true;
        profileChainRows.clear();
        binding.containerAutoSearchProfileChain.removeAllViews();
        manager.startSearch(useBuiltInSubscription);
    }

    private void renderSettingsRows() {
        binding.rowAutoSearchTargetCount.setSummary(
            getString(R.string.auto_search_setting_target_count_summary, AutoSearchManager.getTargetProfileCount(this))
        );
        binding.rowAutoSearchTcpingTimeout.setSummary(
            getString(R.string.auto_search_setting_tcping_timeout_summary, AutoSearchManager.getTcpingTimeoutMs(this))
        );
        binding.rowAutoSearchDownloadSize.setSummary(
            getString(R.string.auto_search_setting_download_size_summary, AutoSearchManager.getDownloadSizeMb(this))
        );
        binding.rowAutoSearchDownloadTimeout.setSummary(
            getString(
                R.string.auto_search_setting_download_timeout_summary,
                AutoSearchManager.getDownloadTimeoutSeconds(this)
            )
        );
        binding.rowAutoSearchDownloadAttempts.setSummary(
            getString(
                R.string.auto_search_setting_download_attempts_summary,
                AutoSearchManager.getDownloadAttempts(this)
            )
        );
    }

    private void updateProfileChain(@NonNull AutoSearchManager.State state) {
        if (
            state.status != AutoSearchManager.Status.RUNNING &&
            state.status != AutoSearchManager.Status.AWAITING_APPLY &&
            state.status != AutoSearchManager.Status.COMPLETED
        ) {
            return;
        }
        String title = state.currentProfileTitle == null ? "" : state.currentProfileTitle.trim();
        if (title.isEmpty()) {
            return;
        }
        ItemProfileEntryBinding rowBinding = profileChainRows.get(title);
        boolean newRow = false;
        if (rowBinding == null) {
            rowBinding = ItemProfileEntryBinding.inflate(
                LayoutInflater.from(this),
                binding.containerAutoSearchProfileChain,
                false
            );
            rowBinding.rowProfileEntry.setClickable(false);
            rowBinding.rowProfileEntry.setFocusable(false);
            rowBinding.rowProfileEntry.setForeground(null);
            rowBinding.checkboxProfileSelected.setVisibility(View.GONE);
            rowBinding.imageProfileActive.setVisibility(View.GONE);
            rowBinding.layoutProfileTraffic.setVisibility(View.GONE);
            rowBinding.viewProfileDivider.setVisibility(View.GONE);
            binding.containerAutoSearchProfileChain.addView(rowBinding.getRoot(), 0);
            profileChainRows.put(title, rowBinding);
            trimProfileChain();
            newRow = true;
        }
        rowBinding.textProfileTitle.setText(title);
        rowBinding.textProfileSummary.setText(
            TextUtils.isEmpty(state.currentMetric)
                ? safe(state.stepTitle, R.string.auto_search_step_download)
                : state.currentMetric
        );
        if (state.currentLatencyMs > 0) {
            rowBinding.progressProfilePing.setVisibility(View.GONE);
            rowBinding.textProfilePing.setVisibility(View.VISIBLE);
            rowBinding.textProfilePing.setText(getString(R.string.auto_search_ping_metric, state.currentLatencyMs));
            rowBinding.textProfilePing.setBackgroundResource(pingBackground(state.currentLatencyMs));
        } else if (state.status == AutoSearchManager.Status.RUNNING) {
            rowBinding.progressProfilePing.setVisibility(View.VISIBLE);
            rowBinding.textProfilePing.setVisibility(View.GONE);
        } else {
            rowBinding.progressProfilePing.setVisibility(View.GONE);
            rowBinding.textProfilePing.setVisibility(View.GONE);
        }
        if (newRow) {
            rowBinding.getRoot().setAlpha(0f);
            rowBinding.getRoot().setTranslationY(-dp(10));
            rowBinding.getRoot().animate().alpha(1f).translationY(0f).setDuration(180L).start();
        }
        if (isFailedProfileState(state)) {
            scheduleFailedProfileRemoval(title);
        } else {
            pendingFailedProfileRemoval.remove(title);
        }
    }

    private void trimProfileChain() {
        while (profileChainRows.size() > PROFILE_CHAIN_LIMIT) {
            String oldestKey = null;
            for (String key : profileChainRows.keySet()) {
                oldestKey = key;
                break;
            }
            if (oldestKey == null) {
                return;
            }
            ItemProfileEntryBinding oldRow = profileChainRows.remove(oldestKey);
            if (oldRow != null) {
                binding.containerAutoSearchProfileChain.removeView(oldRow.getRoot());
            }
        }
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

    private boolean isFailedProfileState(@NonNull AutoSearchManager.State state) {
        String metric = state.currentMetric == null ? "" : state.currentMetric.trim();
        return (
            TextUtils.equals(metric, getString(R.string.auto_search_ping_failed_metric)) ||
            TextUtils.equals(metric, getString(R.string.auto_search_preflight_failed_metric)) ||
            TextUtils.equals(metric, getString(R.string.auto_search_download_failed_metric))
        );
    }

    private void scheduleFailedProfileRemoval(@NonNull String title) {
        if (!pendingFailedProfileRemoval.add(title)) {
            return;
        }
        binding
            .getRoot()
            .postDelayed(
                () -> {
                    if (binding == null || !pendingFailedProfileRemoval.remove(title)) {
                        return;
                    }
                    ItemProfileEntryBinding rowBinding = profileChainRows.remove(title);
                    if (rowBinding == null) {
                        return;
                    }
                    rowBinding
                        .getRoot()
                        .animate()
                        .alpha(0f)
                        .translationY(-dp(10))
                        .setDuration(220L)
                        .withEndAction(() -> {
                            if (binding != null) {
                                binding.containerAutoSearchProfileChain.removeView(rowBinding.getRoot());
                            }
                        })
                        .start();
                },
                FAILED_PROFILE_REMOVE_DELAY_MS
            );
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

    private void maybeContinueWithRequestedMode(@NonNull AutoSearchManager.State state) {
        if (
            state.status != AutoSearchManager.Status.AWAITING_MODE_SELECTION ||
            state.token == 0L ||
            state.token == lastModeDialogToken
        ) {
            return;
        }
        lastModeDialogToken = state.token;
        if (requestedMode != null) {
            AutoSearchManager.Mode mode = requestedMode;
            requestedMode = null;
            manager.continueSearch(mode);
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.auto_search_mode_prompt_title)
            .setMessage(state.stepSummary)
            .setPositiveButton(R.string.auto_search_mode_standard_title, (dialog, which) ->
                manager.continueSearch(AutoSearchManager.Mode.STANDARD)
            )
            .setNegativeButton(R.string.auto_search_mode_whitelist_title, (dialog, which) ->
                manager.continueSearch(AutoSearchManager.Mode.WHITELIST)
            )
            .setNeutralButton(R.string.auto_search_cancel_action, (dialog, which) ->
                manager.cancelPendingModeSelection()
            )
            .setOnCancelListener(dialog -> manager.cancelPendingModeSelection())
            .show();
    }

    private void maybeShowApplyDialog(@NonNull AutoSearchManager.State state) {
        if (
            state.status != AutoSearchManager.Status.AWAITING_APPLY ||
            state.token == 0L ||
            state.token == lastApplyDialogToken
        ) {
            return;
        }
        lastApplyDialogToken = state.token;
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.auto_search_apply_prompt_title)
            .setMessage(state.stepSummary)
            .setPositiveButton(R.string.auto_search_apply_action, (dialog, which) ->
                manager.applyPendingConfiguration(true)
            )
            .setNegativeButton(R.string.auto_search_restore_action, (dialog, which) ->
                manager.applyPendingConfiguration(false)
            )
            .setOnCancelListener(dialog -> manager.applyPendingConfiguration(false))
            .show();
    }

    private void maybeNavigateToProfiles(@NonNull AutoSearchManager.State state) {
        if (
            navigatedToProfiles ||
            manager.isRunning() ||
            state.status != AutoSearchManager.Status.COMPLETED ||
            state.foundProfilesCount <= 0
        ) {
            return;
        }
        navigatedToProfiles = true;
        binding.getRoot().postDelayed(this::navigateToAutoSearchProfiles, 500L);
    }

    private void navigateToAutoSearchProfiles() {
        AppPrefs.setPendingProfilesFilterId(this, AutoSearchManager.AUTOSEARCH_FILTER_ID);
        Intent intent = new Intent(this, MainActivity.class)
            .putExtra(MainActivity.EXTRA_FORCE_CURRENT_TAB_ID, R.id.menu_profiles)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void showNumberSettingDialog(
        int titleRes,
        int currentValue,
        int minValue,
        int maxValue,
        @NonNull IntSettingSetter setter
    ) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(currentValue));
        input.setSelectAllOnFocus(true);
        int padding = dp(20);
        LinearLayout container = new LinearLayout(this);
        container.setPadding(padding, 0, padding, 0);
        container.addView(
            input,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        );
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                int value = parseInt(input.getText() != null ? input.getText().toString() : "", currentValue);
                setter.set(Math.max(minValue, Math.min(maxValue, value)));
                renderSettingsRows();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private int parseInt(@Nullable String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safe(@Nullable String value, int fallbackResId) {
        return value == null || value.isBlank() ? getString(fallbackResId) : value;
    }

    private interface IntSettingSetter {
        void set(int value);
    }
}
