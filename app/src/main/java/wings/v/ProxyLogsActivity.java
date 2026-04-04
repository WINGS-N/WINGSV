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

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import wings.v.databinding.ActivityProxyLogsBinding;
import wings.v.service.ProxyTunnelService;

public class ProxyLogsActivity extends AppCompatActivity {
    private static final long REFRESH_INTERVAL_MS = 500L;
    private static final String EXTRA_LOG_MODE = "wings.v.extra.LOG_MODE";
    private static final String MODE_PROXY = "proxy";
    private static final String MODE_XRAY = "xray";
    private static final String MODE_RUNTIME = "runtime";
    private static final long XRAY_LOG_TAIL_BYTES = 128 * 1024L;
    private static final int XRAY_LOG_TAIL_LINES = 100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileReadExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger xrayReadGeneration = new AtomicInteger();
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshLogs();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private ActivityProxyLogsBinding binding;
    private long lastRenderedLogVersion = -1L;
    private String logMode = MODE_PROXY;

    public static Intent createProxyIntent(Context context) {
        return new Intent(context, ProxyLogsActivity.class).putExtra(EXTRA_LOG_MODE, MODE_PROXY);
    }

    public static Intent createXrayIntent(Context context) {
        return new Intent(context, ProxyLogsActivity.class).putExtra(EXTRA_LOG_MODE, MODE_XRAY);
    }

    public static Intent createRuntimeIntent(Context context) {
        return new Intent(context, ProxyLogsActivity.class).putExtra(EXTRA_LOG_MODE, MODE_RUNTIME);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProxyLogsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String requestedMode = getIntent().getStringExtra(EXTRA_LOG_MODE);
        if (!TextUtils.isEmpty(requestedMode)) {
            logMode = requestedMode;
        }
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.toolbarLayout.setTitle(resolveTitle());
        binding.textLogsHeadline.setText(resolveTitle());
        binding.textLogsSubtitle.setText(resolveSubtitle());
        binding.textLogsOutputTitle.setText(resolveOutputTitle());
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
        fileReadExecutor.shutdownNow();
        binding = null;
    }

    private void refreshLogs() {
        if (binding == null) {
            return;
        }

        updateStatusChip();

        long currentVersion = resolveLogVersion();
        if (currentVersion == lastRenderedLogVersion) {
            return;
        }
        if (MODE_XRAY.equals(logMode)) {
            refreshXrayLogsAsync(currentVersion);
            return;
        }

        boolean shouldStickToBottom = isNearBottom();
        String snapshot = resolveSnapshot();
        applySnapshot(snapshot, currentVersion, shouldStickToBottom);
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

    private long resolveLogVersion() {
        if (MODE_RUNTIME.equals(logMode)) {
            return ProxyTunnelService.getRuntimeLogVersion();
        }
        if (MODE_XRAY.equals(logMode)) {
            File logDir = new File(getFilesDir(), "xray/log");
            File errorLog = new File(logDir, "error.log");
            File accessLog = new File(logDir, "access.log");
            return errorLog.lastModified() ^ accessLog.lastModified()
                    ^ errorLog.length() ^ accessLog.length();
        }
        return ProxyTunnelService.getProxyLogVersion();
    }

    private String resolveSnapshot() {
        if (MODE_RUNTIME.equals(logMode)) {
            return ProxyTunnelService.getRuntimeLogSnapshot();
        }
        return ProxyTunnelService.getProxyLogSnapshot();
    }

    private void refreshXrayLogsAsync(long currentVersion) {
        boolean shouldStickToBottom = isNearBottom();
        int generation = xrayReadGeneration.incrementAndGet();
        fileReadExecutor.execute(() -> {
            String snapshot = buildXraySnapshot();
            runOnUiThread(() -> {
                if (binding == null || generation != xrayReadGeneration.get()) {
                    return;
                }
                applySnapshot(snapshot, currentVersion, shouldStickToBottom);
            });
        });
    }

    private void applySnapshot(String snapshot, long currentVersion, boolean shouldStickToBottom) {
        if (binding == null) {
            return;
        }
        binding.textProxyLogs.setText(TextUtils.isEmpty(snapshot)
                ? resolveEmptyText()
                : snapshot);
        lastRenderedLogVersion = currentVersion;
        if (shouldStickToBottom || MODE_XRAY.equals(logMode)) {
            binding.logsScrollView.post(() -> binding.logsScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private String buildXraySnapshot() {
        File logDir = new File(getFilesDir(), "xray/log");
        String error = trimToLastLines(readFileTail(new File(logDir, "error.log")), XRAY_LOG_TAIL_LINES);
        String access = trimToLastLines(readFileTail(new File(logDir, "access.log")), XRAY_LOG_TAIL_LINES);
        StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(error)) {
            builder.append("[error.log]").append('\n').append(error.trim()).append('\n');
        }
        if (!TextUtils.isEmpty(access)) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("[access.log]").append('\n').append(access.trim()).append('\n');
        }
        return builder.toString().trim();
    }

    private String trimToLastLines(String content, int maxLines) {
        if (TextUtils.isEmpty(content) || maxLines <= 0) {
            return "";
        }
        String[] lines = content.split("\\r?\\n");
        if (lines.length <= maxLines) {
            return content;
        }
        ArrayDeque<String> tail = new ArrayDeque<>(maxLines);
        for (String line : lines) {
            if (tail.size() >= maxLines) {
                tail.removeFirst();
            }
            tail.addLast(line);
        }
        StringBuilder builder = new StringBuilder();
        for (String line : tail) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private String readFileTail(File file) {
        try {
            if (file == null || !file.isFile()) {
                return "";
            }
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                long fileLength = input.length();
                long start = Math.max(0L, fileLength - XRAY_LOG_TAIL_BYTES);
                input.seek(start);
                if (start > 0L) {
                    input.readLine();
                }
                byte[] bytes = new byte[(int) (fileLength - input.getFilePointer())];
                input.readFully(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private String resolveTitle() {
        if (MODE_RUNTIME.equals(logMode)) {
            return getString(R.string.runtime_logs_title);
        }
        if (MODE_XRAY.equals(logMode)) {
            return getString(R.string.xray_logs_title);
        }
        return getString(R.string.proxy_logs_title);
    }

    private String resolveSubtitle() {
        if (MODE_RUNTIME.equals(logMode)) {
            return getString(R.string.runtime_logs_subtitle);
        }
        if (MODE_XRAY.equals(logMode)) {
            return getString(R.string.xray_logs_subtitle);
        }
        return getString(R.string.proxy_logs_subtitle);
    }

    private String resolveOutputTitle() {
        if (MODE_RUNTIME.equals(logMode)) {
            return getString(R.string.runtime_logs_output_title);
        }
        if (MODE_XRAY.equals(logMode)) {
            return getString(R.string.xray_logs_output_title);
        }
        return getString(R.string.proxy_logs_output_title);
    }

    private String resolveEmptyText() {
        if (MODE_RUNTIME.equals(logMode)) {
            return getString(R.string.runtime_logs_empty);
        }
        if (MODE_XRAY.equals(logMode)) {
            return getString(R.string.xray_logs_empty);
        }
        return getString(R.string.proxy_logs_empty);
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
