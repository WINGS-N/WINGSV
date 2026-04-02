package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import wings.v.databinding.ActivityProxyLogsBinding;
import wings.v.service.ProxyTunnelService;

public class ProxyLogsActivity extends AppCompatActivity {
    private static final long REFRESH_INTERVAL_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshLogs();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private ActivityProxyLogsBinding binding;
    private long lastRenderedLogVersion = -1L;

    public static Intent createIntent(Context context) {
        return new Intent(context, ProxyLogsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProxyLogsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        refreshLogs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshLogs();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refreshRunnable);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    private void refreshLogs() {
        if (binding == null) {
            return;
        }

        updateStatusChip();

        long currentVersion = ProxyTunnelService.getProxyLogVersion();
        if (currentVersion == lastRenderedLogVersion) {
            return;
        }

        boolean shouldStickToBottom = isNearBottom();
        String snapshot = ProxyTunnelService.getProxyLogSnapshot();
        binding.textProxyLogs.setText(TextUtils.isEmpty(snapshot)
                ? getString(R.string.proxy_logs_empty)
                : snapshot);
        lastRenderedLogVersion = currentVersion;

        if (shouldStickToBottom) {
            binding.logsScrollView.post(() -> binding.logsScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void updateStatusChip() {
        if (ProxyTunnelService.isRunning()) {
            binding.textProxyLogsStatus.setText(R.string.service_on);
            return;
        }
        if (ProxyTunnelService.isConnecting()) {
            binding.textProxyLogsStatus.setText(R.string.service_connecting);
            return;
        }
        binding.textProxyLogsStatus.setText(R.string.service_off);
    }

    private boolean isNearBottom() {
        if (binding == null) {
            return true;
        }
        View content = binding.logsScrollView.getChildAt(0);
        if (content == null) {
            return true;
        }
        int thresholdPx = Math.round(32f * getResources().getDisplayMetrics().density);
        int diff = content.getBottom()
                - (binding.logsScrollView.getHeight() + binding.logsScrollView.getScrollY());
        return diff <= thresholdPx;
    }
}
