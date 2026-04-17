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
import wings.v.XposedAppsActivity;
import wings.v.core.Haptics;
import wings.v.core.RuStoreRecommendedAppsAsset;
import wings.v.core.XposedModulePrefs;
import wings.v.databinding.FragmentAppsBinding;

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
public class XposedAppsFragment extends Fragment {

    private static final String ARG_MODE = "mode";
    private static final String STATE_SEARCH_QUERY = "xposed_apps_search_query";
    private static final String STATE_SEARCH_VISIBLE = "xposed_apps_search_visible";
    private static final String STATE_APP_TYPE_FILTER = "xposed_apps_type_filter";
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
    private AppSearchAdapter adapter;
    private AppSearchAdapter searchAdapter;
    private String mode = XposedAppsActivity.MODE_TARGET_APPS;
    private String searchQuery = "";
    private boolean searchOverlayVisible;
    private boolean searchBarHidden;
    private String activeAppTypeFilter = FILTER_ALL;

    public static XposedAppsFragment create(String mode) {
        XposedAppsFragment fragment = new XposedAppsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_MODE, mode);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            mode = args.getString(ARG_MODE, XposedAppsActivity.MODE_TARGET_APPS);
        }
        if (savedInstanceState != null) {
            searchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "");
            searchOverlayVisible = savedInstanceState.getBoolean(STATE_SEARCH_VISIBLE, false);
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

        adapter = new AppSearchAdapter(this::onPackageToggled);
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
                }
            }
        );

        binding.searchBarContainer.setOnClickListener(v -> showSearchOverlay(true));
        binding.inputAppSearch.setOnClickListener(v -> showSearchOverlay(true));
        binding.inputAppSearch.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showSearchOverlay(true);
            }
        });
        binding.inputAppSearch.setOnEditorActionListener((v, actionId, event) -> {
            boolean searchAction =
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null &&
                    event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                    event.getAction() == KeyEvent.ACTION_DOWN);
            if (!searchAction) {
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
                    searchQuery = s != null ? s.toString() : "";
                    updateSearchResults();
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
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SEARCH_QUERY, searchQuery);
        outState.putBoolean(STATE_SEARCH_VISIBLE, searchOverlayVisible);
        outState.putString(STATE_APP_TYPE_FILTER, activeAppTypeFilter);
    }

    @Override
    public void onDestroyView() {
        binding.recyclerApps.setAdapter(null);
        binding.recyclerSearchResults.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void onPackageToggled(String packageName, boolean enabled, View sourceView) {
        Context context = requireContext();
        String prefsKey = getPrefsKey();
        boolean recommendedPackage = XposedAppsActivity.MODE_TARGET_APPS.equals(mode)
            ? RuStoreRecommendedAppsAsset.getApps(context).containsKey(packageName)
            : XposedAppsActivity.MODE_HIDDEN_VPN_APPS.equals(mode) &&
              XposedModulePrefs.getRecommendedHiddenVpnPackages(context).contains(packageName);
        if (recommendedPackage) {
            if (enabled) {
                XposedModulePrefs.setRecommendedPackageDismissed(context, prefsKey, packageName, false);
            } else {
                XposedModulePrefs.setRecommendedPackageDismissed(context, prefsKey, packageName, true);
            }
        }
        XposedModulePrefs.setPackageEnabled(context, prefsKey, packageName, enabled);
        if (enabled) {
            enabledPackages.add(packageName);
        } else {
            enabledPackages.remove(packageName);
        }
        adapter.replaceItems(filterEntries("", false), enabledPackages);
        updateSearchResults();
        updateEmptyStates();
        Haptics.softSliderStep(sourceView);
    }

    private void loadApplications() {
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
            String prefsKey = getPrefsKey();
            XposedModulePrefs.syncRecommendedPackages(appContext, prefsKey, installedPackages);
            Set<String> selected = XposedModulePrefs.getPackageSet(appContext, prefsKey);
            mainHandler.post(() -> {
                if (!isAdded() || binding == null) {
                    return;
                }
                binding.progressApps.setVisibility(View.GONE);
                appEntries.clear();
                appEntries.addAll(entries);
                enabledPackages.clear();
                enabledPackages.addAll(selected);
                adapter.replaceItems(filterEntries("", false), enabledPackages);
                updateSearchResults();
                updateEmptyStates();
                updateScrollToTopButton();
            });
        });
    }

    private void updateSearchResults() {
        if (binding == null || searchAdapter == null) {
            return;
        }
        searchAdapter.replaceItems(filterEntries(searchQuery, true), enabledPackages);
        updateEmptyStates();
    }

    private void updateEmptyStates() {
        if (binding == null) {
            return;
        }
        if (binding.progressApps.getVisibility() == View.VISIBLE) {
            binding.textAppsEmpty.setVisibility(View.GONE);
        } else if (filterEntries("", false).isEmpty()) {
            binding.textAppsEmpty.setText(appEntries.isEmpty() ? R.string.apps_empty : R.string.apps_no_results);
            binding.textAppsEmpty.setVisibility(View.VISIBLE);
        } else {
            binding.textAppsEmpty.setVisibility(View.GONE);
        }
        if (!searchOverlayVisible) {
            binding.textSearchEmpty.setVisibility(View.GONE);
            return;
        }
        if (filterEntries(searchQuery, true).isEmpty()) {
            binding.textSearchEmpty.setText(appEntries.isEmpty() ? R.string.apps_empty : R.string.apps_no_results);
            binding.textSearchEmpty.setVisibility(View.VISIBLE);
        } else {
            binding.textSearchEmpty.setVisibility(View.GONE);
        }
    }

    private List<AppRoutingEntry> filterEntries(String query, boolean includeQuery) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        List<AppRoutingEntry> filteredEntries = new ArrayList<>();
        for (AppRoutingEntry entry : appEntries) {
            if (!matchesAppTypeFilter(entry)) {
                continue;
            }
            if (
                !includeQuery ||
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
        if (XposedAppsActivity.MODE_TARGET_APPS.equals(mode) || XposedAppsActivity.MODE_HIDDEN_VPN_APPS.equals(mode)) {
            addFilterChip(FILTER_RECOMMENDED, getString(R.string.apps_filter_recommended));
        }
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
            renderAppTypeFilters();
            adapter.replaceItems(filterEntries("", false), enabledPackages);
            updateSearchResults();
            updateEmptyStates();
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
        if (requestKeyboard) {
            binding.inputAppSearch.requestFocus();
            showKeyboard();
        }
    }

    private void hideSearchOverlay(boolean clearFocus) {
        if (binding == null) {
            return;
        }
        searchOverlayVisible = false;
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
        boolean shouldShow = !searchOverlayVisible && searchBarHidden && binding.recyclerApps.canScrollVertically(-1);
        binding.buttonScrollToTop.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    private float dpToPx(int value) {
        return value * requireContext().getResources().getDisplayMetrics().density;
    }

    private int dpToPxInt(int value) {
        return Math.round(dpToPx(value));
    }

    private void showKeyboard() {
        InputMethodManager inputMethodManager = requireContext().getSystemService(InputMethodManager.class);
        if (inputMethodManager != null && binding != null) {
            binding.inputAppSearch.post(() ->
                inputMethodManager.showSoftInput(binding.inputAppSearch, InputMethodManager.SHOW_IMPLICIT)
            );
        }
    }

    private void hideKeyboard() {
        InputMethodManager inputMethodManager = requireContext().getSystemService(InputMethodManager.class);
        if (inputMethodManager != null && binding != null) {
            inputMethodManager.hideSoftInputFromWindow(binding.inputAppSearch.getWindowToken(), 0);
        }
    }

    private List<AppRoutingEntry> queryInstalledApps(Context context) {
        PackageManager packageManager = context.getPackageManager();
        List<ApplicationInfo> installedApplications;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            installedApplications = packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA)
            );
        } else {
            installedApplications = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
        }
        List<AppRoutingEntry> entries = new ArrayList<>(installedApplications.size());
        Set<String> recommendedPackages = XposedAppsActivity.MODE_TARGET_APPS.equals(mode)
            ? RuStoreRecommendedAppsAsset.getPackageNames(context)
            : XposedAppsActivity.MODE_HIDDEN_VPN_APPS.equals(mode)
                ? XposedModulePrefs.getRecommendedHiddenVpnPackages(context)
                : java.util.Collections.emptySet();
        for (ApplicationInfo applicationInfo : installedApplications) {
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

    private String getPrefsKey() {
        return XposedAppsActivity.MODE_HIDDEN_VPN_APPS.equals(mode)
            ? XposedModulePrefs.KEY_HIDDEN_VPN_PACKAGES
            : XposedModulePrefs.KEY_TARGET_PACKAGES;
    }
}
