package wings.v.core;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class AutoSearchProbeRequest {

    public XrayProfile profile;
    public XraySettings xraySettings;
    public ByeDpiSettings byeDpiSettings;
    public boolean whitelistMode;
    public boolean pingResponsive;
    public int latencyMs;
    public int downloadAttempts;
    public long downloadSizeBytes;
    public int downloadTimeoutSeconds;
    public long stableBytes;
    public int candidateOrdinal;
    public int totalCandidates;

    @NonNull
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        XrayProfileBundle.write(bundle, "profile_", profile);
        XraySettingsBundle.write(bundle, "xray_", xraySettings);
        if (byeDpiSettings != null) {
            bundle.putBoolean("byedpi_present", true);
            ByeDpiSettingsBundle.write(bundle, "byedpi_", byeDpiSettings);
        } else {
            bundle.putBoolean("byedpi_present", false);
        }
        bundle.putBoolean("whitelist_mode", whitelistMode);
        bundle.putBoolean("ping_responsive", pingResponsive);
        bundle.putInt("latency_ms", latencyMs);
        bundle.putInt("download_attempts", downloadAttempts);
        bundle.putLong("download_size_bytes", downloadSizeBytes);
        bundle.putInt("download_timeout_seconds", downloadTimeoutSeconds);
        bundle.putLong("stable_bytes", stableBytes);
        bundle.putInt("candidate_ordinal", candidateOrdinal);
        bundle.putInt("total_candidates", totalCandidates);
        return bundle;
    }

    @NonNull
    public static AutoSearchProbeRequest fromBundle(@Nullable Bundle bundle) {
        AutoSearchProbeRequest request = new AutoSearchProbeRequest();
        if (bundle == null) {
            return request;
        }
        request.profile = XrayProfileBundle.read(bundle, "profile_");
        request.xraySettings = XraySettingsBundle.read(bundle, "xray_");
        if (bundle.getBoolean("byedpi_present", false)) {
            request.byeDpiSettings = ByeDpiSettingsBundle.read(bundle, "byedpi_");
        }
        request.whitelistMode = bundle.getBoolean("whitelist_mode", false);
        request.pingResponsive = bundle.getBoolean("ping_responsive", false);
        request.latencyMs = bundle.getInt("latency_ms", 0);
        request.downloadAttempts = bundle.getInt("download_attempts", 1);
        request.downloadSizeBytes = bundle.getLong("download_size_bytes", 0L);
        request.downloadTimeoutSeconds = bundle.getInt("download_timeout_seconds", 0);
        request.stableBytes = bundle.getLong("stable_bytes", 0L);
        request.candidateOrdinal = bundle.getInt("candidate_ordinal", 0);
        request.totalCandidates = bundle.getInt("total_candidates", 0);
        return request;
    }
}
