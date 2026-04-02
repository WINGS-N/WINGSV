package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.widget.ViewPager2;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.PermissionUtils;
import wings.v.core.RootUtils;
import wings.v.core.WingsImportParser;
import wings.v.databinding.ActivityMainBinding;
import wings.v.service.ProxyTunnelService;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private MainPagerAdapter pagerAdapter;
    private int currentTabId = R.id.menu_home;
    private boolean hasSharingTab;
    private boolean pendingStartAfterOnboarding;
    private boolean pageSelectionReady;
    private final ExecutorService rootStateExecutor = Executors.newSingleThreadExecutor();
    private volatile int rootStateRefreshGeneration;

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
        hasSharingTab = AppPrefs.isRootAccessGranted(this) || AppPrefs.hasRootRuntimeState(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (!hasSharingTab) {
            binding.bottomTab.removeMenuItem(R.id.menu_sharing);
        }
        pagerAdapter = new MainPagerAdapter(this, hasSharingTab);
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
                binding.mainPager.setCurrentItem(position, true);
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
        if (!hasSharingTab && initialTabId == R.id.menu_sharing) {
            initialTabId = R.id.menu_home;
        }
        currentTabId = initialTabId;
        binding.mainPager.setCurrentItem(positionForTabId(initialTabId), false);
        binding.bottomTab.setSelectedItem(initialTabId);
        updateTitle(initialTabId);
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
        refreshRootStateAsync();
        maybeRecoverRuntimeState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
            String rootUnavailableReason = RootUtils.getRootModeUnavailableReason(this, true);
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
        if (tabId == R.id.menu_apps) {
            binding.toolbarLayout.setTitle(getString(R.string.apps));
            return;
        }
        if (tabId == R.id.menu_sharing) {
            binding.toolbarLayout.setTitle(getString(R.string.sharing));
            return;
        }
        if (tabId == R.id.menu_settings) {
            binding.toolbarLayout.setTitle(getString(R.string.settings));
        } else {
            binding.toolbarLayout.setTitle(getString(R.string.home));
        }
    }

    private int positionForTabId(int tabId) {
        if (tabId == R.id.menu_apps) {
            return MainPagerAdapter.PAGE_APPS;
        }
        if (tabId == R.id.menu_sharing && hasSharingTab) {
            return MainPagerAdapter.PAGE_SHARING;
        }
        if (tabId == R.id.menu_settings) {
            return hasSharingTab ? MainPagerAdapter.PAGE_SETTINGS : MainPagerAdapter.PAGE_SHARING;
        }
        return MainPagerAdapter.PAGE_HOME;
    }

    private int tabIdForPosition(int position) {
        if (position == MainPagerAdapter.PAGE_APPS) {
            return R.id.menu_apps;
        }
        if (hasSharingTab && position == MainPagerAdapter.PAGE_SHARING) {
            return R.id.menu_sharing;
        }
        if (!hasSharingTab && position == MainPagerAdapter.PAGE_SHARING) {
            return R.id.menu_settings;
        }
        if (position == MainPagerAdapter.PAGE_SETTINGS) {
            return R.id.menu_settings;
        }
        return R.id.menu_home;
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
                boolean nextHasSharingTab = AppPrefs.isRootAccessGranted(this)
                        || AppPrefs.hasRootRuntimeState(this);
                if (hasSharingTab != nextHasSharingTab) {
                    recreate();
                }
            });
        });
    }

    private void maybeRecoverRuntimeState() {
        ProxyTunnelService.requestRuntimeSyncIfNeeded(this);
    }

    private void handleImportIntent(Intent intent) {
        if (intent == null || intent.getDataString() == null) {
            return;
        }

        String rawData = intent.getDataString();
        if (TextUtils.isEmpty(rawData) || !rawData.startsWith("wingsv://")) {
            return;
        }

        try {
            AppPrefs.applyImportedConfig(this, WingsImportParser.parseFromText(rawData));
            Toast.makeText(this, R.string.clipboard_import_success, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.clipboard_import_invalid, Toast.LENGTH_SHORT).show();
        }
    }
}
