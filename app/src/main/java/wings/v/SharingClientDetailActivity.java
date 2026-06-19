package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;
import wings.v.core.SharingTrafficStatsStore;
import wings.v.databinding.ActivitySharingClientDetailBinding;

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
        binding.textDetailMac.setText(formatMac(mac));
        binding.toolbarLayout.setTitle(formatMac(mac));

        SharingTrafficStatsStore.ClientSummary summary = findSummary(store, mac);
        if (summary == null) {
            binding.textDetailMeta.setText("");
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
}
