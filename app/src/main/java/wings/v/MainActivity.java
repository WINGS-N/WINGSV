package wings.v;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.MetricAffectingSpan;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.widget.ViewPager2;
import dev.oneuiproject.oneui.layout.Badge;
import dev.oneuiproject.oneui.widget.BottomTabLayout;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AppPrefs;
import wings.v.core.AppUpdateManager;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.PermissionUtils;
import wings.v.core.RootUtils;
import wings.v.core.UpdateBadgeUtils;
import wings.v.core.WingsImportParser;
import wings.v.core.XrayStore;
import wings.v.databinding.ActivityMainBinding;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.AvoidUsingVolatile",
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.ExcessiveImports",
        "PMD.AtLeastOneConstructor",
        "PMD.GodClass",
        "PMD.CyclomaticComplexity",
        "PMD.TooManyMethods",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.ConfusingTernary",
    }
)
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_FORCE_CURRENT_TAB_ID = "wings.v.extra.FORCE_CURRENT_TAB_ID";
    private static final long BACK_EXIT_WINDOW_MS = 2_000L;
    private static final long NAVIGATION_REFRESH_INTERVAL_MS = 500L;

    private ActivityMainBinding binding;
    private final Handler navigationHandler = new Handler(Looper.getMainLooper());
    private final Runnable navigationRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (binding == null) {
                return;
            }
            syncNavigationState();
            navigationHandler.postDelayed(this, NAVIGATION_REFRESH_INTERVAL_MS);
        }
    };
    private BottomTabLayout bottomTab;
    private MainPagerAdapter pagerAdapter;
    private int currentTabId = R.id.menu_home;
    private boolean hasProfilesTab;
    private boolean hasSharingTab;
    private boolean pendingStartAfterOnboarding;
    private boolean pageSelectionReady;
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;
    private final ExecutorService rootStateExecutor = Executors.newSingleThreadExecutor();
    private volatile int rootStateRefreshGeneration;
    private AppUpdateManager appUpdateManager;
    private long lastBackPressedAtMs;
    private final AppUpdateManager.Listener updateStateListener = this::applyUpdateBadgeState;
    private final MenuItem.OnMenuItemClickListener bottomTabClickListener = item -> {
        int position = positionForTabId(item.getItemId());
        if (binding.mainPager.getCurrentItem() != position) {
            binding.mainPager.setCurrentItem(position, false);
        } else {
            Haptics.softSliderStep(bottomTab());
        }
        return true;
    };

    private final ActivityResultLauncher<Intent> firstLaunchLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (pendingStartAfterOnboarding && PermissionUtils.areCorePermissionsGranted(this)) {
                pendingStartAfterOnboarding = false;
                startTunnelService();
                return;
            }
            pendingStartAfterOnboarding = false;
        }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppPrefs.ensureDefaults(this);
        appUpdateManager = AppUpdateManager.getInstance(this);
        BackendType visibleBackendType = ProxyTunnelService.getVisibleBackendType(this);
        hasProfilesTab = visibleBackendType != null && visibleBackendType.usesXrayCore();
        hasSharingTab = AppPrefs.isRootModeEnabled(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bottomTab = binding.bottomTab;
        configureToolbar();
        configureBackHandling();
        inflateBottomTabMenu();
        pagerAdapter = new MainPagerAdapter(this, hasProfilesTab, hasSharingTab);
        binding.mainPager.setAdapter(pagerAdapter);
        binding.mainPager.setOffscreenPageLimit(pagerAdapter.getPageCount());
        binding.mainPager.registerOnPageChangeCallback(
            new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    int tabId = tabIdForPosition(position);
                    boolean changed = currentTabId != tabId;
                    currentTabId = tabId;
                    bottomTab().setSelectedItem(tabId);
                    updateTitle(tabId);
                    if (pageSelectionReady && changed) {
                        Haptics.softSliderStep(binding.mainPager);
                    }
                }
            }
        );

        int initialTabId;
        if (savedInstanceState == null) {
            initialTabId = R.id.menu_home;
        } else {
            initialTabId = savedInstanceState.getInt("current_tab_id", R.id.menu_home);
        }
        int forcedTabId = getIntent().getIntExtra(EXTRA_FORCE_CURRENT_TAB_ID, 0);
        if (forcedTabId != 0) {
            initialTabId = forcedTabId;
            getIntent().removeExtra(EXTRA_FORCE_CURRENT_TAB_ID);
        }
        if (!hasProfilesTab && initialTabId == R.id.menu_profiles) {
            initialTabId = R.id.menu_home;
        }
        if (!hasSharingTab && initialTabId == R.id.menu_sharing) {
            initialTabId = R.id.menu_home;
        }
        currentTabId = initialTabId;
        binding.mainPager.setCurrentItem(positionForTabId(initialTabId), false);
        bottomTab().setSelectedItem(initialTabId);
        updateTitle(initialTabId);
        applyUpdateBadgeState(appUpdateManager.getState());
        pageSelectionReady = true;

        handleImportIntent(getIntent());
        maybeLaunchStartupOnboarding();
        maybeRecoverRuntimeState();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current_tab_id", currentTabId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncNavigationState();
        startNavigationRefresh();
        refreshRootStateAsync();
        maybeRecoverRuntimeState();
    }

    @Override
    protected void onPause() {
        stopNavigationRefresh();
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerPreferencesListener();
        appUpdateManager.registerListener(updateStateListener);
        appUpdateManager.checkForUpdatesIfStale();
    }

    @Override
    protected void onStop() {
        unregisterPreferencesListener();
        appUpdateManager.unregisterListener(updateStateListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopNavigationRefresh();
        unregisterPreferencesListener();
        rootStateExecutor.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyForcedTab(intent);
        handleImportIntent(intent);
    }

    public void toggleTunnelRequested() {
        if (ProxyTunnelService.isActive()) {
            ContextCompat.startForegroundService(this, ProxyTunnelService.createStopIntent(this));
            return;
        }

        if (!ensureRootModeCanStart()) {
            return;
        }

        if (!PermissionUtils.areCorePermissionsGranted(this)) {
            pendingStartAfterOnboarding = true;
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_SHORT).show();
            if (!AppPrefs.isFirstLaunchExperienceSeen(this)) {
                firstLaunchLauncher.launch(FirstLaunchActivity.createIntent(this));
            } else {
                firstLaunchLauncher.launch(FirstLaunchActivity.createPermissionsIntent(this));
            }
            return;
        }

        startTunnelService();
    }

    private void startTunnelService() {
        try {
            ContextCompat.startForegroundService(this, ProxyTunnelService.createStartIntent(this));
        } catch (IllegalStateException ignored) {
            Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean ensureRootModeCanStart() {
        if (!AppPrefs.isRootModeEnabled(this)) {
            return true;
        }

        final String rootUnavailableReason = RootUtils.getRootModeUnavailableReason(
            this,
            XrayStore.getBackendType(this),
            true
        );
        if (!TextUtils.isEmpty(rootUnavailableReason)) {
            Toast.makeText(
                this,
                getString(R.string.root_mode_unavailable, rootUnavailableReason),
                Toast.LENGTH_SHORT
            ).show();
            return false;
        }

        final BackendType backendType = XrayStore.getBackendType(this);
        final boolean kernelWireGuardRequested =
            backendType != null && backendType.supportsKernelWireGuard() && AppPrefs.isKernelWireGuardEnabled(this);
        if (!kernelWireGuardRequested) {
            return true;
        }

        final String kernelUnavailableReason = RootUtils.getKernelWireGuardUnavailableReason(this, backendType, false);
        if (TextUtils.isEmpty(kernelUnavailableReason)) {
            return true;
        }

        Toast.makeText(
            this,
            getString(R.string.kernel_wireguard_unavailable, kernelUnavailableReason),
            Toast.LENGTH_SHORT
        ).show();
        return false;
    }

    private void updateTitle(int tabId) {
        String screenTitle;
        if (tabId == R.id.menu_profiles) {
            screenTitle = getString(R.string.xray_profiles_title);
        } else if (tabId == R.id.menu_apps) {
            screenTitle = getString(R.string.apps);
        } else if (tabId == R.id.menu_sharing) {
            screenTitle = getString(R.string.sharing);
        } else if (tabId == R.id.menu_settings) {
            screenTitle = getString(R.string.settings);
        } else {
            screenTitle = getString(R.string.home);
        }
        binding.toolbarLayout.setTitle(buildToolbarTitle(screenTitle));
    }

    private CharSequence buildToolbarTitle(@NonNull String screenTitle) {
        String appName = getString(R.string.app_name);
        String title = getString(R.string.main_toolbar_title_format, screenTitle);
        int appNameStart = title.indexOf(appName);
        if (appNameStart < 0) {
            return title;
        }
        Typeface sharpSansBold = ResourcesCompat.getFont(this, R.font.samsungsharpsans_bold);
        if (sharpSansBold == null) {
            return title;
        }
        SpannableString spannable = new SpannableString(title);
        spannable.setSpan(
            new ToolbarTitleTypefaceSpan(sharpSansBold),
            appNameStart,
            appNameStart + appName.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        return spannable;
    }

    private int positionForTabId(int tabId) {
        if (tabId == R.id.menu_profiles) {
            return pagerAdapter.positionForItem(MainPagerAdapter.ITEM_PROFILES);
        }
        if (tabId == R.id.menu_apps) {
            return pagerAdapter.positionForItem(MainPagerAdapter.ITEM_APPS);
        }
        if (tabId == R.id.menu_sharing && hasSharingTab) {
            return pagerAdapter.positionForItem(MainPagerAdapter.ITEM_SHARING);
        }
        if (tabId == R.id.menu_settings) {
            return pagerAdapter.positionForItem(MainPagerAdapter.ITEM_SETTINGS);
        }
        return pagerAdapter.positionForItem(MainPagerAdapter.ITEM_HOME);
    }

    private int tabIdForPosition(int position) {
        long itemId = pagerAdapter.getItemAt(position);
        if (itemId == MainPagerAdapter.ITEM_PROFILES) {
            return R.id.menu_profiles;
        }
        if (itemId == MainPagerAdapter.ITEM_APPS) {
            return R.id.menu_apps;
        }
        if (itemId == MainPagerAdapter.ITEM_SHARING) {
            return R.id.menu_sharing;
        }
        if (itemId == MainPagerAdapter.ITEM_SETTINGS) {
            return R.id.menu_settings;
        }
        return R.id.menu_home;
    }

    private void applyForcedTab(@Nullable Intent intent) {
        if (intent == null || binding == null || pagerAdapter == null) {
            return;
        }
        int forcedTabId = intent.getIntExtra(EXTRA_FORCE_CURRENT_TAB_ID, 0);
        if (forcedTabId == 0) {
            return;
        }
        if (!hasProfilesTab && forcedTabId == R.id.menu_profiles) {
            currentTabId = forcedTabId;
            return;
        }
        if (!hasSharingTab && forcedTabId == R.id.menu_sharing) {
            forcedTabId = R.id.menu_home;
        }
        intent.removeExtra(EXTRA_FORCE_CURRENT_TAB_ID);
        currentTabId = forcedTabId;
        binding.mainPager.setCurrentItem(positionForTabId(forcedTabId), false);
        bottomTab().setSelectedItem(forcedTabId);
        updateTitle(forcedTabId);
    }

    private void configureToolbar() {
        binding.toolbarLayout.setShowNavigationButton(false);
    }

    private void configureBackHandling() {
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    handleMainBackPressed();
                }
            }
        );
    }

    private void handleMainBackPressed() {
        if (binding == null || pagerAdapter == null) {
            finish();
            return;
        }
        if (currentTabId != R.id.menu_home) {
            currentTabId = R.id.menu_home;
            binding.mainPager.setCurrentItem(positionForTabId(R.id.menu_home), false);
            bottomTab().setSelectedItem(R.id.menu_home);
            updateTitle(R.id.menu_home);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPressedAtMs < BACK_EXIT_WINDOW_MS) {
            finish();
            return;
        }
        lastBackPressedAtMs = now;
        Toast.makeText(this, R.string.first_launch_back_to_exit, Toast.LENGTH_SHORT).show();
    }

    private void inflateBottomTabMenu() {
        int menuResId;
        if (hasProfilesTab && hasSharingTab) {
            menuResId = R.menu.menu_bottom_tabs_xray_sharing;
        } else if (hasProfilesTab) {
            menuResId = R.menu.menu_bottom_tabs_xray;
        } else if (hasSharingTab) {
            menuResId = R.menu.menu_bottom_tabs_sharing;
        } else {
            menuResId = R.menu.menu_bottom_tabs_default;
        }
        bottomTab().inflateMenu(menuResId, bottomTabClickListener);
        applyUpdateBadgeState(appUpdateManager != null ? appUpdateManager.getState() : null);
    }

    private void applyUpdateBadgeState(@Nullable AppUpdateManager.UpdateState state) {
        if (binding == null) {
            return;
        }
        bottomTab().setItemBadge(
            R.id.menu_settings,
            UpdateBadgeUtils.shouldShowUpdateBadge(state) ? Badge.DOT.INSTANCE : Badge.NONE.INSTANCE
        );
    }

    public void setBottomNavigationSuppressed(boolean suppressed) {
        if (binding == null) {
            return;
        }
        bottomTab().setVisibility(suppressed ? View.GONE : View.VISIBLE);
    }

    private void maybeLaunchStartupOnboarding() {
        if (!AppPrefs.isFirstLaunchExperienceSeen(this)) {
            if (AppPrefs.isOnboardingSeen(this)) {
                AppPrefs.markFirstLaunchExperienceSeen(this);
                return;
            }
            firstLaunchLauncher.launch(FirstLaunchActivity.createIntent(this));
            return;
        }
        if (PermissionUtils.shouldShowOnboarding(this)) {
            firstLaunchLauncher.launch(FirstLaunchActivity.createPermissionsIntent(this));
        }
    }

    private void refreshRootStateAsync() {
        if (!AppPrefs.isRootAccessGranted(this)) {
            return;
        }
        rootStateRefreshGeneration++;
        final int generation = rootStateRefreshGeneration;
        rootStateExecutor.execute(() -> {
            Context appContext = getApplicationContext();
            boolean granted = RootUtils.refreshRootAccessState(appContext);
            if (!granted) {
                AppPrefs.clearRootRuntimeState(appContext);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != rootStateRefreshGeneration) {
                    return;
                }
                syncNavigationState();
            });
        });
    }

    private void maybeRecoverRuntimeState() {
        ProxyTunnelService.requestRuntimeSyncIfNeeded(this);
    }

    private void startNavigationRefresh() {
        navigationHandler.removeCallbacks(navigationRefreshRunnable);
        navigationHandler.postDelayed(navigationRefreshRunnable, NAVIGATION_REFRESH_INTERVAL_MS);
    }

    private void stopNavigationRefresh() {
        navigationHandler.removeCallbacks(navigationRefreshRunnable);
    }

    private void syncNavigationState() {
        BackendType visibleBackendType = ProxyTunnelService.getVisibleBackendType(this);
        boolean nextHasProfilesTab = visibleBackendType != null && visibleBackendType.usesXrayCore();
        boolean nextHasSharingTab = AppPrefs.isRootModeEnabled(this);
        if (hasProfilesTab != nextHasProfilesTab || hasSharingTab != nextHasSharingTab) {
            rebuildNavigationStateInPlace(currentTabId, nextHasProfilesTab, nextHasSharingTab);
        }
    }

    private void rebuildNavigationStateInPlace(int targetTabId, boolean nextHasProfilesTab, boolean nextHasSharingTab) {
        if (binding == null) {
            return;
        }
        setBottomNavigationSuppressed(false);
        hasProfilesTab = nextHasProfilesTab;
        hasSharingTab = nextHasSharingTab;

        int resolvedTabId = targetTabId;
        if (!hasProfilesTab && resolvedTabId == R.id.menu_profiles) {
            resolvedTabId = R.id.menu_home;
        }
        if (!hasSharingTab && resolvedTabId == R.id.menu_sharing) {
            resolvedTabId = R.id.menu_home;
        }

        pageSelectionReady = false;
        replaceBottomTabLayout();
        pagerAdapter = new MainPagerAdapter(this, hasProfilesTab, hasSharingTab);
        binding.mainPager.setAdapter(pagerAdapter);
        binding.mainPager.setOffscreenPageLimit(pagerAdapter.getPageCount());
        inflateBottomTabMenu();

        currentTabId = resolvedTabId;
        binding.mainPager.setCurrentItem(positionForTabId(resolvedTabId), false);
        bottomTab().setSelectedItem(resolvedTabId);
        bottomTab().refresh(false);
        bottomTab().setVisibility(View.VISIBLE);
        updateTitle(resolvedTabId);
        pageSelectionReady = true;
    }

    private BottomTabLayout bottomTab() {
        if (bottomTab == null) {
            bottomTab = binding.bottomTab;
        }
        return bottomTab;
    }

    private void replaceBottomTabLayout() {
        if (binding == null || bottomTab() == null) {
            return;
        }
        BottomTabLayout currentBottomTab = bottomTab();
        ViewGroup parent = (ViewGroup) currentBottomTab.getParent();
        if (parent == null) {
            return;
        }
        int index = parent.indexOfChild(currentBottomTab);
        ViewGroup.LayoutParams layoutParams = currentBottomTab.getLayoutParams();
        int visibility = currentBottomTab.getVisibility();
        parent.removeView(currentBottomTab);

        BottomTabLayout replacement = (BottomTabLayout) getLayoutInflater().inflate(
            R.layout.view_main_bottom_tab,
            parent,
            false
        );
        replacement.setLayoutParams(layoutParams);
        replacement.setVisibility(visibility);
        parent.addView(replacement, index);
        bottomTab = replacement;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void handleImportIntent(Intent intent) {
        if (intent == null || intent.getDataString() == null) {
            return;
        }

        String rawData = intent.getDataString();
        if (TextUtils.isEmpty(rawData) || (!rawData.startsWith("wingsv://") && !rawData.startsWith("vless://"))) {
            return;
        }

        try {
            AppPrefs.applyImportedConfig(this, WingsImportParser.parseFromText(rawData));
            requestReconnectAfterImport(rawData);
            Toast.makeText(this, R.string.clipboard_import_success, Toast.LENGTH_SHORT).show();
            intent.setData(null);
            BackendType backendType = XrayStore.getBackendType(this);
            boolean nextHasProfilesTab = backendType != null && backendType.usesXrayCore();
            boolean nextHasSharingTab = AppPrefs.isRootModeEnabled(this);
            if (hasProfilesTab != nextHasProfilesTab || hasSharingTab != nextHasSharingTab) {
                rebuildNavigationStateInPlace(currentTabId, nextHasProfilesTab, nextHasSharingTab);
            }
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.clipboard_import_invalid, Toast.LENGTH_SHORT).show();
            intent.setData(null);
        }
    }

    private void requestReconnectAfterImport(@Nullable String importedText) {
        if (!ProxyTunnelService.isActive()) {
            return;
        }
        final String normalized = importedText == null ? "" : importedText.trim().toLowerCase(Locale.ROOT);
        final String reason = normalized.startsWith("vless://")
            ? "Imported vless configuration applied"
            : "Imported wingsv configuration applied";
        ProxyTunnelService.requestReconnect(this, reason);
    }

    private void registerPreferencesListener() {
        if (preferencesChangeListener != null) {
            return;
        }
        preferencesChangeListener = (sharedPreferences, key) -> {
            if (
                AppPrefs.KEY_BACKEND_TYPE.equals(key) ||
                AppPrefs.KEY_ROOT_MODE.equals(key) ||
                AppPrefs.KEY_ROOT_ACCESS_GRANTED.equals(key) ||
                AppPrefs.KEY_ROOT_RUNTIME_ACTIVE.equals(key) ||
                AppPrefs.KEY_ROOT_RUNTIME_TUNNEL.equals(key)
            ) {
                runOnUiThread(this::syncNavigationState);
            }
        };
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(
            preferencesChangeListener
        );
    }

    private void unregisterPreferencesListener() {
        if (preferencesChangeListener == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(
            preferencesChangeListener
        );
        preferencesChangeListener = null;
    }

    private static final class ToolbarTitleTypefaceSpan extends MetricAffectingSpan {

        private final Typeface typeface;

        private ToolbarTitleTypefaceSpan(@NonNull final Typeface typeface) {
            super();
            this.typeface = typeface;
        }

        @Override
        public void updateMeasureState(TextPaint textPaint) {
            apply(textPaint);
        }

        @Override
        public void updateDrawState(TextPaint textPaint) {
            apply(textPaint);
        }

        private void apply(@NonNull TextPaint textPaint) {
            textPaint.setTypeface(typeface);
        }
    }
}
