package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;
import java.util.Map;
import wings.v.core.SharingClientMetadata;
import wings.v.core.SharingTrafficStatsStore;
import wings.v.databinding.ActivitySharingClientDetailBinding;
import wings.v.vpnhotspot.bridge.SharingApiGuard;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.DoNotUseThreads" })
public class SharingClientDetailActivity extends AppCompatActivity {

    private static final String EXTRA_MAC = "client_mac";

    private ActivitySharingClientDetailBinding binding;

    public static Intent createIntent(@NonNull Context context, @NonNull byte[] mac) {
        return new Intent(context, SharingClientDetailActivity.class).putExtra(EXTRA_MAC, mac);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SharingApiGuard.isSupported()) {
            finish();
            return;
        }
        byte[] mac = getIntent().getByteArrayExtra(EXTRA_MAC);
        if (mac == null) {
            finish();
            return;
        }
        binding = ActivitySharingClientDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.textDetailLegend.setText(R.string.sharing_stats_legend);

        SharingTrafficStatsStore store = new SharingTrafficStatsStore(this);
        SharingTrafficStatsStore.WeeklyTraffic weekly = store.getClientWeekly(mac);
        if (weekly == null) {
            finish();
            return;
        }
        binding.chartDetailWeekly.setPoints(weekly.points, Math.max(1L, weekly.getMaxDailyBytes()));

        SharingTrafficStatsStore.ClientSummary summary = findSummary(store, mac);
        String vendor = SharingClientMetadata.lookupVendor(mac, this);
        String displayName = vendor != null ? vendor : formatMac(mac);
        binding.textDetailMac.setText(displayName);
        binding.toolbarLayout.setTitle(displayName);
        if (summary == null) {
            binding.textDetailMeta.setText("");
            binding.textDetailAttrs.setText("");
            binding.textDetailLifetime.setText("");
            return;
        }
        binding.textDetailMeta.setText(
            getString(
                R.string.sharing_stats_client_meta,
                summary.lastDownstream,
                formatLastSeenAbsolute(summary.lastSeenMillis)
            )
        );
        Map<String, SharingClientMetadata.ArpEntry> arp = SharingClientMetadata.readArpTable();
        SharingClientMetadata.ArpEntry arpEntry = arp.get(SharingClientMetadata.macKey(mac));
        String ip =
            arpEntry != null && arpEntry.ipAddress != null
                ? arpEntry.ipAddress
                : getString(R.string.sharing_stats_detail_offline);
        String vendorLine = vendor != null ? vendor : getString(R.string.sharing_stats_detail_unknown);
        String macLine = formatMac(mac);
        String ifaceLine = summary.lastDownstream.isEmpty()
            ? getString(R.string.sharing_stats_detail_unknown)
            : summary.lastDownstream;
        String connectedLine =
            summary.firstSeenMillis > 0L
                ? formatElapsed((System.currentTimeMillis() - summary.firstSeenMillis) / 1000L)
                : getString(R.string.sharing_stats_detail_unknown);
        binding.textDetailAttrs.setText(
            getString(R.string.sharing_stats_detail_vendor) +
                ": " +
                vendorLine +
                "\n" +
                "MAC: " +
                macLine +
                "\n" +
                getString(R.string.sharing_stats_detail_ip) +
                ": " +
                ip +
                "\n" +
                getString(R.string.sharing_stats_detail_iface) +
                ": " +
                ifaceLine +
                "\n" +
                getString(R.string.sharing_stats_detail_connected) +
                ": " +
                connectedLine
        );
        binding.textDetailLifetime.setText(
            getString(
                R.string.sharing_stats_lifetime_summary,
                SharingStatsActivity.formatBytes(this, summary.lifetimeSentBytes),
                SharingStatsActivity.formatBytes(this, summary.lifetimeReceivedBytes)
            )
        );
    }

    @Nullable
    private SharingTrafficStatsStore.ClientSummary findSummary(
        @NonNull SharingTrafficStatsStore store,
        @NonNull byte[] mac
    ) {
        for (SharingTrafficStatsStore.ClientSummary candidate : store.getClientSummaries()) {
            if (java.util.Arrays.equals(candidate.mac, mac)) return candidate;
        }
        return null;
    }

    @NonNull
    private String formatMac(@NonNull byte[] mac) {
        StringBuilder sb = new StringBuilder(mac.length * 3);
        for (byte value : mac) {
            if (sb.length() > 0) sb.append(':');
            sb.append(String.format(Locale.ROOT, "%02x", value));
        }
        return sb.toString();
    }

    @NonNull
    private String formatLastSeenAbsolute(long millis) {
        if (millis <= 0L) return "-";
        return DateFormat.format("dd.MM HH:mm", millis).toString();
    }

    @NonNull
    private static String formatElapsed(long seconds) {
        if (seconds < 0L) seconds = 0L;
        long h = seconds / 3600L;
        long m = (seconds % 3600L) / 60L;
        long s = seconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }
}
