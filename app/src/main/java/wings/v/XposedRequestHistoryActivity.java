package wings.v;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import wings.v.core.Haptics;
import wings.v.core.XposedAttackStatsStore;
import wings.v.databinding.ActivityXposedRequestHistoryBinding;
import wings.v.receiver.XposedStatsReceiver;
import wings.v.ui.XposedRequestHistoryAdapter;

public class XposedRequestHistoryActivity extends AppCompatActivity {

    private static final long REFRESH_DEBOUNCE_MS = 250L;

    private ActivityXposedRequestHistoryBinding binding;
    private XposedRequestHistoryAdapter adapter;

    private final ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean loadInFlight = new AtomicBoolean(false);
    private final AtomicBoolean loadRequeued = new AtomicBoolean(false);
    private boolean hasRenderedOnce;

    private final Runnable refreshRunnable = this::startLoad;

    private final BroadcastReceiver statsUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && XposedStatsReceiver.ACTION_STATS_UPDATED.equals(intent.getAction())) {
                scheduleRefresh();
            }
        }
    };

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, XposedRequestHistoryActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityXposedRequestHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ToolbarLayout toolbarLayout = binding.toolbarLayout;
        toolbarLayout.setShowNavigationButtonAsBack(true);

        binding.recyclerXposedHistory.setLayoutManager(new LinearLayoutManager(this));
        adapter = new XposedRequestHistoryAdapter(new ArrayList<>(), item -> {
            Haptics.softSelection(binding.recyclerXposedHistory);
            startActivity(XposedRequestHistoryDetailsActivity.createIntent(this, item.packageName));
        });
        binding.recyclerXposedHistory.setAdapter(adapter);
        startLoad();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerStatsReceiver();
        scheduleRefresh();
    }

    @Override
    protected void onPause() {
        unregisterReceiverSafe();
        mainHandler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        loaderExecutor.shutdownNow();
        super.onDestroy();
    }

    private void scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable);
        mainHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS);
    }

    private void startLoad() {
        if (binding == null || adapter == null) {
            return;
        }
        if (!loadInFlight.compareAndSet(false, true)) {
            loadRequeued.set(true);
            return;
        }
        if (!hasRenderedOnce) {
            binding.progressXposedHistory.setVisibility(View.VISIBLE);
            binding.recyclerXposedHistory.setVisibility(View.GONE);
            binding.textXposedHistoryEmpty.setVisibility(View.GONE);
        }
        final Context appContext = getApplicationContext();
        loaderExecutor.execute(() -> {
            final List<XposedRequestHistoryAdapter.Item> items = buildItems(appContext);
            mainHandler.post(() -> applyItems(items));
        });
    }

    private void applyItems(@NonNull List<XposedRequestHistoryAdapter.Item> items) {
        loadInFlight.set(false);
        if (binding == null || adapter == null) {
            return;
        }
        hasRenderedOnce = true;
        binding.progressXposedHistory.setVisibility(View.GONE);
        adapter.replaceItems(items);
        boolean isEmpty = items.isEmpty();
        binding.textXposedHistoryEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.recyclerXposedHistory.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        if (loadRequeued.compareAndSet(true, false)) {
            scheduleRefresh();
        }
    }

    @NonNull
    private List<XposedRequestHistoryAdapter.Item> buildItems(@NonNull Context context) {
        List<XposedAttackStatsStore.AppAttackSummary> summaries = XposedAttackStatsStore.getAppSummaries(context);
        List<XposedRequestHistoryAdapter.Item> items = new ArrayList<>(summaries.size());
        PackageManager packageManager = context.getPackageManager();
        DateFormat dateFormat = DateFormat.getDateTimeInstance();
        for (XposedAttackStatsStore.AppAttackSummary summary : summaries) {
            CharSequence label = summary.packageName;
            Drawable icon = packageManager.getDefaultActivityIcon();
            try {
                ApplicationInfo applicationInfo = packageManager.getApplicationInfo(summary.packageName, 0);
                label = packageManager.getApplicationLabel(applicationInfo);
                icon = packageManager.getApplicationIcon(applicationInfo);
            } catch (Exception ignored) {}
            items.add(
                new XposedRequestHistoryAdapter.Item(
                    summary.packageName,
                    String.valueOf(label),
                    icon,
                    getString(R.string.xposed_history_app_summary, summary.count),
                    dateFormat.format(new Date(summary.lastTimestampMs))
                )
            );
        }
        return items;
    }

    private void registerStatsReceiver() {
        IntentFilter filter = new IntentFilter(XposedStatsReceiver.ACTION_STATS_UPDATED);
        ContextCompat.registerReceiver(this, statsUpdateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterReceiverSafe() {
        try {
            unregisterReceiver(statsUpdateReceiver);
        } catch (IllegalArgumentException ignored) {}
    }
}
