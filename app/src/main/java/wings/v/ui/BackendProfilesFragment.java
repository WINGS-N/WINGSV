package wings.v.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SeslArrayAdapter;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;
import dev.oneuiproject.oneui.widget.CardItemView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import wings.v.ExternalActions;
import wings.v.R;
import wings.v.core.AmneziaProfile;
import wings.v.core.AmneziaProfileStore;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.TunnelMode;
import wings.v.core.UiFormatter;
import wings.v.core.VkTurnProfile;
import wings.v.core.VkTurnProfileStore;
import wings.v.core.WingsImportParser;
import wings.v.core.WireGuardProfile;
import wings.v.core.WireGuardProfileEditorCodec;
import wings.v.core.WireGuardProfileStore;
import wings.v.core.XrayStore;
import wings.v.core.XraySubscription;
import wings.v.databinding.FragmentBackendProfilesBinding;
import wings.v.databinding.ItemBackendProfileEntryBinding;
import wings.v.service.ProxyTunnelService;

/**
 * Profiles screen with a backend selector. The dropdown reuses the settings
 * screen's entries/values and switches through ExternalActions.setBackend, so the
 * two screens never disagree about the active backend.
 *
 * Xray has no list of its own here: ProfilesFragment is embedded as is. WireGuard,
 * AmneziaWG and VK TURN get a simple list over their own profile store
 */
@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.ExcessiveImports",
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
        "PMD.CommentDefaultAccessModifier",
        "PMD.AtLeastOneConstructor",
        "PMD.CouplingBetweenObjects",
        "PMD.AvoidLiteralsInIfCondition",
        "PMD.AvoidDuplicateLiterals",
        "PMD.ConfusingTernary",
        // Per-backend helpers keep backendType / profile for symmetry across the
        // WG / AWG / VK TURN dispatch even where a branch ignores it
        "PMD.UnusedFormalParameter",
    }
)
public class BackendProfilesFragment extends Fragment {

    private static final String XRAY_CHILD_TAG = "embedded_xray_profiles";
    private static final String FILTER_ALL = "__all__";
    private static final String FILTER_FAVORITES = "__favorites__";
    private static final String FILTER_NO_SUBSCRIPTION = "__no_subscription__";
    private static final String FILTER_SUBSCRIPTION_PREFIX = "sub:";
    private static final int GROUP_QUOTA_PROGRESS_MAX = 1000;

    private FragmentBackendProfilesBinding binding;

    // The per-backend settings screen doubles as a profile editor, so on return the
    // flat-key edits have to be folded back into the active profile
    @Nullable
    private BackendType pendingUiEditBackend;

    private String activeBackendFilterId = FILTER_ALL;

    // Subscription refresh runs off the UI thread and posts back through the
    // main-looper handler, guarded by binding / isAdded
    private final java.util.concurrent.ExecutorService subscriptionExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean refreshingSubscriptions;

    // Multi-select for the simple list, same gestures as the Xray one: long-press
    // -> Select, tap toggles, back exits. rowBindingsById holds the live row views
    // so checkboxes repaint without a full re-render, otherwise every tap rebuilds
    // the whole list. currentSelection* is the snapshot the action bar works on
    private final java.util.LinkedHashSet<String> selectedBackendProfileIds = new java.util.LinkedHashSet<>();
    private boolean backendSelectionMode;
    private final java.util.Map<String, ItemBackendProfileEntryBinding> rowBindingsById =
        new java.util.LinkedHashMap<>();
    private final List<SimpleProfile> currentSelectionProfiles = new ArrayList<>();

    @Nullable
    private BackendType currentSelectionBackend;

    @Nullable
    private androidx.activity.OnBackPressedCallback selectionBackCallback;

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentBackendProfilesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.rowBackendSelector.setTitle(getString(R.string.backend_profiles_selector_title));
        bindBackendDropdown(
            binding.rowBackendSelector,
            binding.spinnerBackendTop,
            R.array.backend_top_entries,
            R.array.backend_top_values,
            () -> XrayStore.getBackendType(requireContext()).topLevelGroup(),
            this::onTopLevelChosen
        );
        binding.rowBackendSubSelector.setTitle(getString(R.string.sub_backend_title));
        bindBackendDropdown(
            binding.rowBackendSubSelector,
            binding.spinnerBackendSub,
            R.array.tunnel_mode_entries,
            R.array.tunnel_mode_values,
            () -> AppPrefs.getVkTurnTunnelMode(requireContext()).prefValue,
            this::onSubBackendChosen
        );
        binding.rowBackendAddProfile.setTitle(getString(R.string.backend_profiles_add_title));
        binding.rowBackendAddProfile.setSummary(getString(R.string.backend_profiles_add_summary));
        binding.rowBackendAddProfile.setOnClickListener(v -> {
            Haptics.softSelection(v);
            importFromClipboard();
        });
        binding.rowOpenSubscriptions.setTitle(getString(R.string.xray_profiles_open_subscriptions_title));
        binding.rowOpenSubscriptions.setSummary(getString(R.string.xray_profiles_open_subscriptions_summary));
        binding.rowOpenSubscriptions.setOnClickListener(v -> {
            Haptics.softSelection(v);
            startActivity(wings.v.SubscriptionsActivity.createIntent(requireContext()));
        });
        binding.rowRefreshSubscriptions.setTitle(getString(R.string.xray_profiles_refresh_subscriptions_title));
        binding.rowRefreshSubscriptions.setOnClickListener(v -> {
            Haptics.softSelection(v);
            refreshSubscriptions();
        });
        binding.buttonBackendProfileSelectAll.setOnClickListener(v -> {
            Haptics.softSelection(v);
            selectAllBackendProfiles();
        });
        binding.buttonBackendProfileShare.setOnClickListener(v -> {
            Haptics.softSelection(v);
            shareSelectedBackendProfiles();
        });
        binding.bottomTabBackendProfileSelection.inflateMenu(R.menu.menu_profile_selection_compact_actions, null);
        binding.bottomTabBackendProfileSelection.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_profile_selection_reset_stats) {
                Haptics.softSelection(binding.bottomTabBackendProfileSelection);
                resetSelectedBackendStats();
                return true;
            }
            if (itemId == R.id.menu_profile_selection_delete) {
                Haptics.softSelection(binding.bottomTabBackendProfileSelection);
                confirmDeleteSelectedBackend();
                return true;
            }
            return false;
        });
        selectionBackCallback = new androidx.activity.OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                clearBackendSelectionMode();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), selectionBackCallback);
    }

    // The relay reports fresh traffic usage; repaint the quota bars while this
    // screen is up
    private final android.content.BroadcastReceiver trafficUsageReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, android.content.Intent intent) {
            refreshUi();
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        if (pendingUiEditBackend != null && isAdded()) {
            updateActiveFromFlatPrefs(requireContext(), pendingUiEditBackend);
            pendingUiEditBackend = null;
        }
        ContextCompat.registerReceiver(
            requireContext(),
            trafficUsageReceiver,
            new android.content.IntentFilter(ProxyTunnelService.ACTION_TRAFFIC_USAGE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        );
        refreshUi();
    }

    @Override
    public void onPause() {
        try {
            requireContext().unregisterReceiver(trafficUsageReceiver);
        } catch (IllegalArgumentException ignored) {
            // Was not registered, nothing to do
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        subscriptionExecutor.shutdownNow();
        super.onDestroy();
    }

    // The embedded ProfilesFragment calls this from its own selector so the host
    // swaps the backend view
    public void onEmbeddedBackendSwitched() {
        refreshUi();
    }

    private void refreshUi() {
        if (binding == null || !isAdded()) {
            return;
        }
        Context context = requireContext();
        BackendType backendType = XrayStore.getBackendType(context);
        binding.rowBackendSelector.setSummary(backendSelectorSummary(backendType));
        updateSubscriptionRows(context);
        boolean vkTurn = "vk_turn".equals(backendType.topLevelGroup());
        binding.rowBackendSubSelector.setVisibility(vkTurn ? View.VISIBLE : View.GONE);
        if (vkTurn) {
            binding.rowBackendSubSelector.setSummary(subBackendSummary(backendType));
        }
        if (backendType.usesXrayCore()) {
            showXrayList();
        } else {
            showSimpleList(backendType);
        }
    }

    private void updateSubscriptionRows(Context context) {
        if (binding == null) {
            return;
        }
        binding.rowRefreshSubscriptions.setSummary(refreshSubscriptionsSummary(context));
        renderSubscriptionQuota(context);
    }

    // Same summary the Xray list shows
    private String refreshSubscriptionsSummary(Context context) {
        if (refreshingSubscriptions) {
            return getString(R.string.xray_profiles_refresh_subscriptions_running);
        }
        String lastError = XrayStore.getLastSubscriptionsError(context);
        if (!TextUtils.isEmpty(lastError)) {
            return getString(R.string.xray_profiles_header_error, lastError);
        }
        long lastRefreshAt = XrayStore.getLastSubscriptionsRefreshAt(context);
        if (lastRefreshAt > 0L) {
            return getString(
                R.string.xray_profiles_header_last_refresh,
                java.text.DateFormat.getDateTimeInstance().format(lastRefreshAt)
            );
        }
        return getString(R.string.xray_profiles_refresh_subscriptions_summary);
    }

    // Quota bar for the selected subscription pill, same logic and look as the Xray
    // group header: tinted by remaining ratio (error <=10%, warning <=40%, else
    // success)
    private void renderSubscriptionQuota(Context context) {
        if (binding == null) {
            return;
        }
        SubscriptionQuotaState quotaState = selectedSubscriptionQuotaState(context);
        if (quotaState == null) {
            binding.layoutBackendSubscriptionQuotaContainer.setVisibility(View.GONE);
            return;
        }
        binding.layoutBackendSubscriptionQuotaContainer.setVisibility(View.VISIBLE);
        if (TextUtils.isEmpty(quotaState.summary)) {
            binding.textBackendSubscriptionSummary.setVisibility(View.GONE);
        } else {
            binding.textBackendSubscriptionSummary.setText(quotaState.summary);
            binding.textBackendSubscriptionSummary.setVisibility(View.VISIBLE);
        }
        if (!quotaState.showProgress) {
            binding.layoutBackendSubscriptionQuota.setVisibility(View.GONE);
            return;
        }
        binding.textBackendSubscriptionQuota.setText(quotaState.progressText);
        binding.progressBackendSubscriptionQuota.setMax(GROUP_QUOTA_PROGRESS_MAX);
        binding.progressBackendSubscriptionQuota.setProgress(quotaState.progress);
        binding.progressBackendSubscriptionQuota.setProgressTintList(
            ColorStateList.valueOf(ContextCompat.getColor(context, quotaState.colorResId))
        );
        binding.layoutBackendSubscriptionQuota.setVisibility(View.VISIBLE);
    }

    @Nullable
    private SubscriptionQuotaState selectedSubscriptionQuotaState(Context context) {
        if (!activeBackendFilterId.startsWith(FILTER_SUBSCRIPTION_PREFIX)) {
            return null;
        }
        String subId = activeBackendFilterId.substring(FILTER_SUBSCRIPTION_PREFIX.length());
        for (XraySubscription subscription : XrayStore.getSubscriptions(context)) {
            if (subscription != null && TextUtils.equals(subId, subscription.id)) {
                return buildSubscriptionQuotaState(context, subscription);
            }
        }
        return null;
    }

    @Nullable
    private SubscriptionQuotaState buildSubscriptionQuotaState(
        Context context,
        @Nullable XraySubscription subscription
    ) {
        if (subscription == null) {
            return null;
        }
        long usedBytes = Math.max(0L, subscription.advertisedUploadBytes + subscription.advertisedDownloadBytes);
        long totalBytes = Math.max(0L, subscription.advertisedTotalBytes);
        long expireAt = Math.max(0L, subscription.advertisedExpireAt);
        String expireDate = formatSubscriptionExpireDate(expireAt);
        if (totalBytes > 0L) {
            long remainingBytes = Math.max(totalBytes - usedBytes, 0L);
            double remainingRatio = totalBytes == 0L ? 0.0 : (double) remainingBytes / (double) totalBytes;
            int colorResId =
                remainingRatio <= 0.1d
                    ? R.color.wingsv_error
                    : remainingRatio <= 0.4d
                        ? R.color.wingsv_warning
                        : R.color.wingsv_success;
            String summary = TextUtils.isEmpty(expireDate)
                ? ""
                : getString(R.string.xray_profiles_subscription_expire_only, expireDate);
            String progressText = getString(
                R.string.xray_profiles_subscription_quota_used,
                UiFormatter.formatBytes(context, usedBytes),
                UiFormatter.formatBytes(context, totalBytes)
            );
            int progress = (int) Math.round(remainingRatio * GROUP_QUOTA_PROGRESS_MAX);
            progress = Math.max(0, Math.min(progress, GROUP_QUOTA_PROGRESS_MAX));
            return new SubscriptionQuotaState(summary, progressText, true, progress, colorResId);
        }
        if (usedBytes > 0L) {
            String summary = TextUtils.isEmpty(expireDate)
                ? ""
                : getString(R.string.xray_profiles_subscription_expire_only, expireDate);
            String progressText = getString(
                R.string.xray_profiles_subscription_quota_used,
                UiFormatter.formatBytes(context, usedBytes),
                getString(R.string.xray_profiles_subscription_limit_infinite)
            );
            return new SubscriptionQuotaState(
                summary,
                progressText,
                true,
                GROUP_QUOTA_PROGRESS_MAX,
                R.color.wingsv_success
            );
        }
        if (!TextUtils.isEmpty(expireDate)) {
            return new SubscriptionQuotaState(
                getString(R.string.xray_profiles_subscription_expire_only, expireDate),
                "",
                false,
                0,
                0
            );
        }
        return null;
    }

    private String formatSubscriptionExpireDate(long expireAt) {
        if (expireAt <= 0L) {
            return "";
        }
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(expireAt);
    }

    private static final class SubscriptionQuotaState {

        final String summary;
        final String progressText;
        final boolean showProgress;
        final int progress;
        final int colorResId;

        SubscriptionQuotaState(
            String summary,
            String progressText,
            boolean showProgress,
            int progress,
            int colorResId
        ) {
            this.summary = summary;
            this.progressText = progressText;
            this.showProgress = showProgress;
            this.progress = progress;
            this.colorResId = colorResId;
        }
    }

    private void refreshSubscriptions() {
        if (refreshingSubscriptions || !isAdded()) {
            return;
        }
        refreshingSubscriptions = true;
        if (binding != null) {
            binding.progressRefreshSubscriptions.setVisibility(View.VISIBLE);
            binding.rowRefreshSubscriptions.setSummary(getString(R.string.xray_profiles_refresh_subscriptions_running));
        }
        Context appContext = requireContext().getApplicationContext();
        subscriptionExecutor.execute(() -> {
            String message;
            try {
                wings.v.core.XraySubscriptionUpdater.RefreshResult result =
                    wings.v.core.XraySubscriptionUpdater.refreshAll(appContext);
                message = TextUtils.isEmpty(result.error)
                    ? appContext.getString(R.string.xray_profiles_refresh_subscriptions_done, result.profiles.size())
                    : appContext.getString(R.string.xray_subscriptions_refresh_partial, result.error);
            } catch (Exception error) {
                message = appContext.getString(R.string.xray_subscriptions_refresh_failed, error.getMessage());
            }
            final String toast = message;
            uiHandler.post(() -> {
                refreshingSubscriptions = false;
                if (binding == null || !isAdded()) {
                    return;
                }
                binding.progressRefreshSubscriptions.setVisibility(View.GONE);
                refreshUi();
                android.widget.Toast.makeText(requireContext(), toast, android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }

    // Settings does this with a DropDownPreference (pref_backend_top plus a
    // sub-backend one for VK TURN), but a plain Fragment cannot host a Preference.
    // So the same OneUI dropdown is assembled by hand: a CardItemView row driving a
    // hidden AppCompatSpinner with a SeslArrayAdapter over the same arrays/values.
    // The sub-backend row shows up only for VK TURN

    private void bindBackendDropdown(
        CardItemView row,
        AppCompatSpinner spinner,
        int entriesRes,
        int valuesRes,
        Supplier<String> getter,
        Consumer<String> setter
    ) {
        Context context = requireContext();
        CharSequence[] entries = getResources().getTextArray(entriesRes);
        String[] values = getResources().getStringArray(valuesRes);
        SeslArrayAdapter adapter = new SeslArrayAdapter(
            context,
            androidx.appcompat.R.layout.support_simple_spinner_dropdown_item
        );
        for (CharSequence entry : entries) {
            adapter.add(entry.toString());
        }
        spinner.setAdapter(adapter);
        spinner.setSoundEffectsEnabled(false);
        spinner.setSelection(indexOf(values, getter.get()));
        spinner.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position < 0 || position >= values.length) {
                        return;
                    }
                    String newValue = values[position];
                    if (TextUtils.equals(newValue, getter.get())) {
                        return;
                    }
                    Haptics.softSelection(parent);
                    setter.accept(newValue);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    // noop
                }
            }
        );
        row.setOnClickListener(view -> {
            Haptics.softSelection(view);
            spinner.setSelection(indexOf(values, getter.get()));
            spinner.performClick();
        });
    }

    private void onTopLevelChosen(String topLevel) {
        Context context = requireContext();
        if ("wb_stream".equals(topLevel)) {
            // WB Stream is in the list but has no backend, same as in settings
            Toast.makeText(context, R.string.backend_top_wb_stream_unavailable_toast, Toast.LENGTH_SHORT).show();
            refreshUi();
            return;
        }
        if ("vk_turn".equals(topLevel)) {
            applyBackend(BackendType.fromTopLevelAndSub("vk_turn", AppPrefs.getVkTurnTunnelMode(context)));
            return;
        }
        applyBackend(BackendType.fromTopLevelAndSub(topLevel, TunnelMode.WIREGUARD));
    }

    private void onSubBackendChosen(String subValue) {
        TunnelMode mode = TunnelMode.fromPrefValue(subValue);
        AppPrefs.setVkTurnTunnelMode(requireContext(), mode);
        applyBackend(BackendType.fromTopLevelAndSub("vk_turn", mode));
    }

    private static int indexOf(String[] values, String target) {
        for (int index = 0; index < values.length; index++) {
            if (TextUtils.equals(values[index], target)) {
                return index;
            }
        }
        return -1;
    }

    private void applyBackend(BackendType nextBackend) {
        ExternalActions.setBackend(requireContext(), nextBackend, true, false);
        refreshUi();
    }

    private String backendSelectorSummary(BackendType backendType) {
        switch (backendType.topLevelGroup()) {
            case "xray":
                return getString(R.string.backend_xray_title);
            case "vk_turn":
                return getString(R.string.backend_top_vk_turn_title);
            case "wireguard":
                return getString(R.string.backend_top_wireguard_title);
            case "amneziawg":
                return getString(R.string.backend_top_amneziawg_title);
            default:
                return getString(R.string.backend_profiles_selector_summary);
        }
    }

    private String subBackendSummary(BackendType backendType) {
        return backendType == BackendType.AMNEZIAWG
            ? getString(R.string.backend_profiles_vk_turn_transport_awg)
            : getString(R.string.backend_profiles_vk_turn_transport_wg);
    }

    // Xray: the existing ProfilesFragment goes in as is

    private void showXrayList() {
        // The embedded ProfilesFragment brings its own Actions card with a backend
        // selector and its own scroll, so the shared simple-profiles scroll is
        // hidden completely - otherwise a second selector hangs above it
        binding.scrollSimpleProfiles.setVisibility(View.GONE);
        binding.containerXrayProfiles.setVisibility(View.VISIBLE);
        if (getChildFragmentManager().findFragmentByTag(XRAY_CHILD_TAG) == null) {
            getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.container_xray_profiles, new ProfilesFragment(), XRAY_CHILD_TAG)
                .commit();
        }
    }

    // The simple-profiles scroll either takes the remaining space (WG / AWG /
    // VK TURN) or wraps its content so the embedded Xray fragment gets the rest.
    // Bottom padding clears the navigation bar only in the first case
    private void setSimpleScrollFills(boolean fills) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) binding.scrollSimpleProfiles.getLayoutParams();
        params.height = fills ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        params.weight = fills ? 1f : 0f;
        binding.scrollSimpleProfiles.setLayoutParams(params);
        int bottomPadding = fills ? dpToPx(104) : dpToPx(4);
        binding.scrollSimpleProfiles.setPadding(
            binding.scrollSimpleProfiles.getPaddingLeft(),
            binding.scrollSimpleProfiles.getPaddingTop(),
            binding.scrollSimpleProfiles.getPaddingRight(),
            bottomPadding
        );
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void removeXrayChildIfPresent() {
        Fragment child = getChildFragmentManager().findFragmentByTag(XRAY_CHILD_TAG);
        if (child != null) {
            getChildFragmentManager().beginTransaction().remove(child).commitAllowingStateLoss();
        }
    }

    // Simple list for WireGuard / AmneziaWG / VK TURN

    private void showSimpleList(BackendType backendType) {
        removeXrayChildIfPresent();
        binding.rowBackendAddProfile.setVisibility(View.VISIBLE);
        binding.sectionBackendProfilesList.setVisibility(View.VISIBLE);
        binding.containerXrayProfiles.setVisibility(View.GONE);
        setSimpleScrollFills(true);
        binding.scrollSimpleProfiles.setVisibility(View.VISIBLE);

        Context context = requireContext();
        List<SimpleProfile> allProfiles = loadProfiles(context, backendType);
        Set<String> favorites = favoriteIds(context, backendType);
        renderBackendFilterChips(context, allProfiles, favorites);
        List<SimpleProfile> profiles = filterBackendProfiles(allProfiles, favorites);
        String activeId = activeProfileId(context, backendType);
        Map<String, XrayStore.ProfileTrafficStats> traffic = trafficStatsMap(context, backendType);

        binding.containerBackendProfiles.removeAllViews();
        binding.textBackendProfilesEmpty.setVisibility(profiles.isEmpty() ? View.VISIBLE : View.GONE);
        binding.containerBackendProfiles.setVisibility(profiles.isEmpty() ? View.GONE : View.VISIBLE);

        rowBindingsById.clear();
        currentSelectionProfiles.clear();
        currentSelectionProfiles.addAll(profiles);
        currentSelectionBackend = backendType;
        Set<String> visibleIds = new java.util.HashSet<>();
        for (SimpleProfile candidate : profiles) {
            visibleIds.add(candidate.id);
        }
        // Rows can leave the list through a filter or a delete; drop their selection
        selectedBackendProfileIds.retainAll(visibleIds);
        if (backendSelectionMode && selectedBackendProfileIds.isEmpty()) {
            clearBackendSelectionMode();
        }

        LayoutInflater inflater = LayoutInflater.from(context);
        for (int index = 0; index < profiles.size(); index++) {
            SimpleProfile profile = profiles.get(index);
            ItemBackendProfileEntryBinding rowBinding = ItemBackendProfileEntryBinding.inflate(
                inflater,
                binding.containerBackendProfiles,
                false
            );
            bindRow(rowBinding, backendType, profile, activeId, traffic, index < profiles.size() - 1);
            rowBindingsById.put(profile.id, rowBinding);
            applyBackendRowState(rowBinding, profile.id, activeId);
            binding.containerBackendProfiles.addView(rowBinding.getRoot());
        }
        updateBackendSelectionUi();
    }

    private void bindRow(
        ItemBackendProfileEntryBinding rowBinding,
        BackendType backendType,
        SimpleProfile profile,
        String activeId,
        Map<String, XrayStore.ProfileTrafficStats> traffic,
        boolean showDivider
    ) {
        Context context = requireContext();
        rowBinding.textBackendProfileTitle.setText(profile.displayTitle(context));
        rowBinding.textBackendProfileSummary.setText(profile.displaySummary(context));
        rowBinding.viewBackendProfileDivider.setVisibility(showDivider ? View.VISIBLE : View.GONE);

        boolean active = TextUtils.equals(activeId, profile.id);
        rowBinding.imageBackendProfileActive.setVisibility(active ? View.VISIBLE : View.INVISIBLE);

        XrayStore.ProfileTrafficStats stats = traffic.get(profile.id);
        if (stats == null) {
            stats = XrayStore.ProfileTrafficStats.ZERO;
        }
        boolean hasTraffic = stats.rxBytes > 0L || stats.txBytes > 0L;
        rowBinding.layoutBackendProfileTraffic.setVisibility(hasTraffic ? View.VISIBLE : View.GONE);
        rowBinding.textBackendProfileRx.setText(UiFormatter.formatBytes(context, stats.rxBytes));
        rowBinding.textBackendProfileTx.setText(UiFormatter.formatBytes(context, stats.txBytes));

        bindProfileQuota(rowBinding, profile);

        applyFavoriteState(rowBinding, backendType, profile.id);

        rowBinding.rowBackendProfileEntry.setOnClickListener(v -> {
            if (backendSelectionMode) {
                toggleBackendSelection(profile.id);
            } else {
                onProfileSelected(backendType, profile.id);
            }
        });
        // Same as the Xray list: tap selects, long-press opens a PopupMenu on the
        // row. No per-row overflow button
        rowBinding.rowBackendProfileEntry.setOnLongClickListener(v -> {
            Haptics.softSelection(v);
            showProfileMenu(v, backendType, profile);
            return true;
        });
        rowBinding.buttonBackendProfileFavorite.setOnClickListener(v -> {
            Haptics.softSelection(v);
            toggleFavorite(backendType, profile.id);
            refreshUi();
        });
    }

    // Quota bar only for a standalone managed VK TURN profile (has a panel client
    // id, no parent subscription). Profiles under a subscription show their traffic
    // on the subscription itself, so a second bar on the row is noise
    private void bindProfileQuota(ItemBackendProfileEntryBinding rowBinding, SimpleProfile profile) {
        Context context = requireContext();
        if (!TextUtils.isEmpty(profile.subscriptionId) || TextUtils.isEmpty(profile.provisionClientId)) {
            rowBinding.layoutBackendProfileQuota.setVisibility(View.GONE);
            return;
        }
        AppPrefs.ClientTrafficUsage usage = AppPrefs.getClientTrafficUsage(context, profile.provisionClientId);
        if (usage == null || usage.limitBytes <= 0L) {
            rowBinding.layoutBackendProfileQuota.setVisibility(View.GONE);
            return;
        }
        long used = Math.max(0L, usage.usedBytes);
        long limit = usage.limitBytes;
        long remaining = Math.max(0L, usage.remainingBytes);
        double remainingRatio = (double) remaining / (double) limit;
        int colorResId =
            usage.disabled || remainingRatio <= 0.1d
                ? R.color.wingsv_error
                : remainingRatio <= 0.4d
                    ? R.color.wingsv_warning
                    : R.color.wingsv_success;
        int progress = (int) Math.round(remainingRatio * GROUP_QUOTA_PROGRESS_MAX);
        progress = Math.max(0, Math.min(progress, GROUP_QUOTA_PROGRESS_MAX));
        rowBinding.progressBackendProfileQuota.setProgress(progress);
        rowBinding.progressBackendProfileQuota.setProgressTintList(
            ColorStateList.valueOf(ContextCompat.getColor(context, colorResId))
        );
        rowBinding.textBackendProfileQuota.setText(
            getString(
                R.string.xray_profiles_subscription_quota_used,
                UiFormatter.formatBytes(context, used),
                UiFormatter.formatBytes(context, limit)
            )
        );
        rowBinding.layoutBackendProfileQuota.setVisibility(View.VISIBLE);
    }

    private void applyFavoriteState(ItemBackendProfileEntryBinding rowBinding, BackendType backendType, String id) {
        boolean favorite = isFavorite(requireContext(), backendType, id);
        rowBinding.buttonBackendProfileFavorite.setImageResource(
            favorite ? R.drawable.ic_star_filled : R.drawable.ic_star_outline
        );
        ImageViewCompat.setImageTintList(
            rowBinding.buttonBackendProfileFavorite,
            ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), favorite ? R.color.wingsv_accent : android.R.color.darker_gray)
            )
        );
        rowBinding.buttonBackendProfileFavorite.setContentDescription(
            getString(
                favorite
                    ? R.string.backend_profiles_action_favorite_remove
                    : R.string.backend_profiles_action_favorite_add
            )
        );
    }

    private void onProfileSelected(BackendType backendType, String profileId) {
        Context context = requireContext();
        if (TextUtils.equals(activeProfileId(context, backendType), profileId)) {
            // Re-project even on a re-tap: the profile can have become active
            // through migration or startup, and then KEY_ENDPOINT is not its own.
            // Idempotent and touches no markers, so nothing to re-render
            applyActiveToPrefs(context, backendType);
            return;
        }
        setActiveProfileId(context, backendType, profileId);
        applyActiveToPrefs(context, backendType);
        wings.v.service.ProxyTunnelService.requestReconnect(context, "Backend profile changed");
        Haptics.softSelection(binding.getRoot());
        // Move the checkmark on the already-inflated rows instead of calling
        // refreshUi(), which drops and re-inflates every row; on a large VK TURN
        // list that freezes the UI
        updateActiveMarkers(profileId);
        Toast.makeText(context, R.string.backend_profiles_selected, Toast.LENGTH_SHORT).show();
    }

    // Repaints active / selection state on the live rows, no inflation
    private void updateActiveMarkers(String activeId) {
        for (Map.Entry<String, ItemBackendProfileEntryBinding> entry : rowBindingsById.entrySet()) {
            applyBackendRowState(entry.getValue(), entry.getKey(), activeId);
        }
    }

    // Long-press menu, same as the Xray list: Select plus the editor entry points.
    // WireGuard / AmneziaWG get the wg-quick / awg-quick text editor and the UI
    // editor as separate items, VK TURN has one form. Share / reset / delete live in
    // the multi-select bar
    private void showProfileMenu(View anchor, BackendType backendType, SimpleProfile profile) {
        if (backendSelectionMode) {
            beginBackendSelection(profile.id);
            return;
        }
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 0, 0, R.string.backend_profiles_action_select);
        menu.getMenu().add(0, 4, 1, R.string.backend_profiles_action_rename);
        if (isVkTurn(backendType)) {
            menu.getMenu().add(0, 1, 1, R.string.backend_profiles_action_edit);
        } else {
            menu.getMenu().add(0, 2, 1, R.string.backend_profile_edit_choice_wg);
            menu.getMenu().add(0, 3, 2, R.string.backend_profile_edit_choice_ui);
        }
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0:
                    beginBackendSelection(profile.id);
                    return true;
                case 4:
                    showRenameDialog(backendType, profile);
                    return true;
                case 1:
                    startActivity(wings.v.VkTurnProfileEditorActivity.createIntent(requireContext(), profile.id));
                    return true;
                case 2:
                    startActivity(
                        wings.v.BackendProfileEditorActivity.createIntent(requireContext(), backendType, profile.id)
                    );
                    return true;
                case 3:
                    openUiSettingsForProfile(backendType, profile);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void beginBackendSelection(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return;
        }
        if (!backendSelectionMode) {
            backendSelectionMode = true;
            if (selectionBackCallback != null) {
                selectionBackCallback.setEnabled(true);
            }
        }
        selectedBackendProfileIds.add(profileId);
        updateBackendSelectionUi();
        updateAllBackendRowStates();
    }

    private void toggleBackendSelection(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return;
        }
        if (!backendSelectionMode) {
            beginBackendSelection(profileId);
            return;
        }
        if (selectedBackendProfileIds.contains(profileId)) {
            selectedBackendProfileIds.remove(profileId);
        } else {
            selectedBackendProfileIds.add(profileId);
        }
        if (selectedBackendProfileIds.isEmpty()) {
            clearBackendSelectionMode();
            return;
        }
        updateBackendSelectionUi();
        updateAllBackendRowStates();
    }

    private void clearBackendSelectionMode() {
        if (!backendSelectionMode && selectedBackendProfileIds.isEmpty()) {
            return;
        }
        backendSelectionMode = false;
        selectedBackendProfileIds.clear();
        if (selectionBackCallback != null) {
            selectionBackCallback.setEnabled(false);
        }
        updateBackendSelectionUi();
        updateAllBackendRowStates();
    }

    private void updateBackendSelectionUi() {
        if (binding == null) {
            return;
        }
        boolean visible = backendSelectionMode && !selectedBackendProfileIds.isEmpty();
        binding.layoutBackendProfileSelectionActions.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.textBackendProfileSelectionCount.setText(
            getString(R.string.xray_profiles_selected_count, selectedBackendProfileIds.size())
        );
        binding.buttonBackendProfileSelectAll.setText(
            areAllVisibleBackendProfilesSelected()
                ? R.string.xray_profiles_deselect_all_action
                : R.string.xray_profiles_select_all_action
        );
        if (getActivity() instanceof wings.v.MainActivity) {
            ((wings.v.MainActivity) getActivity()).setBottomNavigationSuppressed(visible);
        }
    }

    private void updateAllBackendRowStates() {
        if (binding == null || currentSelectionBackend == null) {
            return;
        }
        String activeId = activeProfileId(requireContext(), currentSelectionBackend);
        for (Map.Entry<String, ItemBackendProfileEntryBinding> entry : rowBindingsById.entrySet()) {
            applyBackendRowState(entry.getValue(), entry.getKey(), activeId);
        }
    }

    private void applyBackendRowState(ItemBackendProfileEntryBinding rowBinding, String profileId, String activeId) {
        boolean selected = selectedBackendProfileIds.contains(profileId);
        rowBinding.rowBackendProfileEntry.setActivated(selected);
        rowBinding.checkboxBackendProfileSelected.setVisibility(backendSelectionMode ? View.VISIBLE : View.GONE);
        rowBinding.checkboxBackendProfileSelected.setChecked(selected);
        boolean active = TextUtils.equals(activeId, profileId);
        rowBinding.imageBackendProfileActive.setVisibility(
            backendSelectionMode ? View.GONE : active ? View.VISIBLE : View.INVISIBLE
        );
    }

    private boolean areAllVisibleBackendProfilesSelected() {
        if (currentSelectionProfiles.isEmpty()) {
            return false;
        }
        for (SimpleProfile profile : currentSelectionProfiles) {
            if (!selectedBackendProfileIds.contains(profile.id)) {
                return false;
            }
        }
        return true;
    }

    private void selectAllBackendProfiles() {
        if (currentSelectionProfiles.isEmpty()) {
            return;
        }
        if (areAllVisibleBackendProfilesSelected()) {
            clearBackendSelectionMode();
            return;
        }
        if (!backendSelectionMode) {
            backendSelectionMode = true;
            if (selectionBackCallback != null) {
                selectionBackCallback.setEnabled(true);
            }
        }
        for (SimpleProfile profile : currentSelectionProfiles) {
            selectedBackendProfileIds.add(profile.id);
        }
        updateBackendSelectionUi();
        updateAllBackendRowStates();
    }

    private void shareSelectedBackendProfiles() {
        if (currentSelectionBackend == null || selectedBackendProfileIds.isEmpty()) {
            return;
        }
        Context context = requireContext();
        BackendType backendType = currentSelectionBackend;
        List<SimpleProfile> selected = new ArrayList<>();
        for (SimpleProfile profile : currentSelectionProfiles) {
            if (selectedBackendProfileIds.contains(profile.id)) {
                selected.add(profile);
            }
        }
        if (selected.isEmpty()) {
            return;
        }
        if (selected.size() == 1) {
            clearBackendSelectionMode();
            shareProfile(backendType, selected.get(0));
            return;
        }
        String bundleLink = buildBundleShareLink(context, backendType, selected);
        if (TextUtils.isEmpty(bundleLink)) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        sendShareText(bundleLink);
        clearBackendSelectionMode();
    }

    // One wingsv:// for the whole selection (for VK TURN plus the transports it
    // references): the importer only decodes the first wingsv:// it finds in pasted
    // text, so a newline-joined list would arrive truncated
    @Nullable
    private String buildBundleShareLink(Context context, BackendType backendType, List<SimpleProfile> selected) {
        try {
            if (isVkTurn(backendType)) {
                List<VkTurnProfile> profiles = new ArrayList<>();
                for (SimpleProfile profile : selected) {
                    VkTurnProfile resolved = VkTurnProfileStore.getProfileById(context, profile.id);
                    if (resolved != null) {
                        profiles.add(resolved);
                    }
                }
                return profiles.isEmpty()
                    ? null
                    : WingsImportParser.buildTurnProfilesLink(
                          context,
                          profiles,
                          VkTurnProfileStore.getActiveProfileId(context)
                      );
            }
            if (backendType == BackendType.AMNEZIAWG_PLAIN) {
                List<AmneziaProfile> profiles = new ArrayList<>();
                for (SimpleProfile profile : selected) {
                    AmneziaProfile resolved = AmneziaProfileStore.getProfileById(context, profile.id);
                    if (resolved != null) {
                        profiles.add(resolved);
                    }
                }
                return profiles.isEmpty()
                    ? null
                    : WingsImportParser.buildAmneziaProfilesLink(
                          profiles,
                          AmneziaProfileStore.getActiveProfileId(context)
                      );
            }
            List<WireGuardProfile> profiles = new ArrayList<>();
            for (SimpleProfile profile : selected) {
                WireGuardProfile resolved = WireGuardProfileStore.getProfileById(context, profile.id);
                if (resolved != null) {
                    profiles.add(resolved);
                }
            }
            return profiles.isEmpty()
                ? null
                : WingsImportParser.buildWireGuardProfilesLink(
                      profiles,
                      WireGuardProfileStore.getActiveProfileId(context)
                  );
        } catch (Exception ignored) {
            return null;
        }
    }

    private void resetSelectedBackendStats() {
        if (currentSelectionBackend == null || selectedBackendProfileIds.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(selectedBackendProfileIds);
        BackendType backendType = currentSelectionBackend;
        clearBackendSelectionMode();
        if (ids.size() == 1) {
            resetStats(backendType, ids.get(0));
            return;
        }
        resetProfileTrafficStats(requireContext(), backendType, ids);
        refreshUi();
        Toast.makeText(
            requireContext(),
            getString(R.string.xray_profiles_reset_stats_done, ids.size()),
            Toast.LENGTH_SHORT
        ).show();
    }

    private void confirmDeleteSelectedBackend() {
        if (currentSelectionBackend == null || selectedBackendProfileIds.isEmpty()) {
            return;
        }
        if (selectedBackendProfileIds.size() == 1) {
            String id = selectedBackendProfileIds.iterator().next();
            SimpleProfile profile = findSelectionProfile(id);
            BackendType backendType = currentSelectionBackend;
            if (profile != null) {
                clearBackendSelectionMode();
                confirmDelete(backendType, profile);
            }
            return;
        }
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.backend_profiles_delete_confirm_title)
            .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
            .setPositiveButton(R.string.backend_profiles_action_delete, (dialog, which) -> deleteSelectedBackend())
            .show();
    }

    @Nullable
    private SimpleProfile findSelectionProfile(String id) {
        for (SimpleProfile profile : currentSelectionProfiles) {
            if (TextUtils.equals(profile.id, id)) {
                return profile;
            }
        }
        return null;
    }

    private void deleteSelectedBackend() {
        if (currentSelectionBackend == null || selectedBackendProfileIds.isEmpty()) {
            return;
        }
        Context context = requireContext();
        List<String> ids = new ArrayList<>(selectedBackendProfileIds);
        for (String id : ids) {
            deleteProfile(context, currentSelectionBackend, id);
        }
        clearBackendSelectionMode();
        refreshUi();
        Toast.makeText(context, getString(R.string.xray_profiles_delete_done, ids.size()), Toast.LENGTH_SHORT).show();
    }

    private void resetStats(BackendType backendType, String profileId) {
        List<String> ids = new ArrayList<>();
        ids.add(profileId);
        resetProfileTrafficStats(requireContext(), backendType, ids);
        refreshUi();
        Toast.makeText(requireContext(), R.string.backend_profiles_reset_stats_done, Toast.LENGTH_SHORT).show();
    }

    private void showRenameDialog(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setText(profile.rawTitle);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(context)
            .setTitle(R.string.backend_profiles_rename_title)
            .setView(input)
            .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
            .setPositiveButton(R.string.backend_profiles_dialog_ok, (dialog, which) -> {
                String newTitle = input.getText() == null ? "" : input.getText().toString().trim();
                renameProfile(backendType, profile, newTitle);
                refreshUi();
                Toast.makeText(context, R.string.backend_profiles_rename_done, Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void confirmDelete(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        if (backendType == BackendType.WIREGUARD || backendType == BackendType.AMNEZIAWG_PLAIN) {
            String transportKind =
                backendType == BackendType.AMNEZIAWG_PLAIN
                    ? VkTurnProfile.TRANSPORT_KIND_AWG
                    : VkTurnProfile.TRANSPORT_KIND_WG;
            List<VkTurnProfile> dependents = VkTurnProfileStore.findVkTurnProfilesReferencing(
                context,
                transportKind,
                profile.id
            );
            if (!dependents.isEmpty()) {
                showCascadeDeleteDialog(backendType, profile, dependents);
                return;
            }
        }
        new AlertDialog.Builder(context)
            .setTitle(R.string.backend_profiles_delete_confirm_title)
            .setMessage(getString(R.string.backend_profiles_delete_confirm_message, profile.displayTitle(context)))
            .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
            .setPositiveButton(R.string.backend_profiles_action_delete, (dialog, which) ->
                performPlainDelete(backendType, profile)
            )
            .show();
    }

    private void showCascadeDeleteDialog(
        BackendType backendType,
        SimpleProfile profile,
        List<VkTurnProfile> dependents
    ) {
        Context context = requireContext();
        StringBuilder names = new StringBuilder();
        for (VkTurnProfile dependent : dependents) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(
                TextUtils.isEmpty(dependent.title) ? getString(R.string.backend_profiles_untitled) : dependent.title
            );
        }
        new AlertDialog.Builder(context)
            .setTitle(R.string.backend_profiles_delete_cascade_title)
            .setMessage(
                getString(R.string.backend_profiles_delete_cascade_message, dependents.size(), names.toString())
            )
            .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
            .setPositiveButton(R.string.backend_profiles_delete_cascade_confirm, (dialog, which) ->
                performCascadeDelete(backendType, profile)
            )
            .show();
    }

    private void performPlainDelete(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        boolean removed = deleteProfile(context, backendType, profile.id);
        afterDelete(context, backendType, removed);
    }

    private void performCascadeDelete(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        boolean removed;
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            removed = AmneziaProfileStore.deleteProfileCascade(context, profile.id);
        } else {
            removed = WireGuardProfileStore.deleteProfileCascade(context, profile.id);
        }
        afterDelete(context, backendType, removed);
    }

    private void afterDelete(Context context, BackendType backendType, boolean removed) {
        if (removed) {
            applyActiveToPrefs(context, backendType);
            wings.v.service.ProxyTunnelService.requestReconnect(context, "Backend profile deleted");
        }
        refreshUi();
        Toast.makeText(
            context,
            removed ? R.string.backend_profiles_delete_done : R.string.backend_profiles_delete_failed,
            Toast.LENGTH_SHORT
        ).show();
    }

    // Sharing as in Xray: proto+deflate+base64 wingsv:// through ACTION_SEND.
    // WG / AWG also offer the raw wg-quick / awg-quick text, which other clients
    // can read. VK TURN offers only the link (a layered endpoint is meaningless as
    // text), and that link embeds the referenced transport, since its id does not
    // exist on another device
    private void shareProfile(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        if (!isVkTurn(backendType)) {
            CharSequence[] items = {
                getString(R.string.backend_profile_share_choice_link),
                getString(R.string.backend_profile_share_choice_text),
            };
            new AlertDialog.Builder(context)
                .setTitle(R.string.backend_profiles_action_share)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        shareTransportLink(backendType, profile);
                    } else {
                        shareTransportText(backendType, profile);
                    }
                })
                .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
                .show();
            return;
        }
        // VK TURN: link only
        VkTurnProfile vkTurn = VkTurnProfileStore.getProfileById(context, profile.id);
        if (vkTurn == null) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            sendShareText(WingsImportParser.buildTurnProfileLink(context, vkTurn));
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareTransportLink(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        try {
            String link;
            if (backendType == BackendType.AMNEZIAWG_PLAIN) {
                AmneziaProfile amnezia = AmneziaProfileStore.getProfileById(context, profile.id);
                if (amnezia == null) {
                    throw new IllegalArgumentException("AmneziaWG profile not found");
                }
                link = WingsImportParser.buildAmneziaProfileLink(amnezia);
            } else {
                WireGuardProfile wireGuard = WireGuardProfileStore.getProfileById(context, profile.id);
                if (wireGuard == null) {
                    throw new IllegalArgumentException("WireGuard profile not found");
                }
                link = WingsImportParser.buildWireGuardProfileLink(wireGuard);
            }
            sendShareText(link);
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareTransportText(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        String text;
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfile amnezia = AmneziaProfileStore.getProfileById(context, profile.id);
            text = amnezia == null ? "" : amnezia.quickConfig;
        } else {
            WireGuardProfile wireGuard = WireGuardProfileStore.getProfileById(context, profile.id);
            text = wireGuard == null ? "" : WireGuardProfileEditorCodec.toEditableQuickConfig(wireGuard);
        }
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        sendShareText(text);
    }

    private void sendShareText(String sharedText) {
        Context context = requireContext();
        Intent sendIntent = new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, sharedText);
        Intent chooserIntent = Intent.createChooser(sendIntent, getString(R.string.xray_profiles_share_chooser));
        if (chooserIntent.resolveActivity(context.getPackageManager()) == null) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(chooserIntent);
    }

    // UI editor for WG / AWG: make the profile active, project it onto the flat
    // keys and open the backend settings. The edits fold back on the next
    // select/apply; here we only scope the settings to this profile's values
    private void openUiSettingsForProfile(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        setActiveProfileId(context, backendType, profile.id);
        applyActiveToPrefs(context, backendType);
        pendingUiEditBackend = backendType;
        startActivity(wings.v.VkTurnSettingsActivity.createIntent(context));
    }

    private void updateActiveFromFlatPrefs(Context context, BackendType backendType) {
        if (isVkTurn(backendType)) {
            VkTurnProfileStore.updateActiveFromFlatPrefs(context);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfileStore.updateActiveFromFlatPrefs(context);
        } else {
            WireGuardProfileStore.updateActiveFromFlatPrefs(context);
        }
    }

    // A clipboard config goes through AppPrefs.applyImportedConfig, which adds it as
    // a new profile on the matching backend

    private void importFromClipboard() {
        Context context = requireContext();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clipData.getItemAt(0).coerceToText(context);
        String rawText = text == null ? null : text.toString();
        List<String> candidates = WingsImportParser.splitConfigCandidates(rawText);
        if (candidates.size() > 1) {
            importManyFromClipboard(context, candidates);
            return;
        }
        try {
            WingsImportParser.ImportedConfig importedConfig = WingsImportParser.parseFromText(rawText);
            AppPrefs.applyImportedConfig(context, importedConfig);
            wings.v.service.ProxyTunnelService.requestReconnect(context, "Backend profile imported");
            refreshUi();
            Toast.makeText(context, R.string.backend_profiles_import_success, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.backend_profiles_import_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    // Batch paste: splitConfigCandidates guarantees every candidate is a link, so add
    // one profile each and report a single summary instead of a reconnect and a toast
    // per item. See issue #77
    private void importManyFromClipboard(Context context, List<String> candidates) {
        int imported = 0;
        int skipped = 0;
        for (String candidate : candidates) {
            try {
                AppPrefs.applyImportedConfig(context, WingsImportParser.parseFromText(candidate));
                imported++;
            } catch (Exception ignored) {
                skipped++;
            }
        }
        refreshUi();
        Toast.makeText(
            context,
            getString(R.string.clipboard_import_multi_summary, imported, skipped),
            Toast.LENGTH_LONG
        ).show();
    }

    // BackendType to store dispatch. The simple list serves four values: WIREGUARD,
    // AMNEZIAWG_PLAIN and the two VK TURN variants VK_TURN_WIREGUARD (WG transport)
    // and AMNEZIAWG (AWG transport). Both VK TURN variants have topLevelGroup() ==
    // "vk_turn" and route to VkTurnProfileStore; only the two plain backends go to
    // the transport stores. Keying on usesAmneziaSettings() would send AMNEZIAWG, a
    // VK TURN backend, to AmneziaProfileStore, which never projects the VK TURN
    // endpoint onto KEY_ENDPOINT, and the list would show the wrong profiles

    private static boolean isVkTurn(BackendType backendType) {
        return "vk_turn".equals(backendType.topLevelGroup());
    }

    private List<SimpleProfile> loadProfiles(Context context, BackendType backendType) {
        ArrayList<SimpleProfile> result = new ArrayList<>();
        if (isVkTurn(backendType)) {
            for (VkTurnProfile profile : VkTurnProfileStore.getProfiles(context)) {
                result.add(SimpleProfile.fromVkTurn(profile));
            }
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            for (AmneziaProfile profile : AmneziaProfileStore.getProfiles(context)) {
                result.add(SimpleProfile.fromAmnezia(profile));
            }
        } else {
            for (WireGuardProfile profile : WireGuardProfileStore.getProfiles(context)) {
                result.add(SimpleProfile.fromWireGuard(profile));
            }
        }
        return result;
    }

    private String activeProfileId(Context context, BackendType backendType) {
        if (isVkTurn(backendType)) {
            return VkTurnProfileStore.getActiveProfileId(context);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            return AmneziaProfileStore.getActiveProfileId(context);
        }
        return WireGuardProfileStore.getActiveProfileId(context);
    }

    private void setActiveProfileId(Context context, BackendType backendType, String profileId) {
        if (isVkTurn(backendType)) {
            VkTurnProfileStore.setActiveProfileId(context, profileId);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfileStore.setActiveProfileId(context, profileId);
        } else {
            WireGuardProfileStore.setActiveProfileId(context, profileId);
        }
    }

    private void applyActiveToPrefs(Context context, BackendType backendType) {
        if (isVkTurn(backendType)) {
            VkTurnProfileStore.applyActiveToPrefs(context);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfileStore.applyActiveToPrefs(context);
        } else {
            WireGuardProfileStore.applyActiveToPrefs(context);
        }
    }

    private Map<String, XrayStore.ProfileTrafficStats> trafficStatsMap(Context context, BackendType backendType) {
        if (isVkTurn(backendType)) {
            return VkTurnProfileStore.getProfileTrafficStatsMap(context);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            return AmneziaProfileStore.getProfileTrafficStatsMap(context);
        }
        return WireGuardProfileStore.getProfileTrafficStatsMap(context);
    }

    private void resetProfileTrafficStats(Context context, BackendType backendType, List<String> ids) {
        if (isVkTurn(backendType)) {
            VkTurnProfileStore.resetProfileTrafficStats(context, ids);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfileStore.resetProfileTrafficStats(context, ids);
        } else {
            WireGuardProfileStore.resetProfileTrafficStats(context, ids);
        }
    }

    private boolean isFavorite(Context context, BackendType backendType, String id) {
        if (isVkTurn(backendType)) {
            return VkTurnProfileStore.isFavorite(context, id);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            return AmneziaProfileStore.isFavorite(context, id);
        }
        return WireGuardProfileStore.isFavorite(context, id);
    }

    private void toggleFavorite(BackendType backendType, String id) {
        Context context = requireContext();
        if (isVkTurn(backendType)) {
            VkTurnProfileStore.toggleFavorite(context, id);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfileStore.toggleFavorite(context, id);
        } else {
            WireGuardProfileStore.toggleFavorite(context, id);
        }
    }

    private Set<String> favoriteIds(Context context, BackendType backendType) {
        if (isVkTurn(backendType)) {
            return VkTurnProfileStore.getFavoriteProfileIds(context);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            return AmneziaProfileStore.getFavoriteProfileIds(context);
        }
        return WireGuardProfileStore.getFavoriteProfileIds(context);
    }

    // Filters as in the Xray list: All, a bookmark chip for favorites when any
    // exist, one chip per subscription present in the profiles, and one for the
    // manually added ones. Subscription titles come from the live
    // XrayStore.getSubscriptions and fall back to the tag carried on the profile
    private void renderBackendFilterChips(Context context, List<SimpleProfile> profiles, Set<String> favorites) {
        binding.groupBackendProfileFilters.removeAllViews();
        Map<String, String> subscriptionTitles = new LinkedHashMap<>();
        for (XraySubscription subscription : XrayStore.getSubscriptions(context)) {
            if (subscription != null && !TextUtils.isEmpty(subscription.id)) {
                subscriptionTitles.put(subscription.id, subscription.title);
            }
        }
        boolean hasFavoriteProfile = false;
        boolean hasManualProfile = false;
        LinkedHashMap<String, String> subscriptionFilters = new LinkedHashMap<>();
        for (SimpleProfile profile : profiles) {
            if (favorites.contains(profile.id)) {
                hasFavoriteProfile = true;
            }
            if (TextUtils.isEmpty(profile.subscriptionId)) {
                hasManualProfile = true;
                continue;
            }
            if (!subscriptionFilters.containsKey(profile.subscriptionId)) {
                String live = subscriptionTitles.get(profile.subscriptionId);
                String title = !TextUtils.isEmpty(live)
                    ? live
                    : TextUtils.isEmpty(profile.subscriptionTitle)
                        ? getString(R.string.xray_profiles_filter_no_subscription)
                        : profile.subscriptionTitle;
                subscriptionFilters.put(profile.subscriptionId, title);
            }
        }
        // A subscription dropped by a refresh leaves a filter that renders an empty
        // list, so reset it
        if (FILTER_FAVORITES.equals(activeBackendFilterId) && !hasFavoriteProfile) {
            activeBackendFilterId = FILTER_ALL;
        } else if (FILTER_NO_SUBSCRIPTION.equals(activeBackendFilterId) && !hasManualProfile) {
            activeBackendFilterId = FILTER_ALL;
        } else if (
            activeBackendFilterId.startsWith(FILTER_SUBSCRIPTION_PREFIX) &&
            !subscriptionFilters.containsKey(activeBackendFilterId.substring(FILTER_SUBSCRIPTION_PREFIX.length()))
        ) {
            activeBackendFilterId = FILTER_ALL;
        }
        addFilterChip(context, FILTER_ALL, getString(R.string.xray_profiles_filter_all), false);
        if (hasFavoriteProfile) {
            addFilterChip(context, FILTER_FAVORITES, getString(R.string.xray_profiles_filter_favorites), true);
        }
        for (Map.Entry<String, String> entry : subscriptionFilters.entrySet()) {
            addFilterChip(context, FILTER_SUBSCRIPTION_PREFIX + entry.getKey(), entry.getValue(), false);
        }
        if (hasManualProfile && !subscriptionFilters.isEmpty()) {
            addFilterChip(
                context,
                FILTER_NO_SUBSCRIPTION,
                getString(R.string.xray_profiles_filter_no_subscription),
                false
            );
        }
    }

    // Narrows by the active chip: All, Favorites, one source subscription (sub:id)
    // or the manually added ones
    private List<SimpleProfile> filterBackendProfiles(List<SimpleProfile> profiles, Set<String> favorites) {
        if (FILTER_ALL.equals(activeBackendFilterId)) {
            return profiles;
        }
        List<SimpleProfile> result = new ArrayList<>();
        if (FILTER_FAVORITES.equals(activeBackendFilterId)) {
            for (SimpleProfile profile : profiles) {
                if (favorites.contains(profile.id)) {
                    result.add(profile);
                }
            }
            return result;
        }
        if (FILTER_NO_SUBSCRIPTION.equals(activeBackendFilterId)) {
            for (SimpleProfile profile : profiles) {
                if (TextUtils.isEmpty(profile.subscriptionId)) {
                    result.add(profile);
                }
            }
            return result;
        }
        if (activeBackendFilterId.startsWith(FILTER_SUBSCRIPTION_PREFIX)) {
            String selectedId = activeBackendFilterId.substring(FILTER_SUBSCRIPTION_PREFIX.length());
            for (SimpleProfile profile : profiles) {
                if (TextUtils.equals(selectedId, profile.subscriptionId)) {
                    result.add(profile);
                }
            }
            return result;
        }
        return profiles;
    }

    private void addFilterChip(Context context, String filterId, String title, boolean bookmarkIcon) {
        boolean selected = TextUtils.equals(filterId, activeBackendFilterId);
        View pill;
        if (bookmarkIcon) {
            ImageView iconPill = new ImageView(context);
            iconPill.setBackgroundResource(R.drawable.bg_profile_filter_chip);
            iconPill.setMinimumHeight(dpToPx(36));
            iconPill.setMinimumWidth(dpToPx(44));
            iconPill.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
            iconPill.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iconPill.setImageResource(R.drawable.ic_bookmark);
            ColorStateList tint = ContextCompat.getColorStateList(context, R.color.profile_filter_bookmark_tint);
            if (tint != null) {
                ImageViewCompat.setImageTintList(iconPill, tint);
            }
            iconPill.setContentDescription(title);
            pill = iconPill;
        } else {
            TextView textPill = new TextView(context);
            textPill.setText(title);
            textPill.setGravity(Gravity.CENTER);
            textPill.setMinHeight(dpToPx(36));
            textPill.setBackgroundResource(R.drawable.bg_profile_filter_chip);
            textPill.setTextSize(15f);
            textPill.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
            ColorStateList textColors = ContextCompat.getColorStateList(context, R.color.profile_filter_text);
            if (textColors != null) {
                textPill.setTextColor(textColors);
            }
            pill = textPill;
        }
        pill.setSelected(selected);
        pill.setOnClickListener(v -> {
            Haptics.softSelection(v);
            activeBackendFilterId = filterId;
            refreshUi();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        if (binding.groupBackendProfileFilters.getChildCount() > 0) {
            params.setMarginStart(dpToPx(8));
        }
        binding.groupBackendProfileFilters.addView(pill, params);
    }

    private boolean deleteProfile(Context context, BackendType backendType, String id) {
        if (isVkTurn(backendType)) {
            return VkTurnProfileStore.deleteProfile(context, id);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            return AmneziaProfileStore.deleteProfile(context, id);
        }
        return WireGuardProfileStore.deleteProfile(context, id);
    }

    private void renameProfile(BackendType backendType, SimpleProfile profile, String newTitle) {
        Context context = requireContext();
        if (isVkTurn(backendType)) {
            VkTurnProfile current = VkTurnProfileStore.getProfileById(context, profile.id);
            if (current != null) {
                VkTurnProfileStore.replaceProfile(context, renamedVkTurn(current, newTitle));
            }
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfile current = AmneziaProfileStore.getProfileById(context, profile.id);
            if (current != null) {
                AmneziaProfileStore.replaceProfile(
                    context,
                    new AmneziaProfile(current.id, newTitle, current.quickConfig)
                );
            }
        } else {
            WireGuardProfile current = WireGuardProfileStore.getProfileById(context, profile.id);
            if (current != null) {
                WireGuardProfileStore.replaceProfile(
                    context,
                    new WireGuardProfile(
                        current.id,
                        newTitle,
                        current.privateKey,
                        current.addresses,
                        current.dns,
                        current.mtu,
                        current.publicKey,
                        current.presharedKey,
                        current.allowedIps,
                        current.endpoint
                    )
                );
            }
        }
    }

    private VkTurnProfile renamedVkTurn(VkTurnProfile current, String newTitle) {
        return new VkTurnProfile(
            current.id,
            newTitle,
            current.transportKind,
            current.transportProfileId,
            current.vkTurnEndpoint,
            current.threads,
            current.credsGroupSize,
            current.useUdp,
            current.noObfuscation,
            current.manualCaptcha,
            current.captchaAutoSolver,
            current.vkAuthMode,
            current.turnSessionMode,
            current.dnsMode,
            current.userDns,
            current.runtimeMode,
            current.restartOnNetworkChange,
            current.wrapMode,
            current.wrapCipher,
            current.wrapKeyHex,
            current.wrapSendKey,
            current.localEndpoint,
            current.turnHost,
            current.turnPort
        );
    }

    // Flattens the three profile types into one row model
    private static final class SimpleProfile {

        final String id;
        final String rawTitle;
        final String summary;
        final String subscriptionId;
        final String subscriptionTitle;
        // Panel client id of a managed VK TURN profile, empty otherwise. Keys the
        // per-profile traffic-limit usage
        final String provisionClientId;

        private SimpleProfile(
            String id,
            String rawTitle,
            String summary,
            String subscriptionId,
            String subscriptionTitle,
            String provisionClientId
        ) {
            this.id = id == null ? "" : id;
            this.rawTitle = rawTitle == null ? "" : rawTitle;
            this.summary = summary == null ? "" : summary;
            this.subscriptionId = subscriptionId == null ? "" : subscriptionId;
            this.subscriptionTitle = subscriptionTitle == null ? "" : subscriptionTitle;
            this.provisionClientId = provisionClientId == null ? "" : provisionClientId;
        }

        static SimpleProfile fromWireGuard(WireGuardProfile profile) {
            return new SimpleProfile(
                profile.id,
                profile.title,
                profile.endpoint,
                profile.subscriptionId,
                profile.subscriptionTitle,
                ""
            );
        }

        static SimpleProfile fromAmnezia(AmneziaProfile profile) {
            return new SimpleProfile(
                profile.id,
                profile.title,
                "",
                profile.subscriptionId,
                profile.subscriptionTitle,
                ""
            );
        }

        static SimpleProfile fromVkTurn(VkTurnProfile profile) {
            String transport = VkTurnProfile.TRANSPORT_KIND_AWG.equals(profile.transportKind) ? "AWG" : "WG";
            String summary = profile.vkTurnEndpoint;
            if (!TextUtils.isEmpty(summary)) {
                summary = summary + " (" + transport + ")";
            } else {
                summary = transport;
            }
            return new SimpleProfile(
                profile.id,
                profile.title,
                summary,
                profile.subscriptionId,
                profile.subscriptionTitle,
                profile.provisionClientId
            );
        }

        String displayTitle(Context context) {
            if (!TextUtils.isEmpty(rawTitle)) {
                return rawTitle;
            }
            if (!TextUtils.isEmpty(summary)) {
                return summary;
            }
            return context.getString(R.string.backend_profiles_untitled);
        }

        String displaySummary(Context context) {
            if (!TextUtils.isEmpty(summary) && !summary.equals(rawTitle)) {
                return summary;
            }
            return shortId();
        }

        private String shortId() {
            if (id.length() <= 8) {
                return id;
            }
            return id.substring(0, 8).toLowerCase(Locale.ROOT);
        }
    }
}
