package wings.v;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.MetricAffectingSpan;
import android.view.Menu;
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
import androidx.viewpager2.widget.ViewPager2;
import dev.oneuiproject.oneui.layout.Badge;
import dev.oneuiproject.oneui.qr.app.QrScanActivity;
import dev.oneuiproject.oneui.widget.BottomTabLayout;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AppPrefs;
import wings.v.core.AppUpdateManager;
import wings.v.core.BackendType;
import wings.v.core.GuardianImportGate;
import wings.v.core.Haptics;
import wings.v.core.ImportConfigSummary;
import wings.v.core.PermissionUtils;
import wings.v.core.RootUtils;
import wings.v.core.UpdateBadgeUtils;
import wings.v.core.WingsImportParser;
import wings.v.core.XrayStore;
import wings.v.databinding.ActivityMainBinding;
import wings.v.service.ProxyTunnelService;
import wings.v.vpnhotspot.bridge.SharingApiGuard;

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
    // Re-probe актуального состояния su периодически — Magisk/Kitsune может
    // отозвать grant между resume/pause, и UI должен это поймать. 30 секунд
    // — компромисс между ленью пользователя и стоимостью ProcessBuilder("su").
    private static final long ROOT_STATE_REPROBE_INTERVAL_MS = 30_000L;

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
    private String lastAppliedNavbarSignature = "";
    private boolean pendingStartAfterOnboarding;
    private boolean pageSelectionReady;
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;
    private final ExecutorService rootStateExecutor = Executors.newSingleThreadExecutor();
    private volatile int rootStateRefreshGeneration;
    private final Runnable rootStateReprobeRunnable = new Runnable() {
        @Override
        public void run() {
            if (binding == null) {
                return;
            }
            refreshRootStateAsync();
            navigationHandler.postDelayed(this, ROOT_STATE_REPROBE_INTERVAL_MS);
        }
    };
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

    private final ActivityResultLauncher<Intent> qrScanLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> handleQrScanResult(result.getResultCode(), result.getData())
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppPrefs.ensureDefaults(this);
        appUpdateManager = AppUpdateManager.getInstance(this);
        // The profiles screen is now backend-aware (its own backend dropdown swaps
        // the shown list), so the tab is available for every backend.
        hasProfilesTab = true;
        hasSharingTab = AppPrefs.isRootModeEnabled(this) && SharingApiGuard.isSupported();

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bottomTab = binding.bottomTab;
        configureToolbar();
        configureBackHandling();
        inflateBottomTabMenu();
        pagerAdapter = new MainPagerAdapter(this, resolveOrderedPagerItemIds());
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
                    updateTitle();
                    if (changed) {
                        invalidateOptionsMenu();
                    }
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
        java.util.Set<Long> visiblePagerItems = new java.util.LinkedHashSet<>(resolveOrderedPagerItemIds());
        long initialPagerItem = pagerItemForTabId(initialTabId);
        if (!visiblePagerItems.contains(initialPagerItem)) {
            initialTabId = R.id.menu_home;
        }
        currentTabId = initialTabId;
        binding.mainPager.setCurrentItem(positionForTabId(initialTabId), false);
        bottomTab().setSelectedItem(initialTabId);
        updateTitle();
        applyUpdateBadgeState(appUpdateManager.getState());
        lastAppliedNavbarSignature = computeNavbarSignature(hasProfilesTab, hasSharingTab);
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
        startRootStateReprobe();
        maybeRecoverRuntimeState();
    }

    @Override
    protected void onPause() {
        stopNavigationRefresh();
        stopRootStateReprobe();
        super.onPause();
    }

    private void startRootStateReprobe() {
        navigationHandler.removeCallbacks(rootStateReprobeRunnable);
        navigationHandler.postDelayed(rootStateReprobeRunnable, ROOT_STATE_REPROBE_INTERVAL_MS);
    }

    private void stopRootStateReprobe() {
        navigationHandler.removeCallbacks(rootStateReprobeRunnable);
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerPreferencesListener();
        appUpdateManager.registerListener(updateStateListener);
        appUpdateManager.checkForUpdatesIfStale();
        wings.v.guardian.GuardianStateBroadcast.register(guardianStateListener);
        // Guardian foreground/background is driven app-wide by WingsApplication's
        // started-activity counter (onAppForeground/onAppBackground), so it stays
        // connected across all activities instead of only MainActivity.
    }

    @Override
    protected void onStop() {
        unregisterPreferencesListener();
        appUpdateManager.unregisterListener(updateStateListener);
        wings.v.guardian.GuardianStateBroadcast.unregister(guardianStateListener);
        super.onStop();
    }

    private final wings.v.guardian.GuardianStateBroadcast.Listener guardianStateListener = (connected, host) -> {
        if (binding == null) {
            return;
        }
        if (connected && host != null && !host.isEmpty()) {
            binding.toolbarLayout.setSubtitle(getString(R.string.guardian_main_chip_label, host));
        } else {
            binding.toolbarLayout.setSubtitle(null);
        }
    };

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
            ContextCompat.startForegroundService(
                this,
                ProxyTunnelService.createStartIntent(this, XrayStore.getBackendType(this))
            );
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

    private void updateTitle() {
        binding.toolbarLayout.setTitle(buildToolbarTitle());
    }

    private CharSequence buildToolbarTitle() {
        String appName = getString(R.string.app_name);
        Typeface sharpSansBold = ResourcesCompat.getFont(this, R.font.samsungsharpsans_bold);
        if (sharpSansBold == null) {
            return appName;
        }
        SpannableString spannable = new SpannableString(appName);
        spannable.setSpan(
            new ToolbarTitleTypefaceSpan(sharpSansBold),
            0,
            appName.length(),
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

    private long pagerItemForTabId(int tabId) {
        return NavbarItems.pagerItemIdForKey(NavbarItems.keyForMenuItemId(tabId));
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
        updateTitle();
    }

    private void configureToolbar() {
        binding.toolbarLayout.setShowNavigationButton(false);
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        // ToolbarLayout calls setSupportActionBar() on its inner toolbar, so the
        // standard options-menu callback is the lifecycle-stable place to add the
        // QR scan button — direct toolbar.inflateMenu() gets wiped on the first
        // invalidateOptionsMenu() that AppCompat issues during setup.
        getMenuInflater().inflate(R.menu.menu_main_toolbar, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(@NonNull Menu menu) {
        MenuItem qrItem = menu.findItem(R.id.menu_qr_scan);
        if (qrItem != null) {
            // Сканер показываем только на «Главной» и «Профилях» — на вкладках
            // sharing/settings место в тулбаре отдаём под их собственные элементы.
            qrItem.setVisible(currentTabId == R.id.menu_home || currentTabId == R.id.menu_profiles);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_qr_scan) {
            launchQrScanner();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void launchQrScanner() {
        // QrScanActivity self-manages camera permission via its own launcher,
        // so we never request CAMERA on app start — only after the user taps this.
        // No regex/prefix filter: WingsImportParser.parseFromText accepts wingsv://,
        // vless://, AmneziaWG quick-config text, and subscription URLs — let it judge.
        Intent intent = QrScanActivity.Companion.createIntent(this, getString(R.string.qr_scan_title));
        try {
            qrScanLauncher.launch(intent);
        } catch (android.content.ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.qr_scan_camera_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void handleQrScanResult(int resultCode, @Nullable Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        String scanned = data.getStringExtra(QrScanActivity.EXTRA_QR_SCANNER_RESULT);
        if (TextUtils.isEmpty(scanned)) {
            return;
        }
        String trimmed = scanned.trim();
        WingsImportParser.ImportedConfig parsed;
        try {
            parsed = WingsImportParser.parseFromText(trimmed);
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.clipboard_import_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        promptToImport(trimmed, parsed);
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
            updateTitle();
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
        java.util.List<NavbarItems.Item> items = NavbarItems.resolveVisibleItems(this, hasProfilesTab, hasSharingTab);
        if (items.isEmpty()) {
            bottomTab().inflateMenu(R.menu.menu_bottom_tab_home, bottomTabClickListener);
        } else {
            for (NavbarItems.Item item : items) {
                bottomTab().inflateMenu(item.menuResId, bottomTabClickListener);
            }
        }
        applyUpdateBadgeState(appUpdateManager != null ? appUpdateManager.getState() : null);
    }

    private java.util.List<Long> resolveOrderedPagerItemIds() {
        java.util.List<NavbarItems.Item> items = NavbarItems.resolveVisibleItems(this, hasProfilesTab, hasSharingTab);
        java.util.List<Long> result = new java.util.ArrayList<>(items.size());
        for (NavbarItems.Item item : items) {
            result.add(item.pagerItemId);
        }
        if (result.isEmpty()) {
            result.add(MainPagerAdapter.ITEM_HOME);
        }
        return result;
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
        // Раньше тут был ранний выход когда cache=false: «нечего перепроверять,
        // root и так нет». Это пропускало обратное направление — пользователь
        // включил Magisk grant в фоне, мы его не видели до следующего onResume.
        // Теперь probe идёт всегда; refreshRootAccessState внутри сама поймёт
        // и cache→true (user granted), и cache→false (revoke), и вызовет
        // handleRootRevoked для UI cleanup.
        rootStateRefreshGeneration++;
        final int generation = rootStateRefreshGeneration;
        rootStateExecutor.execute(() -> {
            Context appContext = getApplicationContext();
            RootUtils.quickRefreshRootAccessState(appContext);
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
        boolean nextHasProfilesTab = true;
        boolean nextHasSharingTab = AppPrefs.isRootModeEnabled(this) && SharingApiGuard.isSupported();
        String navbarSignature = computeNavbarSignature(nextHasProfilesTab, nextHasSharingTab);
        if (
            hasProfilesTab != nextHasProfilesTab ||
            hasSharingTab != nextHasSharingTab ||
            !navbarSignature.equals(lastAppliedNavbarSignature)
        ) {
            rebuildNavigationStateInPlace(currentTabId, nextHasProfilesTab, nextHasSharingTab);
            lastAppliedNavbarSignature = navbarSignature;
        }
    }

    private String computeNavbarSignature(boolean profilesTab, boolean sharingTab) {
        StringBuilder builder = new StringBuilder();
        builder.append(profilesTab).append('|').append(sharingTab);
        for (NavbarItems.Item item : NavbarItems.resolveVisibleItems(this, profilesTab, sharingTab)) {
            builder.append('|').append(item.key);
        }
        return builder.toString();
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
        pagerAdapter = new MainPagerAdapter(this, resolveOrderedPagerItemIds());
        binding.mainPager.setAdapter(pagerAdapter);
        binding.mainPager.setOffscreenPageLimit(pagerAdapter.getPageCount());
        inflateBottomTabMenu();

        currentTabId = resolvedTabId;
        binding.mainPager.setCurrentItem(positionForTabId(resolvedTabId), false);
        bottomTab().setSelectedItem(resolvedTabId);
        bottomTab().refresh(false);
        bottomTab().setVisibility(View.VISIBLE);
        updateTitle();
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
        if (intent == null) {
            return;
        }

        String rawData = extractImportLink(intent);
        if (TextUtils.isEmpty(rawData)) {
            return;
        }

        WingsImportParser.ImportedConfig parsed;
        try {
            parsed = WingsImportParser.parseFromText(rawData);
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.clipboard_import_invalid, Toast.LENGTH_SHORT).show();
            intent.setData(null);
            return;
        }
        intent.setData(null);
        promptToImport(rawData, parsed);
    }

    private static final int REQUEST_GUARDIAN_CONFIRM = 4201;

    private String pendingImportRawData;
    private WingsImportParser.ImportedConfig pendingImport;

    private void promptToImport(@NonNull String rawData, @NonNull WingsImportParser.ImportedConfig parsed) {
        if (parsed.hasGuardian && !TextUtils.isEmpty(parsed.guardianAdminUsername)) {
            promptToImportGuardian(rawData, parsed);
            return;
        }
        String summary = ImportConfigSummary.forUser(this, parsed);
        new AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(summary)
            .setPositiveButton(R.string.import_confirm_apply, (dialog, which) -> beginApplyImport(rawData, parsed))
            .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                Toast.makeText(this, R.string.import_confirm_cancelled, Toast.LENGTH_SHORT).show()
            )
            .show();
    }

    private void promptToImportGuardian(@NonNull String rawData, @NonNull WingsImportParser.ImportedConfig parsed) {
        android.view.View view = android.view.LayoutInflater.from(this).inflate(
            R.layout.dialog_guardian_import_confirm,
            null
        );
        android.widget.TextView usernameView = view.findViewById(R.id.guardian_import_username);
        android.widget.TextView descriptionView = view.findViewById(R.id.guardian_import_description);
        android.widget.ImageView avatarView = view.findViewById(R.id.guardian_import_avatar_image);
        android.widget.ProgressBar loaderView = view.findViewById(R.id.guardian_import_avatar_loader);
        avatarView.setClipToOutline(true);
        avatarView.setOutlineProvider(
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View v, android.graphics.Outline outline) {
                    outline.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            }
        );
        usernameView.setText(parsed.guardianAdminUsername);
        descriptionView.setText(getString(R.string.guardian_import_description, parsed.guardianAdminUsername));
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setView(view)
            .setPositiveButton(R.string.import_confirm_apply, (d, which) -> beginApplyImport(rawData, parsed))
            .setNegativeButton(android.R.string.cancel, (d, which) ->
                Toast.makeText(this, R.string.import_confirm_cancelled, Toast.LENGTH_SHORT).show()
            )
            .create();
        prefetchGuardianAdminAvatar(parsed, dialog, loaderView, avatarView);
        dialog.show();
    }

    private void prefetchGuardianAdminAvatar(
        @NonNull WingsImportParser.ImportedConfig parsed,
        @NonNull androidx.appcompat.app.AlertDialog hostDialog,
        @NonNull android.widget.ProgressBar loaderView,
        @NonNull android.widget.ImageView avatarView
    ) {
        // Always try a fetch when an admin id is present: a missing /
        // outdated avatar_version (panel build that did not yet wire that
        // field, or a default placeholder admin) is no reason to hide the
        // spinner before we even hit the network. The server responds 404
        // for admins without a custom upload and we then fall back to the
        // placeholder background.
        if (parsed.guardianAdminId <= 0) {
            loaderView.setVisibility(View.GONE);
            return;
        }
        String url = buildGuardianAdminAvatarUrl(parsed);
        if (TextUtils.isEmpty(url)) {
            loaderView.setVisibility(View.GONE);
            return;
        }
        loaderView.setVisibility(View.VISIBLE);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        hostDialog.setOnDismissListener(d -> cancelled.set(true));
        Handler mainHandler = new Handler(Looper.getMainLooper());
        importExecutor.execute(() -> {
            android.graphics.Bitmap bitmap = downloadGuardianAvatar(url);
            if (cancelled.get()) {
                return;
            }
            mainHandler.post(() -> {
                if (cancelled.get()) {
                    return;
                }
                loaderView.setVisibility(View.GONE);
                if (bitmap != null) {
                    avatarView.setImageBitmap(bitmap);
                }
                // On miss (server returned 404 or network failed) keep the
                // shipped default avatar that the layout already loaded.
            });
        });
    }

    @Nullable
    private String buildGuardianAdminAvatarUrl(@NonNull WingsImportParser.ImportedConfig parsed) {
        String wsUrl = parsed.guardianWsUrl;
        if (TextUtils.isEmpty(wsUrl)) {
            return null;
        }
        String httpBase;
        if (wsUrl.startsWith("wss://")) {
            httpBase = "https://" + wsUrl.substring("wss://".length());
        } else if (wsUrl.startsWith("ws://")) {
            httpBase = "http://" + wsUrl.substring("ws://".length());
        } else {
            httpBase = wsUrl;
        }
        int pathStart = httpBase.indexOf('/', httpBase.indexOf("://") + 3);
        String origin = pathStart < 0 ? httpBase : httpBase.substring(0, pathStart);
        return origin + "/api/admin/avatars/" + parsed.guardianAdminId + ".png?v=" + parsed.guardianAdminAvatarVersion;
    }

    @Nullable
    private android.graphics.Bitmap downloadGuardianAvatar(@NonNull String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            try (java.io.InputStream in = conn.getInputStream()) {
                return android.graphics.BitmapFactory.decodeStream(in);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();

    private void beginApplyImport(@NonNull String rawData, @NonNull WingsImportParser.ImportedConfig parsed) {
        if (GuardianImportGate.needsConfirmation(parsed)) {
            pendingImportRawData = rawData;
            pendingImport = parsed;
            GuardianImportGate.launchFromActivity(this, REQUEST_GUARDIAN_CONFIRM);
            return;
        }
        applyImport(rawData, parsed);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_GUARDIAN_CONFIRM) {
            String rawData = pendingImportRawData;
            WingsImportParser.ImportedConfig parsed = pendingImport;
            pendingImportRawData = null;
            pendingImport = null;
            if (resultCode == RESULT_OK && rawData != null && parsed != null) {
                applyImport(rawData, parsed);
            } else {
                Toast.makeText(this, R.string.import_confirm_cancelled, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void applyImport(@NonNull String rawData, @NonNull WingsImportParser.ImportedConfig parsed) {
        AppPrefs.applyImportedConfig(this, parsed);
        requestReconnectAfterImport(rawData);
        Toast.makeText(this, R.string.clipboard_import_success, Toast.LENGTH_SHORT).show();
        boolean nextHasProfilesTab = true;
        boolean nextHasSharingTab = AppPrefs.isRootModeEnabled(this) && SharingApiGuard.isSupported();
        if (hasProfilesTab != nextHasProfilesTab || hasSharingTab != nextHasSharingTab) {
            rebuildNavigationStateInPlace(currentTabId, nextHasProfilesTab, nextHasSharingTab);
        }
    }

    @Nullable
    private String extractImportLink(@NonNull Intent intent) {
        Uri data = intent.getData();
        if (data == null) {
            return null;
        }

        String rawData = data.toString();
        if (isSupportedImportLink(rawData)) {
            return rawData;
        }

        String scheme = data.getScheme();
        String host = data.getHost();
        if (
            scheme == null ||
            host == null ||
            (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) ||
            !"v.wingsnet.org".equalsIgnoreCase(host)
        ) {
            return null;
        }

        String nestedLink = data.getQueryParameter("link");
        if (isSupportedImportLink(nestedLink)) {
            return nestedLink;
        }
        return null;
    }

    private boolean isSupportedImportLink(@Nullable String rawData) {
        if (TextUtils.isEmpty(rawData)) {
            return false;
        }
        return rawData.startsWith("wingsv://") || rawData.startsWith("vless://");
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
        AppPrefs.defaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(preferencesChangeListener);
    }

    private void unregisterPreferencesListener() {
        if (preferencesChangeListener == null) {
            return;
        }
        AppPrefs.defaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(preferencesChangeListener);
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
