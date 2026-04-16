package wings.v.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.RuStoreRecommendedAppsAsset;
import wings.v.databinding.FragmentAppsBinding;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class AppsFragment extends Fragment {

    private static final String STATE_SEARCH_QUERY = "apps_search_query";
    private static final String STATE_SEARCH_VISIBLE = "apps_search_visible";
    private static final String STATE_SELECTED_ONLY_MODE = "apps_selected_only_mode";
    private static final String STATE_APP_TYPE_FILTER = "apps_type_filter";
    private static final String FILTER_ALL = "all";
    private static final String FILTER_RECOMMENDED = "recommended";
    private static final String FILTER_SYSTEM = "system";
    private static final String FILTER_USER = "user";
    private static final long SEARCH_BAR_ANIMATION_MS = 180L;
    private static final float SEARCH_BAR_HIDDEN_ALPHA = 0.92f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<AppRoutingEntry> appEntries = new ArrayList<>();
    private final Set<String> enabledPackages = new LinkedHashSet<>();
    private final OnBackPressedCallback searchBackCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            hideSearchOverlay(true);
        }
    };

    private FragmentAppsBinding binding;
    private AppRoutingAdapter adapter;
    private AppSearchAdapter searchAdapter;
    private String searchQuery = "";
    private boolean searchOverlayVisible;
    private boolean searchBarHidden;
    private boolean selectedOnlyMode;
    private boolean appRoutingChanged;
    private String activeAppTypeFilter = FILTER_ALL;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            searchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "");
            searchOverlayVisible = savedInstanceState.getBoolean(STATE_SEARCH_VISIBLE, false);
            selectedOnlyMode = savedInstanceState.getBoolean(STATE_SELECTED_ONLY_MODE, false);
            activeAppTypeFilter = savedInstanceState.getString(STATE_APP_TYPE_FILTER, FILTER_ALL);
        }
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentAppsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), searchBackCallback);

        adapter = new AppRoutingAdapter(
            new AppRoutingAdapter.Callback() {
                @Override
                public void onPackageToggled(String packageName, boolean enabled, View sourceView) {
                    AppsFragment.this.onPackageToggled(packageName, enabled, sourceView);
                }

                @Override
                public void onBypassModeChanged(boolean enabled, View sourceView) {
                    AppsFragment.this.onBypassModeChanged(enabled, sourceView);
                }

                @Override
                public void onSelectedAppsRequested(View sourceView) {
                    AppsFragment.this.onSelectedAppsRequested(sourceView);
                }
            }
        );
        searchAdapter = new AppSearchAdapter(this::onPackageToggled);

        binding.recyclerApps.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerApps.setAdapter(adapter);
        binding.recyclerSearchResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerSearchResults.setAdapter(searchAdapter);
        binding.recyclerApps.addOnScrollListener(
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (dy == 0) {
                        return;
                    }
                    hideKeyboard();
                    if (!searchOverlayVisible) {
                        if (dy > 0) {
                            setSearchBarHidden(true);
                        } else if (dy < 0) {
                            setSearchBarHidden(false);
                        }
                    }
                    updateScrollToTopButton();
                }
            }
        );
        binding.recyclerSearchResults.addOnScrollListener(
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (dy != 0) {
                        hideKeyboard();
                    }
                    updateScrollToTopButton();
                }
            }
        );

        binding.searchBarContainer.setOnClickListener(v -> {
            binding.inputAppSearch.requestFocus();
            showSearchOverlay(true);
        });
        binding.inputAppSearch.setOnClickListener(v -> showSearchOverlay(true));
        binding.inputAppSearch.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showSearchOverlay(true);
            }
        });
        binding.inputAppSearch.setOnEditorActionListener((v, actionId, event) -> {
            boolean isSearchAction =
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null &&
                    event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                    event.getAction() == KeyEvent.ACTION_DOWN);
            if (!isSearchAction) {
                return false;
            }
            hideKeyboard();
            binding.inputAppSearch.clearFocus();
            return true;
        });
        binding.inputAppSearch.addTextChangedListener(
            new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    onSearchQueryChanged(s != null ? s.toString() : "");
                }

                @Override
                public void afterTextChanged(Editable s) {}
            }
        );
        binding.buttonSearchClose.setOnClickListener(v -> {
            binding.inputAppSearch.setText("");
            hideSearchOverlay(true);
            Haptics.softSliderStep(v);
        });
        binding.buttonScrollToTop.setOnClickListener(v -> {
            RecyclerView activeRecycler = searchOverlayVisible ? binding.recyclerSearchResults : binding.recyclerApps;
            activeRecycler.smoothScrollToPosition(0);
            setSearchBarHidden(false);
            Haptics.softSliderStep(v);
        });

        binding.inputAppSearch.setText(searchQuery);
        binding.inputAppSearch.setSelection(binding.inputAppSearch.length());
        renderAppTypeFilters();
        loadApplications();

        if (searchOverlayVisible) {
            binding.inputAppSearch.post(() -> showSearchOverlay(true));
        } else {
            updateSearchResults();
        }
        updateScrollToTopButton();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SEARCH_QUERY, searchQuery);
        outState.putBoolean(STATE_SEARCH_VISIBLE, searchOverlayVisible);
        outState.putBoolean(STATE_SELECTED_ONLY_MODE, selectedOnlyMode);
        outState.putString(STATE_APP_TYPE_FILTER, activeAppTypeFilter);
    }

    @Override
    public void onPause() {
        requestDeferredReconnectIfNeeded();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadApplications();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding.recyclerApps.setAdapter(null);
        binding.recyclerSearchResults.setAdapter(null);
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void onPackageToggled(String packageName, boolean enabled, View sourceView) {
        Context context = requireContext();
        if (TextUtils.equals(packageName, context.getPackageName())) {
            return;
        }
        boolean recommendedPackage = RuStoreRecommendedAppsAsset.getApps(context).containsKey(packageName);
        boolean bypassEnabled = AppPrefs.isAppRoutingBypassEnabled(context);
        if (recommendedPackage) {
            if (enabled) {
                AppPrefs.setAppRoutingRecommendedPackageDismissed(context, packageName, false);
            } else if (bypassEnabled) {
                AppPrefs.setAppRoutingRecommendedPackageDismissed(context, packageName, true);
            }
        }
        AppPrefs.setAppRoutingPackageEnabled(context, packageName, enabled);
        appRoutingChanged = true;
        if (enabled) {
            enabledPackages.add(packageName);
        } else {
            enabledPackages.remove(packageName);
        }
        adapter.replaceItems(
            filterEntries("", false),
            enabledPackages,
            AppPrefs.isAppRoutingBypassEnabled(requireContext())
        );
        updateSearchResults();
        updateSearchResultsEmptyState();
        Haptics.softSliderStep(sourceView);
    }

    private void onBypassModeChanged(boolean enabled, View sourceView) {
        AppPrefs.setAppRoutingBypassEnabled(requireContext(), enabled);
        appRoutingChanged = true;
        if (enabled && syncRecommendedPackages()) {
            adapter.replaceItems(filterEntries("", false), enabledPackages, true);
            updateMainEmptyState();
            updateSearchResults();
        } else {
            adapter.setBypassEnabled(enabled);
        }
        Haptics.softSliderStep(sourceView);
    }

    private void requestDeferredReconnectIfNeeded() {
        if (!appRoutingChanged || !ProxyTunnelService.isActive()) {
            appRoutingChanged = false;
            return;
        }
        appRoutingChanged = false;
        ProxyTunnelService.requestReconnect(requireContext().getApplicationContext(), "App routing changed");
    }

    private void onSearchQueryChanged(String query) {
        searchQuery = query == null ? "" : query;
        updateSearchResults();
        if (!searchOverlayVisible && binding != null && binding.inputAppSearch.hasFocus()) {
            selectedOnlyMode = false;
            showSearchOverlay(false);
        }
    }

    private void onSelectedAppsRequested(View sourceView) {
        showSelectedAppsOverlay();
        Haptics.softSliderStep(sourceView);
    }

    private void loadApplications() {
        if (binding == null) {
            return;
        }
        Context appContext = requireContext().getApplicationContext();
        binding.progressApps.setVisibility(View.VISIBLE);
        binding.recyclerApps.setVisibility(View.VISIBLE);
        binding.textAppsEmpty.setVisibility(View.GONE);
        executor.execute(() -> {
            List<AppRoutingEntry> entries = queryInstalledApps(appContext);
            Set<String> installedPackages = new LinkedHashSet<>();
            for (AppRoutingEntry entry : entries) {
                installedPackages.add(entry.packageName);
            }
            boolean autoSelected = AppPrefs.syncRecommendedAppRoutingPackages(appContext, installedPackages);
            AppPrefs.setAppRoutingPackageEnabled(appContext, appContext.getPackageName(), false);
            Set<String> storedEnabledPackages = AppPrefs.getAppRoutingPackages(appContext);
            boolean bypassEnabled = AppPrefs.isAppRoutingBypassEnabled(appContext);
            mainHandler.post(() -> {
                if (!isAdded() || binding == null) {
                    return;
                }
                binding.progressApps.setVisibility(View.GONE);
                appEntries.clear();
                appEntries.addAll(entries);
                enabledPackages.clear();
                enabledPackages.addAll(storedEnabledPackages);
                if (autoSelected) {
                    appRoutingChanged = true;
                }
                adapter.replaceItems(filterEntries("", false), enabledPackages, bypassEnabled);
                updateMainEmptyState();
                updateSearchResults();
                updateScrollToTopButton();
            });
        });
    }

    private boolean syncRecommendedPackages() {
        Context appContext = requireContext().getApplicationContext();
        Set<String> installedPackages = new LinkedHashSet<>();
        for (AppRoutingEntry entry : appEntries) {
            installedPackages.add(entry.packageName);
        }
        boolean changed = AppPrefs.syncRecommendedAppRoutingPackages(appContext, installedPackages);
        if (changed) {
            enabledPackages.clear();
            enabledPackages.addAll(AppPrefs.getAppRoutingPackages(appContext));
        }
        return changed;
    }

    private void updateMainEmptyState() {
        if (binding == null || adapter == null || binding.progressApps.getVisibility() == View.VISIBLE) {
            return;
        }
        if (!adapter.hasAnyApps()) {
            binding.textAppsEmpty.setText(appEntries.isEmpty() ? R.string.apps_empty : R.string.apps_no_results);
            binding.textAppsEmpty.setVisibility(View.VISIBLE);
            return;
        }
        binding.textAppsEmpty.setVisibility(View.GONE);
    }

    private void updateSearchResults() {
        if (binding == null || searchAdapter == null) {
            return;
        }
        searchAdapter.replaceItems(filterEntries(searchQuery, true), enabledPackages);
        updateSearchResultsEmptyState();
    }

    private void updateSearchResultsEmptyState() {
        if (binding == null) {
            return;
        }
        if (!searchOverlayVisible) {
            binding.textSearchEmpty.setVisibility(View.GONE);
            return;
        }
        List<AppRoutingEntry> filteredEntries = filterEntries(searchQuery, true);
        if (selectedOnlyMode && enabledPackages.isEmpty()) {
            binding.textSearchEmpty.setText(R.string.apps_selected_empty);
            binding.textSearchEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (appEntries.isEmpty()) {
            binding.textSearchEmpty.setText(R.string.apps_empty);
            binding.textSearchEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (filteredEntries.isEmpty()) {
            binding.textSearchEmpty.setText(R.string.apps_no_results);
            binding.textSearchEmpty.setVisibility(View.VISIBLE);
            return;
        }
        binding.textSearchEmpty.setVisibility(View.GONE);
    }

    private List<AppRoutingEntry> filterEntries(String query, boolean includeSelectedOnly) {
        List<AppRoutingEntry> filteredEntries = new ArrayList<>();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        for (AppRoutingEntry entry : appEntries) {
            if (includeSelectedOnly && selectedOnlyMode && !enabledPackages.contains(entry.packageName)) {
                continue;
            }
            if (!matchesAppTypeFilter(entry)) {
                continue;
            }
            if (
                normalizedQuery.isEmpty() ||
                entry.label.toLowerCase(Locale.getDefault()).contains(normalizedQuery) ||
                entry.packageName.toLowerCase(Locale.ROOT).contains(normalizedQuery)
            ) {
                filteredEntries.add(entry);
            }
        }
        sortSelectedFirst(filteredEntries);
        return filteredEntries;
    }

    private void sortSelectedFirst(List<AppRoutingEntry> entries) {
        if (entries == null || entries.size() < 2) {
            return;
        }
        entries.sort(
            Comparator.comparingInt((AppRoutingEntry entry) -> enabledPackages.contains(entry.packageName) ? 0 : 1)
                .thenComparing(entry -> entry.label.toLowerCase(Locale.getDefault()))
                .thenComparing(entry -> entry.packageName)
        );
    }

    private boolean matchesAppTypeFilter(AppRoutingEntry entry) {
        if (entry == null || TextUtils.equals(activeAppTypeFilter, FILTER_ALL)) {
            return true;
        }
        if (TextUtils.equals(activeAppTypeFilter, FILTER_RECOMMENDED)) {
            return entry.recommendedApp;
        }
        if (TextUtils.equals(activeAppTypeFilter, FILTER_USER)) {
            return !entry.systemApp;
        }
        if (TextUtils.equals(activeAppTypeFilter, FILTER_SYSTEM)) {
            return entry.systemApp;
        }
        return true;
    }

    private void renderAppTypeFilters() {
        if (binding == null) {
            return;
        }
        binding.groupAppTypeFilters.removeAllViews();
        addFilterChip(FILTER_ALL, getString(R.string.apps_filter_all));
        addFilterChip(FILTER_RECOMMENDED, getString(R.string.apps_filter_recommended));
        addFilterChip(FILTER_USER, getString(R.string.apps_filter_user));
        addFilterChip(FILTER_SYSTEM, getString(R.string.apps_filter_system));
    }

    private void addFilterChip(@NonNull String filterId, @NonNull String title) {
        Context context = requireContext();
        TextView pill = new TextView(context);
        boolean selected = TextUtils.equals(activeAppTypeFilter, filterId);
        pill.setText(title);
        pill.setGravity(Gravity.CENTER);
        pill.setMinHeight(dpToPxInt(36));
        pill.setPadding(dpToPxInt(16), dpToPxInt(8), dpToPxInt(16), dpToPxInt(8));
        pill.setBackgroundResource(R.drawable.bg_profile_filter_chip);
        pill.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small);
        pill.setTextSize(15f);
        pill.setSelected(selected);
        ColorStateList textColors = AppCompatResources.getColorStateList(context, R.color.profile_filter_text);
        if (textColors != null) {
            pill.setTextColor(textColors);
        }
        pill.setOnClickListener(v -> {
            if (TextUtils.equals(activeAppTypeFilter, filterId)) {
                return;
            }
            activeAppTypeFilter = filterId;
            selectedOnlyMode = false;
            renderAppTypeFilters();
            adapter.replaceItems(
                filterEntries("", false),
                enabledPackages,
                AppPrefs.isAppRoutingBypassEnabled(requireContext())
            );
            updateMainEmptyState();
            updateSearchResults();
            Haptics.softSelection(v);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        if (binding.groupAppTypeFilters.getChildCount() > 0) {
            params.setMarginStart(dpToPxInt(8));
        }
        binding.groupAppTypeFilters.addView(pill, params);
    }

    private void showSearchOverlay(boolean requestKeyboard) {
        if (binding == null) {
            return;
        }
        searchOverlayVisible = true;
        searchBackCallback.setEnabled(true);
        binding.searchOverlayContainer.setVisibility(View.VISIBLE);
        binding.buttonSearchClose.setVisibility(View.VISIBLE);
        setSearchBarHidden(false);
        updateSearchResults();
        updateScrollToTopButton();
        if (requestKeyboard) {
            binding.inputAppSearch.requestFocus();
            showKeyboard();
        }
    }

    private void showSelectedAppsOverlay() {
        if (binding == null) {
            return;
        }
        selectedOnlyMode = true;
        searchQuery = "";
        if (binding.inputAppSearch.getText() == null || binding.inputAppSearch.getText().length() != 0) {
            binding.inputAppSearch.setText("");
        }
        binding.inputAppSearch.clearFocus();
        hideKeyboard();
        searchOverlayVisible = true;
        searchBackCallback.setEnabled(true);
        binding.searchOverlayContainer.setVisibility(View.VISIBLE);
        binding.buttonSearchClose.setVisibility(View.VISIBLE);
        binding.recyclerSearchResults.scrollToPosition(0);
        setSearchBarHidden(false);
        updateSearchResults();
        updateScrollToTopButton();
    }

    private void hideSearchOverlay(boolean clearFocus) {
        if (binding == null) {
            return;
        }
        searchOverlayVisible = false;
        selectedOnlyMode = false;
        searchBackCallback.setEnabled(false);
        binding.searchOverlayContainer.setVisibility(View.GONE);
        binding.textSearchEmpty.setVisibility(View.GONE);
        binding.buttonSearchClose.setVisibility(View.GONE);
        updateScrollToTopButton();
        if (clearFocus) {
            binding.inputAppSearch.clearFocus();
            hideKeyboard();
        }
    }

    private void setSearchBarHidden(boolean hidden) {
        if (binding == null || (searchOverlayVisible && hidden)) {
            return;
        }
        if (searchBarHidden == hidden) {
            return;
        }
        searchBarHidden = hidden;
        float targetTranslation = hidden ? binding.searchBarContainer.getHeight() + dpToPx(24) : 0f;
        float targetAlpha = hidden ? SEARCH_BAR_HIDDEN_ALPHA : 1f;
        binding.searchBarContainer
            .animate()
            .translationY(targetTranslation)
            .alpha(targetAlpha)
            .setDuration(SEARCH_BAR_ANIMATION_MS)
            .start();
    }

    private void updateScrollToTopButton() {
        if (binding == null) {
            return;
        }
        boolean shouldShow;
        if (searchOverlayVisible) {
            shouldShow = false;
        } else {
            shouldShow = searchBarHidden && binding.recyclerApps.canScrollVertically(-1);
        }
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

    private float dpToPx(int value) {
        return value * requireContext().getResources().getDisplayMetrics().density;
    }

    private int dpToPxInt(int value) {
        return Math.round(dpToPx(value));
    }

    private void showKeyboard() {
        if (binding == null) {
            return;
        }
        InputMethodManager inputMethodManager = requireContext().getSystemService(InputMethodManager.class);
        if (inputMethodManager != null) {
            binding.inputAppSearch.post(() ->
                inputMethodManager.showSoftInput(binding.inputAppSearch, InputMethodManager.SHOW_IMPLICIT)
            );
        }
    }

    private void hideKeyboard() {
        if (binding == null) {
            return;
        }
        InputMethodManager inputMethodManager = requireContext().getSystemService(InputMethodManager.class);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(binding.inputAppSearch.getWindowToken(), 0);
        }
    }

    private List<AppRoutingEntry> queryInstalledApps(Context context) {
        PackageManager packageManager = context.getPackageManager();
        String ownPackageName = context.getPackageName();
        List<ApplicationInfo> installedApplications;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            installedApplications = packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA)
            );
        } else {
            installedApplications = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
        }

        List<AppRoutingEntry> entries = new ArrayList<>(installedApplications.size());
        Set<String> recommendedPackages = RuStoreRecommendedAppsAsset.getPackageNames(context);
        for (ApplicationInfo applicationInfo : installedApplications) {
            if (TextUtils.equals(applicationInfo.packageName, ownPackageName)) {
                continue;
            }
            String label = applicationInfo.loadLabel(packageManager).toString().trim();
            if (label.isEmpty()) {
                label = applicationInfo.packageName;
            }
            entries.add(
                new AppRoutingEntry(
                    label,
                    applicationInfo.packageName,
                    applicationInfo.loadIcon(packageManager),
                    (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    recommendedPackages.contains(applicationInfo.packageName)
                )
            );
        }
        entries.sort(
            Comparator.comparing((AppRoutingEntry entry) -> entry.label.toLowerCase(Locale.getDefault())).thenComparing(
                entry -> entry.packageName
            )
        );
        return entries;
    }
}
