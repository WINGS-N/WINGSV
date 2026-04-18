package wings.v;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
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
import wings.v.core.Haptics;
import wings.v.core.XposedAttackStatsStore;
import wings.v.databinding.ActivityXposedRequestHistoryBinding;
import wings.v.receiver.XposedStatsReceiver;
import wings.v.ui.XposedRequestHistoryAdapter;

public class XposedRequestHistoryActivity extends AppCompatActivity {

    private ActivityXposedRequestHistoryBinding binding;
    private XposedRequestHistoryAdapter adapter;

    private final BroadcastReceiver statsUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && XposedStatsReceiver.ACTION_STATS_UPDATED.equals(intent.getAction())) {
                refreshItems();
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
        refreshItems();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerStatsReceiver();
        refreshItems();
    }

    @Override
    protected void onPause() {
        unregisterReceiverSafe();
        super.onPause();
    }

    @NonNull
    private List<XposedRequestHistoryAdapter.Item> buildItems() {
        List<XposedAttackStatsStore.AppAttackSummary> summaries = XposedAttackStatsStore.getAppSummaries(this);
        List<XposedRequestHistoryAdapter.Item> items = new ArrayList<>(summaries.size());
        PackageManager packageManager = getPackageManager();
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
                    DateFormat.getDateTimeInstance().format(new Date(summary.lastTimestampMs))
                )
            );
        }
        return items;
    }

    private void refreshItems() {
        if (binding == null || adapter == null) {
            return;
        }
        List<XposedRequestHistoryAdapter.Item> items = buildItems();
        adapter.replaceItems(items);
        boolean isEmpty = items.isEmpty();
        binding.textXposedHistoryEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.recyclerXposedHistory.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
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
