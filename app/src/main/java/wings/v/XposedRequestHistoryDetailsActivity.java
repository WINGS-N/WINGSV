package wings.v;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import wings.v.core.DisplayDensityUtils;
import wings.v.core.Haptics;
import wings.v.core.XposedAttackStatsStore;
import wings.v.core.XposedAttackVector;
import wings.v.databinding.ActivityXposedRequestHistoryDetailsBinding;
import wings.v.receiver.XposedStatsReceiver;
import wings.v.ui.XposedRequestEventAdapter;

public class XposedRequestHistoryDetailsActivity extends AppCompatActivity {

    private static final String EXTRA_PACKAGE_NAME = "package_name";
    private static final String FILTER_ALL = "";
    private static final int PAGE_SIZE = 30;

    private ActivityXposedRequestHistoryDetailsBinding binding;
    private XposedRequestEventAdapter adapter;
    private String packageName = "";

    @NonNull
    private String activeVectorFilter = FILTER_ALL;

    private int currentPage = 0;

    private final BroadcastReceiver statsUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && XposedStatsReceiver.ACTION_STATS_UPDATED.equals(intent.getAction())) {
                refreshItems();
            }
        }
    };

    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull String packageName) {
        Intent intent = new Intent(context, XposedRequestHistoryDetailsActivity.class);
        intent.putExtra(EXTRA_PACKAGE_NAME, packageName);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityXposedRequestHistoryDetailsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        packageName = normalizePackageName(getIntent().getStringExtra(EXTRA_PACKAGE_NAME));

        ToolbarLayout toolbarLayout = binding.toolbarLayout;
        toolbarLayout.setShowNavigationButtonAsBack(true);
        toolbarLayout.setSubtitle(packageName);
        binding.recyclerXposedHistoryDetails.setLayoutManager(new LinearLayoutManager(this));
        adapter = new XposedRequestEventAdapter(new ArrayList<>());
        binding.recyclerXposedHistoryDetails.setAdapter(adapter);
        applyPackageHeader();
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

    private void applyPackageHeader() {
        if (binding == null || TextUtils.isEmpty(packageName)) {
            return;
        }
        PackageManager packageManager = getPackageManager();
        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(applicationInfo);
            if (!TextUtils.isEmpty(label)) {
                binding.toolbarLayout.setTitle(label);
            }
        } catch (Exception ignored) {}
    }

    private void refreshItems() {
        if (binding == null || adapter == null) {
            return;
        }
        renderFilterChips();
        List<XposedAttackStatsStore.AttackEvent> events = XposedAttackStatsStore.getEventsForPackage(
            this,
            packageName,
            activeVectorFilter
        );
        List<XposedRequestEventAdapter.Item> items = new ArrayList<>(events.size());
        DateFormat dateFormat = DateFormat.getDateTimeInstance();
        for (XposedAttackStatsStore.AttackEvent event : events) {
            String detail = formatEventDetail(event);
            items.add(
                new XposedRequestEventAdapter.Item(
                    getString(XposedAttackVector.getDetailLabelRes(event.vector)),
                    dateFormat.format(new Date(event.timestampMs)),
                    detail
                )
            );
        }
        int pageCount = Math.max(1, (int) Math.ceil(items.size() / (double) PAGE_SIZE));
        currentPage = Math.max(0, Math.min(currentPage, pageCount - 1));
        int fromIndex = currentPage * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, items.size());
        List<XposedRequestEventAdapter.Item> pageItems =
            fromIndex < toIndex ? new ArrayList<>(items.subList(fromIndex, toIndex)) : new ArrayList<>();
        adapter.replaceItems(pageItems);
        boolean isEmpty = items.isEmpty();
        binding.textXposedHistoryDetailsEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.recyclerXposedHistoryDetails.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        binding.rowXposedHistoryDetailsPagination.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        binding.textXposedHistoryDetailsPage.setText(
            getString(R.string.xposed_history_page_value, currentPage + 1, pageCount)
        );
        bindPaginationButton(binding.buttonXposedHistoryDetailsPrev, currentPage > 0, () -> {
            currentPage--;
            refreshItems();
        });
        bindPaginationButton(binding.buttonXposedHistoryDetailsNext, currentPage + 1 < pageCount, () -> {
            currentPage++;
            refreshItems();
        });
    }

    private void renderFilterChips() {
        if (binding == null) {
            return;
        }
        binding.groupXposedHistoryDetailsFilters.removeAllViews();
        addFilterChip(FILTER_ALL, getString(R.string.xposed_history_filter_all));
        for (String vector : XposedAttackStatsStore.getKnownVectors(this, packageName)) {
            addFilterChip(vector, getString(XposedAttackVector.getShortLabelRes(vector)));
        }
    }

    private void addFilterChip(@NonNull String filterId, @NonNull String title) {
        TextView pill = new TextView(this);
        boolean selected = TextUtils.equals(activeVectorFilter, filterId);
        pill.setText(title);
        pill.setGravity(Gravity.CENTER);
        pill.setMinHeight(dpToPxInt(36));
        pill.setPadding(dpToPxInt(16), dpToPxInt(8), dpToPxInt(16), dpToPxInt(8));
        pill.setBackgroundResource(R.drawable.bg_profile_filter_chip);
        pill.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small);
        pill.setTextSize(15f);
        pill.setSelected(selected);
        ColorStateList textColors = AppCompatResources.getColorStateList(this, R.color.profile_filter_text);
        if (textColors != null) {
            pill.setTextColor(textColors);
        }
        pill.setOnClickListener(v -> {
            if (TextUtils.equals(activeVectorFilter, filterId)) {
                return;
            }
            activeVectorFilter = filterId;
            currentPage = 0;
            Haptics.softSelection(v);
            refreshItems();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        if (binding.groupXposedHistoryDetailsFilters.getChildCount() > 0) {
            params.setMarginStart(dpToPxInt(8));
        }
        binding.groupXposedHistoryDetailsFilters.addView(pill, params);
    }

    private void bindPaginationButton(@NonNull TextView view, boolean enabled, @NonNull Runnable action) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.45f);
        view.setOnClickListener(
            enabled
                ? v -> {
                      Haptics.softSelection(v);
                      action.run();
                  }
                : null
        );
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

    @NonNull
    private String formatSource(@Nullable String source) {
        if ("native".equals(source)) {
            return getString(R.string.xposed_history_source_native);
        }
        return getString(R.string.xposed_history_source_java);
    }

    @NonNull
    private String formatEventDetail(@NonNull XposedAttackStatsStore.AttackEvent event) {
        String source = formatSource(event.source);
        boolean hasCaller = !TextUtils.isEmpty(event.callerMethod);
        boolean hasDetail = !TextUtils.isEmpty(event.detail);
        if (hasCaller && hasDetail) {
            return getString(
                R.string.xposed_history_event_detail_with_caller,
                source,
                event.callerMethod,
                event.detail
            );
        }
        if (hasCaller) {
            return getString(R.string.xposed_history_event_source_with_caller, source, event.callerMethod);
        }
        if (hasDetail) {
            return getString(R.string.xposed_history_event_detail, source, event.detail);
        }
        return getString(R.string.xposed_history_event_source, source);
    }

    @NonNull
    private static String normalizePackageName(@Nullable String packageName) {
        return packageName == null ? "" : packageName.trim();
    }

    private int dpToPxInt(int value) {
        return DisplayDensityUtils.dpToPx(this, value);
    }
}
