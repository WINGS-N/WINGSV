package wings.v;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.widget.ViewPager2;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.oneuiproject.oneui.layout.Badge;
import wings.v.core.AppUpdateManager;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.PermissionUtils;
import wings.v.core.RootUtils;
import wings.v.core.UpdateBadgeUtils;
import wings.v.core.WingsImportParser;
import wings.v.core.XrayStore;
import wings.v.databinding.ActivityMainBinding;
import wings.v.service.ProxyTunnelService;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_FORCE_CURRENT_TAB_ID = "wings.v.extra.FORCE_CURRENT_TAB_ID";

    private ActivityMainBinding binding;
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
    private final AppUpdateManager.Listener updateStateListener = this::applyUpdateBadgeState;

    private final ActivityResultLauncher<Intent> onboardingLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (pendingStartAfterOnboarding
                                && PermissionUtils.areCorePermissionsGranted(this)) {
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
        hasProfilesTab = XrayStore.getBackendType(this) == BackendType.XRAY;
        hasSharingTab = AppPrefs.isRootAccessGranted(this) || AppPrefs.hasRootRuntimeState(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        configureToolbar();
        inflateBottomTabMenu();
        pagerAdapter = new MainPagerAdapter(this, hasProfilesTab, hasSharingTab);
        binding.mainPager.setAdapter(pagerAdapter);
        binding.mainPager.setOffscreenPageLimit(pagerAdapter.getPageCount());
        binding.mainPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                int tabId = tabIdForPosition(position);
                boolean changed = currentTabId != tabId;
                currentTabId = tabId;
                binding.bottomTab.setSelectedItem(tabId);
                updateTitle(tabId);
                if (pageSelectionReady && changed) {
                    Haptics.softSliderStep(binding.mainPager);
                }
            }
        });

        binding.bottomTab.setOnMenuItemClickListener(item -> {
            int position = positionForTabId(item.getItemId());
            if (binding.mainPager.getCurrentItem() != position) {
                binding.mainPager.setCurrentItem(position, false);
            } else {
                Haptics.softSliderStep(binding.bottomTab);
            }
            return true;
        });

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
        binding.bottomTab.setSelectedItem(initialTabId);
        updateTitle(initialTabId);
        applyUpdateBadgeState(appUpdateManager.getState());
        pageSelectionReady = true;

        handleImportIntent(getIntent());
        maybeShowOnboardingOnFirstLaunch();
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
        refreshRootStateAsync();
        maybeRecoverRuntimeState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerPreferencesListener();
        appUpdateManager.registerListener(updateStateListener);
        if (appUpdateManager.getState().status == AppUpdateManager.Status.IDLE) {
            appUpdateManager.checkForUpdates();
        }
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
        unregisterPreferencesListener();
        rootStateExecutor.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleImportIntent(intent);
    }

    public void toggleTunnelRequested() {
        if (ProxyTunnelService.isActive()) {
            ContextCompat.startForegroundService(this, ProxyTunnelService.createStopIntent(this));
            return;
        }

        if (AppPrefs.isRootModeEnabled(this)) {
            String rootUnavailableReason = RootUtils.getRootModeUnavailableReason(
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
                return;
            }
        }

        if (!PermissionUtils.areCorePermissionsGranted(this)) {
            pendingStartAfterOnboarding = true;
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_SHORT).show();
            onboardingLauncher.launch(PermissionOnboardingActivity.createIntent(this, true));
            return;
        }

        startTunnelService();
    }

    private void startTunnelService() {
        try {
            ContextCompat.startForegroundService(this, ProxyTunnelService.createStartIntent(this));
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_SHORT).show();
        }
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
        binding.toolbarLayout.setTitle(getString(R.string.main_toolbar_title_format, screenTitle));
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

    private void configureToolbar() {
        binding.toolbarLayout.setShowNavigationButton(false);
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
        binding.bottomTab.inflateMenu(menuResId, null);
        applyUpdateBadgeState(appUpdateManager != null ? appUpdateManager.getState() : null);
    }

    private void applyUpdateBadgeState(@Nullable AppUpdateManager.UpdateState state) {
        if (binding == null) {
            return;
        }
        binding.bottomTab.setItemBadge(
                R.id.menu_settings,
                UpdateBadgeUtils.shouldShowUpdateBadge(state) ? Badge.DOT.INSTANCE : Badge.NONE.INSTANCE
        );
    }

    public void setBottomNavigationSuppressed(boolean suppressed) {
        if (binding == null) {
            return;
        }
        binding.bottomTab.setVisibility(suppressed ? View.GONE : View.VISIBLE);
    }

    private void maybeShowOnboardingOnFirstLaunch() {
        if (PermissionUtils.shouldShowOnboarding(this)) {
            onboardingLauncher.launch(PermissionOnboardingActivity.createIntent(this, false));
        } else if (!AppPrefs.isOnboardingSeen(this)) {
            AppPrefs.markOnboardingSeen(this);
        }
    }

    private void refreshRootStateAsync() {
        if (!AppPrefs.isRootAccessGranted(this)
                && !AppPrefs.isRootModeEnabled(this)
                && !AppPrefs.hasRootRuntimeState(this)) {
            return;
        }
        final int generation = ++rootStateRefreshGeneration;
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

    private void syncNavigationState() {
        boolean nextHasProfilesTab = XrayStore.getBackendType(this) == BackendType.XRAY;
        boolean nextHasSharingTab = AppPrefs.isRootAccessGranted(this)
                || AppPrefs.hasRootRuntimeState(this);
        if (hasProfilesTab != nextHasProfilesTab || hasSharingTab != nextHasSharingTab) {
            restartPreservingTab(currentTabId);
        }
    }

    public void restartPreservingTab(int targetTabId) {
        Intent intent = getIntent();
        if (intent == null) {
            intent = new Intent(this, MainActivity.class);
        }
        intent.putExtra(EXTRA_FORCE_CURRENT_TAB_ID, targetTabId);
        setIntent(intent);
        recreate();
        overridePendingTransition(0, 0);
    }

    private void handleImportIntent(Intent intent) {
        if (intent == null || intent.getDataString() == null) {
            return;
        }

        String rawData = intent.getDataString();
        if (TextUtils.isEmpty(rawData)
                || (!rawData.startsWith("wingsv://") && !rawData.startsWith("vless://"))) {
            return;
        }

        try {
            AppPrefs.applyImportedConfig(this, WingsImportParser.parseFromText(rawData));
            Toast.makeText(this, R.string.clipboard_import_success, Toast.LENGTH_SHORT).show();
            intent.setData(null);
            boolean nextHasProfilesTab = XrayStore.getBackendType(this) == BackendType.XRAY;
            boolean nextHasSharingTab = AppPrefs.isRootAccessGranted(this)
                    || AppPrefs.hasRootRuntimeState(this);
            if (hasProfilesTab != nextHasProfilesTab || hasSharingTab != nextHasSharingTab) {
                restartPreservingTab(currentTabId);
            }
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.clipboard_import_invalid, Toast.LENGTH_SHORT).show();
            intent.setData(null);
        }
    }

    private void registerPreferencesListener() {
        if (preferencesChangeListener != null) {
            return;
        }
        preferencesChangeListener = (sharedPreferences, key) -> {
            if (AppPrefs.KEY_BACKEND_TYPE.equals(key)
                    || AppPrefs.KEY_ROOT_ACCESS_GRANTED.equals(key)
                    || AppPrefs.KEY_ROOT_RUNTIME_ACTIVE.equals(key)
                    || AppPrefs.KEY_ROOT_RUNTIME_TUNNEL.equals(key)) {
                runOnUiThread(this::syncNavigationState);
            }
        };
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(preferencesChangeListener);
    }

    private void unregisterPreferencesListener() {
        if (preferencesChangeListener == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(preferencesChangeListener);
        preferencesChangeListener = null;
    }
}
