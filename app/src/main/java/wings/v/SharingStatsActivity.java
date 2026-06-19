package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;
import wings.v.core.SharingClientMetadata;
import wings.v.core.SharingTrafficStatsStore;
import wings.v.databinding.ActivitySharingStatsBinding;
import wings.v.vpnhotspot.bridge.SharingApiGuard;
import wings.v.vpnhotspot.sharing.runtime.VpnHotspotTrafficCounter;
import wings.v.widget.TrafficWeeklyChartView;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.DoNotUseThreads" })
public class SharingStatsActivity extends AppCompatActivity {

    private static final long POLL_INTERVAL_MS = 10_000L;
    private static final long HEADER_TICK_MS = 1_000L;

    private ActivitySharingStatsBinding binding;
    private SharingTrafficStatsStore store;
    private ClientAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService workExecutor = Executors.newSingleThreadExecutor();
    private final Runnable pollRunnable = this::pollCounters;
    private final Runnable headerTickRunnable = this::tickHeader;
    private Map<String, SharingClientMetadata.ArpEntry> arpCache = new java.util.HashMap<>();
    private boolean active;

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, SharingStatsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SharingApiGuard.isSupported()) {
            finish();
            return;
        }
        binding = ActivitySharingStatsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setTitle(getString(R.string.sharing_stats_title));
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.textLegend.setText(R.string.sharing_stats_legend);

        store = new SharingTrafficStatsStore(this);
        adapter = new ClientAdapter();
        binding.listClients.setLayoutManager(new LinearLayoutManager(this));
        binding.listClients.setAdapter(adapter);
        binding.listClients.setHasFixedSize(false);

        renderState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        store.resetSession();
        mainHandler.post(pollRunnable);
        mainHandler.post(headerTickRunnable);
    }

    @Override
    protected void onPause() {
        active = false;
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.removeCallbacks(headerTickRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        workExecutor.shutdownNow();
    }

    private void pollCounters() {
        if (!active) return;
        Context appContext = getApplicationContext();
        workExecutor.execute(() -> {
            List<VpnHotspotTrafficCounter> counters;
            try {
                counters = SharingApiGuard.readTrafficCountersOrEmpty(appContext);
            } catch (Throwable error) {
                counters = new ArrayList<>();
            }
            List<SharingTrafficStatsStore.CounterSnapshot> snapshots = new ArrayList<>(counters.size());
            for (VpnHotspotTrafficCounter counter : counters) {
                snapshots.add(
                    new SharingTrafficStatsStore.CounterSnapshot(
                        counter.getMac(),
                        counter.getDownstream() == null ? "" : counter.getDownstream(),
                        counter.getSentBytes(),
                        counter.getReceivedBytes()
                    )
                );
            }
            store.applyCounters(snapshots);
            Map<String, SharingClientMetadata.ArpEntry> arp = SharingClientMetadata.readArpTable();
            mainHandler.post(() -> {
                arpCache = arp;
                renderState();
                if (active) mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
            });
        });
    }

    private void tickHeader() {
        if (!active) return;
        updateClientsHeader();
        mainHandler.postDelayed(headerTickRunnable, HEADER_TICK_MS);
    }

    private void updateClientsHeader() {
        if (binding == null) return;
        String iface = store.getLastActiveInterface();
        if (iface.isEmpty()) iface = "swlan0";
        long startedAt = store.getSessionStartedAtMs();
        String label;
        if (startedAt > 0L) {
            long elapsedSec = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
            label = getString(R.string.sharing_stats_clients_section_header, iface, formatElapsed(elapsedSec));
        } else {
            label = getString(R.string.sharing_stats_clients_section_idle, iface);
        }
        binding.separatorClients.setText(label);
    }

    @NonNull
    private static String formatElapsed(long seconds) {
        long h = seconds / 3600L;
        long m = (seconds % 3600L) / 60L;
        long s = seconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }

    private void renderState() {
        SharingTrafficStatsStore.WeeklyTraffic weekly = store.getAggregateWeekly();
        binding.chartWeekly.setPoints(weekly.points, Math.max(1L, weekly.getMaxDailyBytes()));
        binding.textTotalSummary.setText(
            getString(
                R.string.sharing_stats_total_summary,
                formatBytes(weekly.totalSentBytes),
                formatBytes(weekly.totalReceivedBytes)
            )
        );
        List<SharingTrafficStatsStore.ClientSummary> clients = store.getClientSummaries();
        adapter.setItems(clients, arpCache, this);
        updateClientsHeader();
        if (clients.isEmpty()) {
            binding.textEmpty.setVisibility(View.VISIBLE);
            binding.textEmpty.setText(R.string.sharing_stats_no_clients);
            binding.listClients.setVisibility(View.GONE);
        } else {
            binding.textEmpty.setVisibility(View.GONE);
            binding.listClients.setVisibility(View.VISIBLE);
        }
    }

    @NonNull
    private String formatLastSeen(long millis) {
        if (millis <= 0L) return getString(R.string.sharing_stats_just_now);
        long deltaMs = System.currentTimeMillis() - millis;
        if (deltaMs < 60_000L) return getString(R.string.sharing_stats_just_now);
        long deltaMinutes = deltaMs / 60_000L;
        if (deltaMinutes < 60L) return getString(R.string.sharing_stats_minutes_ago, (int) deltaMinutes);
        long deltaHours = deltaMinutes / 60L;
        if (deltaHours < 24L) return getString(R.string.sharing_stats_hours_ago, (int) deltaHours);
        long deltaDays = deltaHours / 24L;
        return getString(R.string.sharing_stats_days_ago, (int) deltaDays);
    }

    @NonNull
    public static String formatBytes(@NonNull Context context, long bytes) {
        if (bytes < 1024L) {
            return context.getString(R.string.sharing_stats_unit_bytes, (int) bytes);
        }
        double value = bytes / 1024.0;
        if (value < 1024.0) {
            return context.getString(R.string.sharing_stats_unit_kib, value);
        }
        value /= 1024.0;
        if (value < 1024.0) {
            return context.getString(R.string.sharing_stats_unit_mib, value);
        }
        value /= 1024.0;
        return context.getString(R.string.sharing_stats_unit_gib, value);
    }

    @NonNull
    private String formatBytes(long bytes) {
        return formatBytes(this, bytes);
    }

    private final class ClientAdapter extends RecyclerView.Adapter<ClientViewHolder> {

        private final List<SharingTrafficStatsStore.ClientSummary> items = new ArrayList<>();
        private Map<String, SharingClientMetadata.ArpEntry> arp = new java.util.HashMap<>();
        private Context context = SharingStatsActivity.this;

        void setItems(
            @NonNull List<SharingTrafficStatsStore.ClientSummary> next,
            @NonNull Map<String, SharingClientMetadata.ArpEntry> arpEntries,
            @NonNull Context ctx
        ) {
            items.clear();
            items.addAll(next);
            arp = arpEntries;
            context = ctx;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ClientViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.item_sharing_traffic_client,
                parent,
                false
            );
            return new ClientViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ClientViewHolder holder, int position) {
            SharingTrafficStatsStore.ClientSummary item = items.get(position);
            String vendor = SharingClientMetadata.lookupVendor(item.mac, context);
            String displayName = vendor != null ? vendor : item.formatMac();
            holder.macView.setText(displayName);
            SharingClientMetadata.ArpEntry arpEntry = arp.get(SharingClientMetadata.macKey(item.mac));
            String ip = arpEntry != null ? arpEntry.ipAddress : null;
            String iface = item.lastDownstream;
            String metaPrimary;
            if (ip != null && !ip.isEmpty()) {
                metaPrimary = ip + " · " + iface;
            } else {
                metaPrimary = iface;
            }
            String meta;
            if (item.lastSeenMillis > 0L) {
                meta = holder.itemView
                    .getContext()
                    .getString(R.string.sharing_stats_client_meta, metaPrimary, formatLastSeen(item.lastSeenMillis));
            } else {
                meta = holder.itemView
                    .getContext()
                    .getString(R.string.sharing_stats_client_meta_never, metaPrimary);
            }
            holder.metaView.setText(meta);
            holder.trafficView.setText(
                holder.itemView
                    .getContext()
                    .getString(
                        R.string.sharing_stats_traffic_summary,
                        formatBytes(item.weeklySentBytes),
                        formatBytes(item.weeklyReceivedBytes)
                    )
            );
            holder.itemView.setOnClickListener(view -> {
                Intent intent = SharingClientDetailActivity.createIntent(view.getContext(), item.mac);
                view.getContext().startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    static final class ClientViewHolder extends RecyclerView.ViewHolder {

        final TextView macView;
        final TextView metaView;
        final TextView trafficView;

        ClientViewHolder(@NonNull View itemView) {
            super(itemView);
            macView = itemView.findViewById(R.id.text_client_mac);
            metaView = itemView.findViewById(R.id.text_client_meta);
            trafficView = itemView.findViewById(R.id.text_client_traffic);
        }
    }
}
