package wings.v.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.DropDownPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import wings.v.R;
import wings.v.XposedAppsActivity;
import wings.v.XposedRequestHistoryActivity;
import wings.v.core.Haptics;
import wings.v.core.XposedAttackStatsStore;
import wings.v.core.XposedModulePrefs;
import wings.v.core.XposedSecurityScore;
import wings.v.receiver.XposedStatsReceiver;

@SuppressWarnings("PMD.NullAssignment")
public class XposedSettingsFragment extends PreferenceFragmentCompat {

    private static final long PROCFS_ROW_ANIMATION_DURATION_MS = 180L;
    private static final long OVERVIEW_REFRESH_DEBOUNCE_MS = 400L;
    private static final String KEY_SECURITY_OVERVIEW = "pref_xposed_security_overview";
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangeListener;
    private final ExecutorService overviewExecutor = Executors.newSingleThreadExecutor();
    private final Handler overviewHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean overviewLoadInFlight = new AtomicBoolean(false);
    private final AtomicBoolean overviewRequeued = new AtomicBoolean(false);
    private final Runnable overviewRefreshRunnable = this::startOverviewLoad;
    private final BroadcastReceiver statsUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && XposedStatsReceiver.ACTION_STATS_UPDATED.equals(intent.getAction())) {
                scheduleOverviewRefresh();
            }
        }
    };

    @Nullable
    private Boolean lastProcfsHookModeVisible;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName(XposedModulePrefs.PREFS_NAME);
        XposedModulePrefs.ensureDefaults(requireContext());
        setPreferencesFromResource(R.xml.xposed_preferences, rootKey);
        configurePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerPreferencesListener();
        updatePackageSummaries();
        registerStatsReceiver();
        scheduleOverviewRefresh();
        XposedModulePrefs.export(requireContext());
    }

    @Override
    public void onPause() {
        unregisterPreferencesListener();
        unregisterStatsReceiver();
        XposedModulePrefs.export(requireContext());
        super.onPause();
    }

    private void configurePreferences() {
        bindSwitchHaptics(XposedModulePrefs.KEY_ENABLED);
        bindSwitchHaptics(XposedModulePrefs.KEY_ALL_APPS);
        bindSwitchHaptics(XposedModulePrefs.KEY_NATIVE_HOOK_ENABLED);
        bindSwitchHaptics(XposedModulePrefs.KEY_INLINE_HOOKS_ENABLED);
        bindSwitchHaptics(XposedModulePrefs.KEY_HIDE_VPN_APPS);
        bindDropDownPreference(XposedModulePrefs.KEY_PROCFS_HOOK_MODE);
        bindDropDownPreference(XposedModulePrefs.KEY_ICMP_SPOOFING_MODE);
        bindSecurityOverview();
        bindPackagePicker(XposedModulePrefs.KEY_TARGET_PACKAGES, XposedAppsActivity.MODE_TARGET_APPS);
        bindPackagePicker(XposedModulePrefs.KEY_HIDDEN_VPN_PACKAGES, XposedAppsActivity.MODE_HIDDEN_VPN_APPS);
        updatePackageSummaries();
        updatePreferenceEnabledState();
        scheduleOverviewRefresh();
    }

    private void bindSwitchHaptics(String key) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSliderStep(getListView() != null ? getListView() : requireView());
            return true;
        });
    }

    private void bindPackagePicker(String key, String mode) {
        Preference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceClickListener(clickedPreference -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            startActivity(XposedAppsActivity.createIntent(requireContext(), mode));
            return true;
        });
    }

    private void bindDropDownPreference(String key) {
        DropDownPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummaryProvider(DropDownPreference.SimpleSummaryProvider.getInstance());
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            Haptics.softSelection(getListView() != null ? getListView() : requireView());
            return true;
        });
    }

    private void bindSecurityOverview() {
        XposedSecurityOverviewPreference preference = findPreference(KEY_SECURITY_OVERVIEW);
        if (preference == null) {
            return;
        }
        preference.setOnHistoryClickListener(v -> {
            Haptics.softSelection(v);
            startActivity(XposedRequestHistoryActivity.createIntent(requireContext()));
        });
    }

    private void updatePackageSummaries() {
        updatePackageSummary(XposedModulePrefs.KEY_TARGET_PACKAGES);
        updatePackageSummary(XposedModulePrefs.KEY_HIDDEN_VPN_PACKAGES);
    }

    private void updatePreferenceEnabledState() {
        SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
        if (preferences == null) {
            return;
        }
        boolean moduleEnabled = preferences.getBoolean(
            XposedModulePrefs.KEY_ENABLED,
            XposedModulePrefs.DEFAULT_ENABLED
        );
        boolean nativeHookEnabled = preferences.getBoolean(
            XposedModulePrefs.KEY_NATIVE_HOOK_ENABLED,
            XposedModulePrefs.DEFAULT_NATIVE_HOOK_ENABLED
        );
        boolean procfsHookModeVisible = moduleEnabled && nativeHookEnabled;
        setPreferenceEnabled(XposedModulePrefs.KEY_ALL_APPS, moduleEnabled);
        setPreferenceEnabled(XposedModulePrefs.KEY_TARGET_PACKAGES, moduleEnabled);
        setPreferenceEnabled(XposedModulePrefs.KEY_NATIVE_HOOK_ENABLED, moduleEnabled);
        setPreferenceEnabled(XposedModulePrefs.KEY_INLINE_HOOKS_ENABLED, moduleEnabled && nativeHookEnabled);
        applyProcfsHookModeVisibility(procfsHookModeVisible);
        setPreferenceEnabled(XposedModulePrefs.KEY_PROCFS_HOOK_MODE, procfsHookModeVisible);
        setPreferenceEnabled(XposedModulePrefs.KEY_ICMP_SPOOFING_MODE, moduleEnabled);
        setPreferenceEnabled(XposedModulePrefs.KEY_HIDE_VPN_APPS, moduleEnabled);
        setPreferenceEnabled(XposedModulePrefs.KEY_HIDDEN_VPN_PACKAGES, moduleEnabled);
    }

    private void setPreferenceEnabled(String key, boolean enabled) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setEnabled(enabled);
        }
    }

    private void setPreferenceVisible(String key, boolean visible) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setVisible(visible);
        }
    }

    private void applyProcfsHookModeVisibility(boolean visible) {
        Preference preference = findPreference(XposedModulePrefs.KEY_PROCFS_HOOK_MODE);
        if (preference == null) {
            return;
        }
        if (lastProcfsHookModeVisible == null) {
            lastProcfsHookModeVisible = visible;
            setPreferenceVisible(XposedModulePrefs.KEY_PROCFS_HOOK_MODE, visible);
            return;
        }
        if (lastProcfsHookModeVisible == visible) {
            return;
        }
        lastProcfsHookModeVisible = visible;
        animatePreferenceVisibility(preference, visible);
    }

    private void animatePreferenceVisibility(Preference preference, boolean visible) {
        RecyclerView recyclerView = getListView();
        if (recyclerView == null) {
            preference.setVisible(visible);
            return;
        }
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter)) {
            preference.setVisible(visible);
            return;
        }
        PreferenceGroupAdapter preferenceAdapter = (PreferenceGroupAdapter) adapter;
        if (visible) {
            preference.setVisible(true);
            recyclerView.post(() -> {
                int position = preferenceAdapter.getPreferenceAdapterPosition(preference);
                RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(position);
                if (viewHolder == null) {
                    return;
                }
                View itemView = viewHolder.itemView;
                itemView.setAlpha(0f);
                itemView.setTranslationY(-itemView.getHeight() * 0.12f);
                itemView.animate().alpha(1f).translationY(0f).setDuration(PROCFS_ROW_ANIMATION_DURATION_MS).start();
            });
            return;
        }
        int position = preferenceAdapter.getPreferenceAdapterPosition(preference);
        RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(position);
        if (viewHolder == null) {
            preference.setVisible(false);
            return;
        }
        View itemView = viewHolder.itemView;
        itemView
            .animate()
            .alpha(0f)
            .translationY(-itemView.getHeight() * 0.12f)
            .setDuration(PROCFS_ROW_ANIMATION_DURATION_MS)
            .withEndAction(() -> {
                preference.setVisible(false);
                itemView.setAlpha(1f);
                itemView.setTranslationY(0f);
            })
            .start();
    }

    private void updatePackageSummary(String key) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setSummary(XposedModulePrefs.buildPackagesSummary(requireContext(), key));
        }
    }

    private void registerPreferencesListener() {
        if (preferencesChangeListener != null) {
            return;
        }
        SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
        preferencesChangeListener = (sharedPreferences, key) -> {
            updatePackageSummaries();
            updatePreferenceEnabledState();
            scheduleOverviewRefresh();
            XposedModulePrefs.export(requireContext());
        };
        preferences.registerOnSharedPreferenceChangeListener(preferencesChangeListener);
    }

    private void unregisterPreferencesListener() {
        if (preferencesChangeListener == null) {
            return;
        }
        getPreferenceManager()
            .getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(preferencesChangeListener);
        preferencesChangeListener = null;
    }

    private void scheduleOverviewRefresh() {
        overviewHandler.removeCallbacks(overviewRefreshRunnable);
        overviewHandler.postDelayed(overviewRefreshRunnable, OVERVIEW_REFRESH_DEBOUNCE_MS);
    }

    private void startOverviewLoad() {
        if (!isAdded()) return;
        if (!overviewLoadInFlight.compareAndSet(false, true)) {
            overviewRequeued.set(true);
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        overviewExecutor.execute(() -> {
            final XposedSecurityScore.Snapshot snapshot = XposedSecurityScore.compute(appContext);
            final XposedAttackStatsStore.WeeklySummary summary = XposedAttackStatsStore.getWeeklySummary(appContext);
            overviewHandler.post(() -> applyOverview(snapshot, summary));
        });
    }

    private void applyOverview(XposedSecurityScore.Snapshot snapshot, XposedAttackStatsStore.WeeklySummary summary) {
        overviewLoadInFlight.set(false);
        if (!isAdded()) return;
        XposedSecurityOverviewPreference preference = findPreference(KEY_SECURITY_OVERVIEW);
        if (preference != null) {
            preference.bindState(snapshot, summary);
        }
        if (overviewRequeued.compareAndSet(true, false)) {
            scheduleOverviewRefresh();
        }
    }

    private void registerStatsReceiver() {
        IntentFilter filter = new IntentFilter(XposedStatsReceiver.ACTION_STATS_UPDATED);
        ContextCompat.registerReceiver(
            requireContext(),
            statsUpdateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    private void unregisterStatsReceiver() {
        try {
            requireContext().unregisterReceiver(statsUpdateReceiver);
        } catch (IllegalArgumentException ignored) {}
    }

    @Override
    public void onDestroy() {
        overviewHandler.removeCallbacks(overviewRefreshRunnable);
        overviewExecutor.shutdownNow();
        super.onDestroy();
    }
}
