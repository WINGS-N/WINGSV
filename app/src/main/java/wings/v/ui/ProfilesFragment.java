package wings.v.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import dev.oneuiproject.oneui.widget.Separator;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import wings.v.MainActivity;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.UiFormatter;
import wings.v.core.WingsImportParser;
import wings.v.core.XrayProfile;
import wings.v.core.XrayStore;
import wings.v.core.XraySubscriptionUpdater;
import wings.v.databinding.FragmentProfilesBinding;
import wings.v.databinding.ItemProfileEntryBinding;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.AvoidCatchingGenericException",
        "PMD.NullAssignment",
        "PMD.ExceptionAsFlowControl",
        "PMD.CommentRequired",
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
        "PMD.OnlyOneReturn",
        "PMD.CommentDefaultAccessModifier",
        "PMD.LooseCoupling",
        "PMD.ShortVariable",
        "PMD.CouplingBetweenObjects",
        "PMD.AtLeastOneConstructor",
        "PMD.AvoidLiteralsInIfCondition",
        "PMD.ShortMethodName",
    }
)
public class ProfilesFragment extends Fragment {

    private static final int TCPING_TIMEOUT_MS = 1000;
    private static final int TCPING_PARALLELISM = 5;
    private static final int PING_GOOD_THRESHOLD_MS = 150;
    private static final int PING_WARNING_THRESHOLD_MS = 350;
    private static final int PAGE_SIZE = 5;
    private static final int LOAD_MORE_THRESHOLD_DP = 320;
    private static final long TRAFFIC_REFRESH_INTERVAL_MS = 1_000L;
    private static final String FILTER_ALL = "__all__";
    private static final String FILTER_NO_SUBSCRIPTION = "__manual__";

    private final ExecutorService workExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService tcpingExecutor = Executors.newFixedThreadPool(TCPING_PARALLELISM);
    private final LinkedHashMap<String, FilterSpec> filterSpecs = new LinkedHashMap<>();
    private final LinkedHashMap<String, ProfileRowViews> rowViews = new LinkedHashMap<>();
    private final LinkedHashMap<String, PingState> pingStates = new LinkedHashMap<>();
    private final LinkedHashMap<String, XrayStore.ProfileTrafficStats> profileTrafficStats = new LinkedHashMap<>();
    private final LinkedHashSet<String> selectedProfileIds = new LinkedHashSet<>();
    private final ArrayList<DisplayItem> currentDisplayItems = new ArrayList<>();
    private final Runnable trafficRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (binding == null || !isAdded()) {
                return;
            }
            refreshVisibleProfileTrafficStats(true);
            binding.getRoot().removeCallbacks(this);
            binding.getRoot().postDelayed(this, TRAFFIC_REFRESH_INTERVAL_MS);
        }
    };

    private FragmentProfilesBinding binding;
    private List<XrayProfile> currentProfiles = new ArrayList<>();
    private String currentActiveProfileId = "";
    private String activeFilterId = FILTER_ALL;
    private String currentRenderSignature = "";
    private boolean selectionMode;
    private boolean refreshingSubscriptions;
    private boolean tcpingRunning;
    private boolean pageAppendRunning;
    private int tcpingGeneration;
    private int renderGeneration;
    private int renderedItemCount;
    private RoundedLinearLayout currentAppendGroupContainer;
    private OnBackPressedCallback selectionBackCallback;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectionBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                clearSelectionMode();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, selectionBackCallback);
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentProfilesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.rowRefreshSubscriptions.setTitle(getString(R.string.xray_profiles_refresh_subscriptions_title));
        binding.rowTcppingActiveProfile.setTitle(getString(R.string.xray_profiles_tcping_title));

        binding.rowRefreshSubscriptions.setOnClickListener(v -> {
            Haptics.softSelection(v);
            refreshSubscriptions();
        });
        binding.rowTcppingActiveProfile.setOnClickListener(v -> {
            Haptics.softSelection(v);
            runTcpingForCurrentFilter();
        });
        binding.buttonProfileSelectAll.setOnClickListener(v -> {
            Haptics.softSelection(v);
            selectAllProfilesInCurrentFilter();
        });
        binding.buttonProfileShare.setOnClickListener(v -> {
            Haptics.softSelection(v);
            showShareFormatMenu(v);
        });
        binding.buttonScrollToTop.setOnClickListener(v -> {
            binding.scrollProfilesContent.smoothScrollTo(0, 0);
            Haptics.softSliderStep(v);
        });
        binding.bottomTabProfileSelection.inflateMenu(R.menu.menu_profile_selection_compact_actions, null);
        binding.bottomTabProfileSelection.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_profile_selection_reset_stats) {
                Haptics.softSelection(binding.bottomTabProfileSelection);
                resetSelectedProfileStats();
                return true;
            }
            if (itemId == R.id.menu_profile_selection_delete) {
                Haptics.softSelection(binding.bottomTabProfileSelection);
                deleteSelectedProfiles();
                return true;
            }
            return false;
        });
        binding.scrollProfilesContent.setOnScrollChangeListener(
            (NestedScrollView scrollView, int scrollX, int scrollY, int oldScrollX, int oldScrollY) -> {
                updateScrollToTopButton();
                maybeAppendMoreItems();
            }
        );
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUi();
        scheduleTrafficRefresh();
    }

    @Override
    public void onPause() {
        stopTrafficRefresh();
        clearSelectionMode();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        updateBottomNavigationSuppression(false);
        rowViews.clear();
        currentDisplayItems.clear();
        profileTrafficStats.clear();
        stopTrafficRefresh();
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        workExecutor.shutdownNow();
        renderExecutor.shutdownNow();
        tcpingExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refreshUi() {
        if (binding == null || !isAdded()) {
            return;
        }
        Context appContext = requireContext().getApplicationContext();
        String pendingFilterId = AppPrefs.consumePendingProfilesFilterId(appContext);
        if (!TextUtils.isEmpty(pendingFilterId)) {
            activeFilterId = pendingFilterId;
        }
        renderGeneration++;
        final int generation = renderGeneration;
        final String requestedFilterId = activeFilterId;
        renderExecutor.execute(() -> {
            List<XrayProfile> profiles = XrayStore.getProfiles(appContext);
            XrayProfile activeProfile = XrayStore.getActiveProfile(appContext);
            String activeProfileId = activeProfile != null ? activeProfile.id : "";
            LinkedHashMap<String, FilterSpec> computedFilters = buildFilters(profiles, appContext);
            String resolvedFilterId = computedFilters.containsKey(requestedFilterId) ? requestedFilterId : FILTER_ALL;
            List<XrayProfile> filteredProfiles = filterProfiles(profiles, resolvedFilterId);
            sortProfilesByStoredTcping(filteredProfiles, XrayStore.getProfilePingResultsMap(appContext));
            Map<String, List<XrayProfile>> groupedProfiles = groupProfilesForDisplay(
                filteredProfiles,
                resolvedFilterId,
                computedFilters,
                appContext
            );
            ArrayList<DisplayItem> displayItems = buildDisplayItems(groupedProfiles);
            String renderSignature = buildRenderSignature(resolvedFilterId, displayItems);
            ProfilesUiModel uiModel = new ProfilesUiModel(
                profiles,
                activeProfileId,
                computedFilters,
                resolvedFilterId,
                displayItems,
                renderSignature,
                XrayStore.getLastSubscriptionsRefreshAt(appContext),
                XrayStore.getLastSubscriptionsError(appContext)
            );
            postToUi(() -> applyUiModel(generation, uiModel));
        });
    }

    private void applyUiModel(int generation, ProfilesUiModel uiModel) {
        if (binding == null || !isAdded() || generation != renderGeneration || uiModel == null) {
            return;
        }
        boolean contentChanged =
            !TextUtils.equals(currentRenderSignature, uiModel.renderSignature) || rowViews.isEmpty();
        currentProfiles = new ArrayList<>(uiModel.profiles);
        currentActiveProfileId = uiModel.activeProfileId;
        activeFilterId = uiModel.activeFilterId;
        filterSpecs.clear();
        filterSpecs.putAll(uiModel.filters);
        profileTrafficStats.clear();
        profileTrafficStats.putAll(XrayStore.getProfileTrafficStatsMap(requireContext()));
        pruneSelection(uiModel.profiles);
        syncPingStates(uiModel.profiles);
        updateHeaderSummary(uiModel.lastRefreshAt, uiModel.lastError);
        renderFilterChips();
        if (contentChanged) {
            startPagedRender(uiModel);
        } else {
            updateEmptyState();
            updatePageLoader();
        }
        updateActionRows();
        updateSelectionUi();
        updateAllRowStates(currentActiveProfileId);
        refreshVisibleProfileTrafficStats(false);
        binding.scrollProfilesContent.post(() -> {
            updateScrollToTopButton();
            maybeAppendMoreItems();
        });
    }

    private void startPagedRender(ProfilesUiModel uiModel) {
        currentRenderSignature = uiModel.renderSignature;
        currentDisplayItems.clear();
        currentDisplayItems.addAll(uiModel.displayItems);
        renderedItemCount = 0;
        pageAppendRunning = false;
        currentAppendGroupContainer = null;
        binding.containerProfileGroups.removeAllViews();
        rowViews.clear();
        updateEmptyState();
        appendMoreItems();
    }

    private void appendMoreItems() {
        if (binding == null || !isAdded() || pageAppendRunning) {
            return;
        }
        if (renderedItemCount >= currentDisplayItems.size()) {
            updatePageLoader();
            return;
        }
        pageAppendRunning = true;
        updatePageLoader();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int appendedProfiles = 0;
        while (renderedItemCount < currentDisplayItems.size()) {
            DisplayItem item = currentDisplayItems.get(renderedItemCount);
            renderedItemCount++;
            if (item instanceof HeaderDisplayItem) {
                HeaderDisplayItem header = (HeaderDisplayItem) item;
                Separator separator = new Separator(requireContext());
                separator.setText(header.title);
                binding.containerProfileGroups.addView(separator);
                currentAppendGroupContainer = (RoundedLinearLayout) inflater.inflate(
                    R.layout.item_profile_group_section,
                    binding.containerProfileGroups,
                    false
                );
                binding.containerProfileGroups.addView(
                    currentAppendGroupContainer,
                    new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                );
                continue;
            }

            ProfileDisplayItem profileItem = (ProfileDisplayItem) item;
            if (currentAppendGroupContainer == null) {
                currentAppendGroupContainer = (RoundedLinearLayout) inflater.inflate(
                    R.layout.item_profile_group_section,
                    binding.containerProfileGroups,
                    false
                );
                binding.containerProfileGroups.addView(
                    currentAppendGroupContainer,
                    new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                );
            }
            ItemProfileEntryBinding rowBinding = ItemProfileEntryBinding.inflate(
                inflater,
                currentAppendGroupContainer,
                false
            );
            XrayProfile profile = profileItem.profile;
            rowBinding.textProfileTitle.setText(profileTitle(profile));
            rowBinding.textProfileSummary.setText(profileSummary(profile));
            rowBinding.viewProfileDivider.setVisibility(profileItem.showDivider ? View.VISIBLE : View.GONE);
            rowBinding.checkboxProfileSelected.setClickable(false);
            rowBinding.checkboxProfileSelected.setFocusable(false);
            rowBinding.rowProfileEntry.setOnClickListener(v -> onProfileClicked(profile));
            rowBinding.rowProfileEntry.setOnLongClickListener(v -> {
                Haptics.softSelection(v);
                beginSelection(profile.id);
                return true;
            });

            ProfileRowViews views = new ProfileRowViews(profile, rowBinding, pingStateKey(profile));
            rowViews.put(profile.id, views);
            currentAppendGroupContainer.addView(rowBinding.getRoot());
            appendedProfiles++;
            if (appendedProfiles >= PAGE_SIZE) {
                break;
            }
        }
        pageAppendRunning = false;
        updateAllRowStates(currentActiveProfileId);
        refreshVisibleProfileTrafficStats(false);
        updatePageLoader();
        binding.scrollProfilesContent.post(this::maybeAppendMoreItems);
    }

    private void maybeAppendMoreItems() {
        if (binding == null || !isAdded() || pageAppendRunning || selectionMode) {
            return;
        }
        if (renderedItemCount >= currentDisplayItems.size()) {
            updatePageLoader();
            return;
        }
        View content = binding.scrollProfilesContent.getChildAt(0);
        if (content == null) {
            return;
        }
        int distanceToBottom =
            content.getBottom() -
            (binding.scrollProfilesContent.getScrollY() + binding.scrollProfilesContent.getHeight());
        if (distanceToBottom <= dp(LOAD_MORE_THRESHOLD_DP)) {
            appendMoreItems();
        } else {
            updatePageLoader();
        }
    }

    private void updateHeaderSummary(long refreshedAt, String lastError) {
        if (TextUtils.isEmpty(lastError)) {
            if (refreshedAt > 0L) {
                binding.textProfilesHeaderSummary.setText(
                    getString(
                        R.string.xray_profiles_header_last_refresh,
                        DateFormat.getDateTimeInstance().format(refreshedAt)
                    )
                );
            } else {
                binding.textProfilesHeaderSummary.setText(R.string.xray_profiles_header_summary);
            }
            return;
        }
        binding.textProfilesHeaderSummary.setText(getString(R.string.xray_profiles_header_error, lastError));
    }

    private void updateEmptyState() {
        boolean empty = currentDisplayItems.isEmpty();
        binding.textProfilesEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.containerProfileGroups.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void updatePageLoader() {
        if (binding == null) {
            return;
        }
        boolean visible = pageAppendRunning || renderedItemCount < currentDisplayItems.size();
        binding.progressProfilesPageLoading.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private LinkedHashMap<String, FilterSpec> buildFilters(List<XrayProfile> profiles, Context context) {
        LinkedHashMap<String, FilterSpec> result = new LinkedHashMap<>();
        result.put(FILTER_ALL, new FilterSpec(FILTER_ALL, context.getString(R.string.xray_profiles_filter_all)));
        LinkedHashMap<String, FilterSpec> subscriptions = new LinkedHashMap<>();
        boolean hasManualProfiles = false;
        for (XrayProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            if (TextUtils.isEmpty(profile.subscriptionId)) {
                hasManualProfiles = true;
                continue;
            }
            String filterId = "sub:" + profile.subscriptionId;
            if (!subscriptions.containsKey(filterId)) {
                String title = TextUtils.isEmpty(profile.subscriptionTitle)
                    ? context.getString(R.string.xray_profiles_filter_no_subscription)
                    : profile.subscriptionTitle;
                subscriptions.put(filterId, new FilterSpec(filterId, title));
            }
        }
        result.putAll(subscriptions);
        if (hasManualProfiles) {
            result.put(
                FILTER_NO_SUBSCRIPTION,
                new FilterSpec(FILTER_NO_SUBSCRIPTION, context.getString(R.string.xray_profiles_filter_no_subscription))
            );
        }
        return result;
    }

    private void renderFilterChips() {
        binding.groupProfileFilters.removeAllViews();
        Context context = requireContext();
        for (FilterSpec filterSpec : filterSpecs.values()) {
            TextView pill = new TextView(context);
            boolean selected = TextUtils.equals(filterSpec.id, activeFilterId);
            pill.setText(filterSpec.title);
            pill.setGravity(Gravity.CENTER);
            pill.setMinHeight(dp(36));
            pill.setPadding(dp(16), dp(8), dp(16), dp(8));
            pill.setBackgroundResource(R.drawable.bg_profile_filter_chip);
            pill.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small);
            pill.setTextSize(15f);
            pill.setSelected(selected);
            ColorStateList textColors = AppCompatResources.getColorStateList(context, R.color.profile_filter_text);
            if (textColors != null) {
                pill.setTextColor(textColors);
            }
            pill.setOnClickListener(v -> onFilterSelected(filterSpec));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (binding.groupProfileFilters.getChildCount() > 0) {
                params.setMarginStart(dp(8));
            }
            binding.groupProfileFilters.addView(pill, params);
        }
    }

    private void onFilterSelected(FilterSpec filterSpec) {
        if (filterSpec == null || TextUtils.equals(activeFilterId, filterSpec.id)) {
            return;
        }
        cancelRunningTcping();
        clearSelectionMode();
        activeFilterId = filterSpec.id;
        refreshUi();
    }

    private void onProfileClicked(XrayProfile profile) {
        if (profile == null || binding == null || !isAdded()) {
            return;
        }
        if (selectionMode) {
            toggleSelection(profile.id);
            return;
        }
        Haptics.softSelection(binding.getRoot());
        if (TextUtils.equals(currentActiveProfileId, profile.id)) {
            updateAllRowStates(currentActiveProfileId);
            return;
        }
        currentActiveProfileId = profile.id;
        XrayStore.setActiveProfileId(requireContext(), profile.id);
        updateAllRowStates(currentActiveProfileId);
        refreshVisibleProfileTrafficStats(true);
        if (XrayStore.getBackendType(requireContext()) == BackendType.XRAY && ProxyTunnelService.isActive()) {
            try {
                ContextCompat.startForegroundService(
                    requireContext(),
                    ProxyTunnelService.createReconnectIntent(requireContext())
                );
            } catch (Exception ignored) {}
        }
        Toast.makeText(requireContext(), R.string.xray_profiles_selected, Toast.LENGTH_SHORT).show();
    }

    private void beginSelection(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return;
        }
        if (!selectionMode) {
            selectionMode = true;
            selectionBackCallback.setEnabled(true);
        }
        selectedProfileIds.add(profileId);
        updateSelectionUi();
        updateAllRowStates(currentActiveProfileId);
        updateScrollToTopButton();
    }

    private void toggleSelection(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return;
        }
        if (!selectionMode) {
            beginSelection(profileId);
            return;
        }
        if (selectedProfileIds.contains(profileId)) {
            selectedProfileIds.remove(profileId);
        } else {
            selectedProfileIds.add(profileId);
        }
        if (selectedProfileIds.isEmpty()) {
            clearSelectionMode();
            return;
        }
        updateSelectionUi();
        updateAllRowStates(currentActiveProfileId);
        updateScrollToTopButton();
    }

    private void clearSelectionMode() {
        if (!selectionMode && selectedProfileIds.isEmpty()) {
            updateBottomNavigationSuppression(false);
            if (binding != null) {
                updateScrollToTopButton();
            }
            return;
        }
        selectionMode = false;
        selectedProfileIds.clear();
        selectionBackCallback.setEnabled(false);
        updateSelectionUi();
        if (binding != null) {
            updateAllRowStates(currentActiveProfileId);
            updateScrollToTopButton();
        }
    }

    private void updateSelectionUi() {
        if (binding == null) {
            return;
        }
        boolean visible = selectionMode && !selectedProfileIds.isEmpty();
        binding.layoutProfileSelectionActions.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.textProfileSelectionCount.setText(
            getString(R.string.xray_profiles_selected_count, selectedProfileIds.size())
        );
        updateSelectionToggleButton();
        updateBottomNavigationSuppression(visible);
    }

    private void updateSelectionToggleButton() {
        if (binding == null) {
            return;
        }
        binding.buttonProfileSelectAll.setText(
            areAllProfilesInCurrentFilterSelected()
                ? R.string.xray_profiles_deselect_all_action
                : R.string.xray_profiles_select_all_action
        );
    }

    private void updateBottomNavigationSuppression(boolean suppressed) {
        if (!(getActivity() instanceof MainActivity)) {
            return;
        }
        ((MainActivity) getActivity()).setBottomNavigationSuppressed(suppressed);
    }

    private void updateAllRowStates(@Nullable String activeProfileId) {
        for (ProfileRowViews views : rowViews.values()) {
            applyRowState(views, TextUtils.equals(activeProfileId, views.profile.id));
        }
    }

    private void applyRowState(ProfileRowViews views, boolean active) {
        if (views == null) {
            return;
        }
        boolean selected = selectedProfileIds.contains(views.profile.id);
        views.root.setActivated(selected);
        views.checkbox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        views.checkbox.setChecked(selected);
        views.activeIcon.setVisibility(selectionMode ? View.GONE : active ? View.VISIBLE : View.INVISIBLE);
        applyTrafficState(views, profileTrafficStats.get(views.profile.id));
        applyPingState(views, pingStates.get(views.pingKey));
    }

    private void applyTrafficState(ProfileRowViews views, @Nullable XrayStore.ProfileTrafficStats trafficStats) {
        if (views == null) {
            return;
        }
        XrayStore.ProfileTrafficStats stats = trafficStats != null ? trafficStats : XrayStore.ProfileTrafficStats.ZERO;
        boolean visible = stats.rxBytes > 0L || stats.txBytes > 0L;
        views.trafficRow.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            return;
        }
        Context context = views.root.getContext();
        views.rxText.setText(UiFormatter.formatBytes(context, stats.rxBytes));
        views.txText.setText(UiFormatter.formatBytes(context, stats.txBytes));
    }

    private void applyPingState(ProfileRowViews views, @Nullable PingState pingState) {
        if (views == null) {
            return;
        }
        if (pingState == null || pingState.state == PingDisplayState.NONE) {
            views.progress.setVisibility(View.GONE);
            views.pingBadge.setVisibility(View.GONE);
            return;
        }
        if (pingState.state == PingDisplayState.LOADING) {
            views.progress.setVisibility(View.VISIBLE);
            views.pingBadge.setVisibility(View.GONE);
            return;
        }

        views.progress.setVisibility(View.GONE);
        views.pingBadge.setVisibility(View.VISIBLE);
        if (pingState.state == PingDisplayState.FAILED) {
            views.pingBadge.setText(R.string.xray_profiles_ping_error_badge);
            views.pingBadge.setBackgroundResource(R.drawable.bg_profile_ping_bad);
            return;
        }

        views.pingBadge.setText(pingState.latencyMs + " ms");
        if (pingState.latencyMs <= PING_GOOD_THRESHOLD_MS) {
            views.pingBadge.setBackgroundResource(R.drawable.bg_profile_ping_good);
        } else if (pingState.latencyMs <= PING_WARNING_THRESHOLD_MS) {
            views.pingBadge.setBackgroundResource(R.drawable.bg_profile_ping_warning);
        } else {
            views.pingBadge.setBackgroundResource(R.drawable.bg_profile_ping_bad);
        }
    }

    private void updateActionRows() {
        if (binding == null) {
            return;
        }
        binding.rowRefreshSubscriptions.setEnabled(!refreshingSubscriptions && !tcpingRunning);
        binding.rowRefreshSubscriptions.setSummary(
            refreshingSubscriptions
                ? getString(R.string.xray_profiles_refresh_subscriptions_running)
                : getString(R.string.xray_profiles_refresh_subscriptions_summary)
        );
        binding.progressRefreshSubscriptions.setVisibility(refreshingSubscriptions ? View.VISIBLE : View.GONE);

        binding.rowTcppingActiveProfile.setEnabled(!tcpingRunning && !refreshingSubscriptions);
        binding.rowTcppingActiveProfile.setSummary(
            tcpingRunning
                ? getString(R.string.xray_profiles_tcping_running)
                : getString(R.string.xray_profiles_tcping_summary_filter, currentFilterTitle())
        );
    }

    private void refreshSubscriptions() {
        if (refreshingSubscriptions || tcpingRunning || !isAdded()) {
            return;
        }
        refreshingSubscriptions = true;
        updateActionRows();
        Context appContext = requireContext().getApplicationContext();
        workExecutor.execute(() -> {
            String resolvedToastMessage;
            try {
                XraySubscriptionUpdater.RefreshResult result = XraySubscriptionUpdater.refreshAll(appContext);
                resolvedToastMessage = TextUtils.isEmpty(result.error)
                    ? appContext.getString(R.string.xray_profiles_refresh_subscriptions_done, result.profiles.size())
                    : appContext.getString(R.string.xray_subscriptions_refresh_partial, result.error);
            } catch (Exception error) {
                resolvedToastMessage = appContext.getString(
                    R.string.xray_subscriptions_refresh_failed,
                    error.getMessage()
                );
            }
            final String toastMessage = resolvedToastMessage;
            postToUi(() -> {
                refreshingSubscriptions = false;
                refreshUi();
                Toast.makeText(requireContext(), toastMessage, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void runTcpingForCurrentFilter() {
        if (tcpingRunning || refreshingSubscriptions || !isAdded()) {
            return;
        }
        List<XrayProfile> targets = pingTargets(filterProfiles(currentProfiles, activeFilterId));
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), R.string.xray_profiles_tcping_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

        tcpingRunning = true;
        tcpingGeneration++;
        final int generation = tcpingGeneration;
        final AtomicInteger remaining = new AtomicInteger(targets.size());
        for (XrayProfile profile : targets) {
            pingStates.put(pingStateKey(profile), PingState.loading());
        }
        updateActionRows();
        updateAllRowStates(currentActiveProfileId);

        for (XrayProfile profile : targets) {
            tcpingExecutor.execute(() -> {
                PingState result = generation == tcpingGeneration ? tcping(profile) : PingState.none();
                postToUi(() -> {
                    if (generation != tcpingGeneration || binding == null) {
                        return;
                    }
                    pingStates.put(pingStateKey(profile), result);
                    XrayStore.putProfilePingResult(
                        requireContext(),
                        pingStateKey(profile),
                        result.state == PingDisplayState.SUCCESS,
                        result.latencyMs
                    );
                    ProfileRowViews row = rowViews.get(profile.id);
                    if (row != null) {
                        applyRowState(row, TextUtils.equals(currentActiveProfileId, profile.id));
                    }
                });
                if (remaining.decrementAndGet() == 0) {
                    postToUi(() -> {
                        if (generation != tcpingGeneration || binding == null) {
                            return;
                        }
                        tcpingRunning = false;
                        updateActionRows();
                        refreshUi();
                    });
                }
            });
        }
    }

    private PingState tcping(XrayProfile profile) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(profile.address, profile.port), TCPING_TIMEOUT_MS);
            int elapsedMs = (int) ((System.nanoTime() - start) / 1_000_000L);
            return PingState.success(Math.max(elapsedMs, 1));
        } catch (Exception ignored) {
            return PingState.failed();
        }
    }

    private void cancelRunningTcping() {
        if (!tcpingRunning) {
            return;
        }
        tcpingGeneration++;
        tcpingRunning = false;
        ArrayList<String> loadingKeys = new ArrayList<>();
        for (Map.Entry<String, PingState> entry : pingStates.entrySet()) {
            if (entry.getValue() != null && entry.getValue().state == PingDisplayState.LOADING) {
                loadingKeys.add(entry.getKey());
            }
        }
        for (String key : loadingKeys) {
            pingStates.remove(key);
        }
        if (binding != null && isAdded()) {
            updateActionRows();
            updateAllRowStates(currentActiveProfileId);
        }
    }

    private void showShareFormatMenu(View anchor) {
        if (!isAdded() || selectedProfileIds.isEmpty()) {
            return;
        }
        PopupMenu popupMenu = new PopupMenu(requireContext(), anchor);
        popupMenu.inflate(R.menu.menu_profile_share_formats);
        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_profile_share_format_wingsv || itemId == R.id.menu_profile_share_format_vless) {
                shareSelectedProfiles(itemId);
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void shareSelectedProfiles(int formatMenuId) {
        if (!isAdded() || selectedProfileIds.isEmpty()) {
            return;
        }
        Context context = requireContext();
        List<XrayProfile> selectedProfiles = selectedProfiles();
        if (selectedProfiles.isEmpty()) {
            return;
        }
        try {
            String sharedText;
            if (formatMenuId == R.id.menu_profile_share_format_vless) {
                sharedText = buildVlessShareText(selectedProfiles);
            } else {
                String activeProfileId = currentActiveProfileId;
                if (TextUtils.isEmpty(activeProfileId) || !selectedProfileIds.contains(activeProfileId)) {
                    activeProfileId = selectedProfiles.get(0).id;
                }
                sharedText = WingsImportParser.buildXrayProfilesLink(context, selectedProfiles, activeProfileId);
            }
            Intent sendIntent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, sharedText);
            Intent chooserIntent = Intent.createChooser(sendIntent, getString(R.string.xray_profiles_share_chooser));
            if (chooserIntent.resolveActivity(context.getPackageManager()) == null) {
                throw new IllegalStateException("No share targets");
            }
            startActivity(chooserIntent);
            clearSelectionMode();
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.xray_profiles_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String buildVlessShareText(List<XrayProfile> selectedProfiles) {
        LinkedHashMap<String, String> deduped = new LinkedHashMap<>();
        for (XrayProfile profile : selectedProfiles) {
            if (profile == null || TextUtils.isEmpty(profile.rawLink)) {
                continue;
            }
            deduped.put(profile.stableDedupKey(), profile.rawLink.trim());
        }
        if (deduped.isEmpty()) {
            throw new IllegalArgumentException("No VLESS profiles to export");
        }
        StringBuilder builder = new StringBuilder();
        for (String rawLink : deduped.values()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(rawLink);
        }
        return builder.toString();
    }

    private void selectAllProfilesInCurrentFilter() {
        List<XrayProfile> filteredProfiles = filterProfiles(currentProfiles, activeFilterId);
        if (filteredProfiles.isEmpty()) {
            return;
        }
        if (areAllProfilesInCurrentFilterSelected()) {
            for (XrayProfile profile : filteredProfiles) {
                if (profile != null) {
                    selectedProfileIds.remove(profile.id);
                }
            }
            if (selectedProfileIds.isEmpty()) {
                clearSelectionMode();
            } else {
                updateSelectionUi();
                updateAllRowStates(currentActiveProfileId);
                updateScrollToTopButton();
            }
            return;
        }
        if (!selectionMode) {
            selectionMode = true;
            selectionBackCallback.setEnabled(true);
        }
        for (XrayProfile profile : filteredProfiles) {
            if (profile == null || TextUtils.isEmpty(profile.id)) {
                continue;
            }
            selectedProfileIds.add(profile.id);
        }
        updateSelectionUi();
        updateAllRowStates(currentActiveProfileId);
        updateScrollToTopButton();
    }

    private boolean areAllProfilesInCurrentFilterSelected() {
        List<XrayProfile> filteredProfiles = filterProfiles(currentProfiles, activeFilterId);
        if (filteredProfiles.isEmpty()) {
            return false;
        }
        for (XrayProfile profile : filteredProfiles) {
            if (profile == null || TextUtils.isEmpty(profile.id)) {
                continue;
            }
            if (!selectedProfileIds.contains(profile.id)) {
                return false;
            }
        }
        return true;
    }

    private void resetSelectedProfileStats() {
        if (!isAdded() || selectedProfileIds.isEmpty()) {
            return;
        }
        ArrayList<String> profileIds = new ArrayList<>(selectedProfileIds);
        XrayStore.resetProfileTrafficStats(requireContext(), profileIds);
        for (String profileId : profileIds) {
            profileTrafficStats.remove(profileId);
        }
        updateAllRowStates(currentActiveProfileId);
        clearSelectionMode();
        Toast.makeText(
            requireContext(),
            getString(R.string.xray_profiles_reset_stats_done, profileIds.size()),
            Toast.LENGTH_SHORT
        ).show();
    }

    private void deleteSelectedProfiles() {
        if (!isAdded() || selectedProfileIds.isEmpty()) {
            return;
        }
        Context context = requireContext();
        List<XrayProfile> profiles = new ArrayList<>(currentProfiles);
        int removed = 0;
        for (int index = profiles.size() - 1; index >= 0; index--) {
            XrayProfile profile = profiles.get(index);
            if (profile != null && selectedProfileIds.contains(profile.id)) {
                pingStates.remove(pingStateKey(profile));
                profiles.remove(index);
                removed++;
            }
        }
        XrayStore.setProfiles(context, profiles);
        XrayStore.removeProfilePingResults(context, collectSelectedProfilePingKeys());
        if (selectedProfileIds.contains(currentActiveProfileId)) {
            currentActiveProfileId = profiles.isEmpty() ? "" : profiles.get(0).id;
            XrayStore.setActiveProfileId(context, currentActiveProfileId);
        }
        clearSelectionMode();
        refreshUi();
        Toast.makeText(context, getString(R.string.xray_profiles_delete_done, removed), Toast.LENGTH_SHORT).show();
    }

    private void pruneSelection(List<XrayProfile> profiles) {
        LinkedHashSet<String> existingIds = new LinkedHashSet<>();
        for (XrayProfile profile : profiles) {
            if (profile != null) {
                existingIds.add(profile.id);
            }
        }
        selectedProfileIds.retainAll(existingIds);
        if (selectedProfileIds.isEmpty()) {
            selectionMode = false;
            selectionBackCallback.setEnabled(false);
        }
    }

    private void syncPingStates(List<XrayProfile> profiles) {
        LinkedHashSet<String> activeKeys = new LinkedHashSet<>();
        for (XrayProfile profile : profiles) {
            String profilePingKey = pingStateKey(profile);
            if (!TextUtils.isEmpty(profilePingKey)) {
                activeKeys.add(profilePingKey);
            }
        }

        LinkedHashMap<String, PingState> merged = new LinkedHashMap<>();
        Map<String, XrayStore.ProfilePingResult> stored = XrayStore.getProfilePingResultsMap(requireContext());
        for (String activeKey : activeKeys) {
            XrayStore.ProfilePingResult storedResult = stored.get(activeKey);
            if (storedResult == null) {
                continue;
            }
            merged.put(
                activeKey,
                storedResult.success ? PingState.success(storedResult.latencyMs) : PingState.failed()
            );
        }

        for (Map.Entry<String, PingState> entry : pingStates.entrySet()) {
            if (!activeKeys.contains(entry.getKey())) {
                continue;
            }
            PingState value = entry.getValue();
            if (value != null && value.state == PingDisplayState.LOADING) {
                merged.put(entry.getKey(), value);
            }
        }

        pingStates.clear();
        pingStates.putAll(merged);
    }

    private List<XrayProfile> selectedProfiles() {
        ArrayList<XrayProfile> result = new ArrayList<>();
        for (XrayProfile profile : currentProfiles) {
            if (profile != null && selectedProfileIds.contains(profile.id)) {
                result.add(profile);
            }
        }
        return result;
    }

    private List<XrayProfile> filterProfiles(List<XrayProfile> profiles, String filterId) {
        if (TextUtils.equals(filterId, FILTER_ALL)) {
            return new ArrayList<>(profiles);
        }
        ArrayList<XrayProfile> filtered = new ArrayList<>();
        String subscriptionId = filterId.startsWith("sub:") ? filterId.substring("sub:".length()) : "";
        for (XrayProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            if (TextUtils.equals(filterId, FILTER_NO_SUBSCRIPTION)) {
                if (TextUtils.isEmpty(profile.subscriptionId)) {
                    filtered.add(profile);
                }
                continue;
            }
            if (TextUtils.equals(profile.subscriptionId, subscriptionId)) {
                filtered.add(profile);
            }
        }
        return filtered;
    }

    private void sortProfilesByStoredTcping(
        List<XrayProfile> profiles,
        Map<String, XrayStore.ProfilePingResult> pingResults
    ) {
        if (profiles == null || profiles.size() < 2 || pingResults == null || pingResults.isEmpty()) {
            return;
        }
        profiles.sort(
            Comparator.comparingInt((XrayProfile profile) -> pingSortBucket(pingResults.get(pingStateKey(profile))))
                .thenComparingInt(profile -> pingSortLatency(pingResults.get(pingStateKey(profile))))
                .thenComparing(this::profileSortTitle, String.CASE_INSENSITIVE_ORDER)
        );
    }

    private int pingSortBucket(@Nullable XrayStore.ProfilePingResult result) {
        if (result == null) {
            return 2;
        }
        return result.success ? 0 : 1;
    }

    private int pingSortLatency(@Nullable XrayStore.ProfilePingResult result) {
        if (result == null || !result.success || result.latencyMs <= 0) {
            return Integer.MAX_VALUE;
        }
        return result.latencyMs;
    }

    private String profileSortTitle(XrayProfile profile) {
        if (profile == null) {
            return "";
        }
        if (!TextUtils.isEmpty(profile.title)) {
            return profile.title;
        }
        if (!TextUtils.isEmpty(profile.address) && profile.port > 0) {
            return profile.address + ":" + profile.port;
        }
        return "";
    }

    private Map<String, List<XrayProfile>> groupProfilesForDisplay(
        List<XrayProfile> profiles,
        String filterId,
        Map<String, FilterSpec> filters,
        Context context
    ) {
        if (TextUtils.equals(filterId, FILTER_ALL)) {
            return XraySubscriptionUpdater.groupProfilesBySubscription(profiles);
        }
        LinkedHashMap<String, List<XrayProfile>> singleGroup = new LinkedHashMap<>();
        singleGroup.put(filterTitle(filterId, filters, context), profiles);
        return singleGroup;
    }

    private ArrayList<DisplayItem> buildDisplayItems(Map<String, List<XrayProfile>> groupedProfiles) {
        ArrayList<DisplayItem> items = new ArrayList<>();
        if (groupedProfiles == null) {
            return items;
        }
        for (Map.Entry<String, List<XrayProfile>> entry : groupedProfiles.entrySet()) {
            List<XrayProfile> groupProfiles = entry.getValue();
            if (groupProfiles == null || groupProfiles.isEmpty()) {
                continue;
            }
            items.add(new HeaderDisplayItem(entry.getKey()));
            for (int index = 0; index < groupProfiles.size(); index++) {
                items.add(new ProfileDisplayItem(groupProfiles.get(index), index < groupProfiles.size() - 1));
            }
        }
        return items;
    }

    private String buildRenderSignature(String filterId, List<DisplayItem> displayItems) {
        StringBuilder builder = new StringBuilder(filterId).append('|');
        for (DisplayItem item : displayItems) {
            if (item instanceof HeaderDisplayItem) {
                builder.append("h:").append(((HeaderDisplayItem) item).title).append('|');
            } else if (item instanceof ProfileDisplayItem) {
                XrayProfile profile = ((ProfileDisplayItem) item).profile;
                builder.append("p:").append(pingStateKey(profile)).append('|');
            }
        }
        return builder.toString();
    }

    private List<XrayProfile> pingTargets(List<XrayProfile> profiles) {
        ArrayList<XrayProfile> result = new ArrayList<>();
        for (XrayProfile profile : profiles) {
            if (profile == null || TextUtils.isEmpty(profile.address) || profile.port <= 0) {
                continue;
            }
            result.add(profile);
        }
        return result;
    }

    private String currentFilterTitle() {
        return filterTitle(activeFilterId, filterSpecs, requireContext());
    }

    private String filterTitle(String filterId, Map<String, FilterSpec> filters, Context context) {
        FilterSpec activeFilter = filters.get(filterId);
        if (activeFilter == null || TextUtils.isEmpty(activeFilter.title)) {
            return context.getString(R.string.xray_profiles_filter_all);
        }
        return activeFilter.title;
    }

    private String pingStateKey(XrayProfile profile) {
        return XrayStore.getProfilePingKey(profile);
    }

    private List<String> collectSelectedProfilePingKeys() {
        ArrayList<String> result = new ArrayList<>();
        for (XrayProfile profile : currentProfiles) {
            if (profile == null || !selectedProfileIds.contains(profile.id)) {
                continue;
            }
            String key = pingStateKey(profile);
            if (!TextUtils.isEmpty(key)) {
                result.add(key);
            }
        }
        return result;
    }

    private String profileTitle(XrayProfile profile) {
        if (!TextUtils.isEmpty(profile.title)) {
            return profile.title;
        }
        if (!TextUtils.isEmpty(profile.address) && profile.port > 0) {
            return profile.address + ":" + profile.port;
        }
        return getString(R.string.xray_profiles_untitled);
    }

    private String profileSummary(XrayProfile profile) {
        if (!TextUtils.isEmpty(profile.address) && profile.port > 0) {
            return profile.address + ":" + profile.port;
        }
        if (!TextUtils.isEmpty(profile.rawLink)) {
            return profile.rawLink;
        }
        return getString(R.string.xray_profiles_unavailable_target);
    }

    private void updateScrollToTopButton() {
        if (binding == null) {
            return;
        }
        boolean shouldShow = !selectionMode && binding.scrollProfilesContent.canScrollVertically(-1);
        binding.buttonScrollToTop.animate().cancel();
        if (shouldShow) {
            binding.buttonScrollToTop.setVisibility(View.VISIBLE);
            binding.buttonScrollToTop.setAlpha(1f);
            binding.buttonScrollToTop.setScaleX(1f);
            binding.buttonScrollToTop.setScaleY(1f);
        } else {
            binding.buttonScrollToTop.setVisibility(View.GONE);
        }
    }

    private void scheduleTrafficRefresh() {
        if (binding == null) {
            return;
        }
        binding.getRoot().removeCallbacks(trafficRefreshRunnable);
        binding.getRoot().postDelayed(trafficRefreshRunnable, TRAFFIC_REFRESH_INTERVAL_MS);
    }

    private void stopTrafficRefresh() {
        if (binding != null) {
            binding.getRoot().removeCallbacks(trafficRefreshRunnable);
        }
    }

    private void refreshVisibleProfileTrafficStats(boolean activeOnly) {
        if (binding == null || !isAdded()) {
            return;
        }
        Context context = requireContext();
        if (activeOnly) {
            if (TextUtils.isEmpty(currentActiveProfileId)) {
                return;
            }
            XrayStore.ProfileTrafficStats stats = XrayStore.getProfileTrafficStats(context, currentActiveProfileId);
            profileTrafficStats.put(currentActiveProfileId, stats);
            ProfileRowViews row = rowViews.get(currentActiveProfileId);
            if (row != null) {
                applyTrafficState(row, stats);
            }
            return;
        }
        profileTrafficStats.clear();
        profileTrafficStats.putAll(XrayStore.getProfileTrafficStatsMap(context));
        for (ProfileRowViews row : rowViews.values()) {
            applyTrafficState(row, profileTrafficStats.get(row.profile.id));
        }
    }

    private void postToUi(Runnable action) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (!isAdded() || binding == null) {
                return;
            }
            action.run();
        });
    }

    private int dp(int value) {
        return Math.round(value * requireContext().getResources().getDisplayMetrics().density);
    }

    private static final class FilterSpec {

        final String id;
        final String title;

        FilterSpec(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private interface DisplayItem {}

    private static final class HeaderDisplayItem implements DisplayItem {

        final String title;

        HeaderDisplayItem(String title) {
            this.title = title;
        }
    }

    private static final class ProfileDisplayItem implements DisplayItem {

        final XrayProfile profile;
        final boolean showDivider;

        ProfileDisplayItem(XrayProfile profile, boolean showDivider) {
            this.profile = profile;
            this.showDivider = showDivider;
        }
    }

    private static final class ProfileRowViews {

        final XrayProfile profile;
        final String pingKey;
        final FrameLayout root;
        final AppCompatCheckBox checkbox;
        final View activeIcon;
        final ProgressBar progress;
        final TextView pingBadge;
        final View trafficRow;
        final TextView rxText;
        final TextView txText;

        ProfileRowViews(XrayProfile profile, ItemProfileEntryBinding binding, String pingKey) {
            this.profile = profile;
            this.pingKey = pingKey;
            this.root = binding.rowProfileEntry;
            this.checkbox = binding.checkboxProfileSelected;
            this.activeIcon = binding.imageProfileActive;
            this.progress = binding.progressProfilePing;
            this.pingBadge = binding.textProfilePing;
            this.trafficRow = binding.layoutProfileTraffic;
            this.rxText = binding.textProfileRx;
            this.txText = binding.textProfileTx;
        }
    }

    private enum PingDisplayState {
        NONE,
        LOADING,
        SUCCESS,
        FAILED,
    }

    private static final class PingState {

        final PingDisplayState state;
        final int latencyMs;

        private PingState(PingDisplayState state, int latencyMs) {
            this.state = state;
            this.latencyMs = latencyMs;
        }

        static PingState none() {
            return new PingState(PingDisplayState.NONE, 0);
        }

        static PingState loading() {
            return new PingState(PingDisplayState.LOADING, 0);
        }

        static PingState success(int latencyMs) {
            return new PingState(PingDisplayState.SUCCESS, latencyMs);
        }

        static PingState failed() {
            return new PingState(PingDisplayState.FAILED, 0);
        }
    }

    private static final class ProfilesUiModel {

        final List<XrayProfile> profiles;
        final String activeProfileId;
        final LinkedHashMap<String, FilterSpec> filters;
        final String activeFilterId;
        final List<DisplayItem> displayItems;
        final String renderSignature;
        final long lastRefreshAt;
        final String lastError;

        ProfilesUiModel(
            List<XrayProfile> profiles,
            String activeProfileId,
            LinkedHashMap<String, FilterSpec> filters,
            String activeFilterId,
            List<DisplayItem> displayItems,
            String renderSignature,
            long lastRefreshAt,
            String lastError
        ) {
            this.profiles = profiles;
            this.activeProfileId = activeProfileId;
            this.filters = filters;
            this.activeFilterId = activeFilterId;
            this.displayItems = displayItems;
            this.renderSignature = renderSignature;
            this.lastRefreshAt = lastRefreshAt;
            this.lastError = lastError;
        }
    }
}
