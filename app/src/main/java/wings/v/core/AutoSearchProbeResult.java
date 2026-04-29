package wings.v.core;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class AutoSearchProbeResult {

    public boolean live;
    public boolean stable;
    public long downloadedBytes;
    public int successfulAttempts;
    public int completedRuns;
    public int stableRuns;
    public long totalSpeedBytesPerSecond;
    public long averageSpeedBytesPerSecond;
    public String errorMessage;

    @NonNull
    public static AutoSearchProbeResult failure(@Nullable String message) {
        AutoSearchProbeResult result = new AutoSearchProbeResult();
        result.errorMessage = message;
        return result;
    }

    @NonNull
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putBoolean("live", live);
        bundle.putBoolean("stable", stable);
        bundle.putLong("downloaded_bytes", downloadedBytes);
        bundle.putInt("successful_attempts", successfulAttempts);
        bundle.putInt("completed_runs", completedRuns);
        bundle.putInt("stable_runs", stableRuns);
        bundle.putLong("total_speed", totalSpeedBytesPerSecond);
        bundle.putLong("average_speed", averageSpeedBytesPerSecond);
        if (errorMessage != null) {
            bundle.putString("error_message", errorMessage);
        }
        return bundle;
    }

    @NonNull
    public static AutoSearchProbeResult fromBundle(@Nullable Bundle bundle) {
        AutoSearchProbeResult result = new AutoSearchProbeResult();
        if (bundle == null) {
            return result;
        }
        result.live = bundle.getBoolean("live", false);
        result.stable = bundle.getBoolean("stable", false);
        result.downloadedBytes = bundle.getLong("downloaded_bytes", 0L);
        result.successfulAttempts = bundle.getInt("successful_attempts", 0);
        result.completedRuns = bundle.getInt("completed_runs", 0);
        result.stableRuns = bundle.getInt("stable_runs", 0);
        result.totalSpeedBytesPerSecond = bundle.getLong("total_speed", 0L);
        result.averageSpeedBytesPerSecond = bundle.getLong("average_speed", 0L);
        result.errorMessage = bundle.getString("error_message");
        return result;
    }
}
