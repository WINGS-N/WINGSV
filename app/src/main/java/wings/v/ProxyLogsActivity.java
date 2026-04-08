package wings.v;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityProxyLogsBinding;
import wings.v.databinding.ItemLogLineBinding;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class ProxyLogsActivity extends AppCompatActivity {

    private static final long REFRESH_INTERVAL_MS = 500L;
    private static final String EXTRA_LOG_MODE = "wings.v.extra.LOG_MODE";
    private static final String STATE_AUTO_SCROLL = "state.auto_scroll";
    private static final String MODE_PROXY = "proxy";
    private static final String MODE_XRAY = "xray";
    private static final String MODE_RUNTIME = "runtime";
    private static final long XRAY_LOG_TAIL_BYTES = 128 * 1024L;
    private static final int XRAY_LOG_TAIL_LINES = 100;
    private static final int MAX_DISPLAY_LINES = 5_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileReadExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger xrayReadGeneration = new AtomicInteger();
    private final ArrayList<String> displayedLines = new ArrayList<>();
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshLogs();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private ActivityProxyLogsBinding binding;
    private LogsAdapter logsAdapter;
    private LinearLayoutManager layoutManager;
    private long lastRenderedLogVersion = -1L;
    private String logMode = MODE_PROXY;
    private boolean autoScrollEnabled;

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
        autoScrollEnabled = savedInstanceState != null && savedInstanceState.getBoolean(STATE_AUTO_SCROLL, false);

        String requestedMode = getIntent().getStringExtra(EXTRA_LOG_MODE);
        if (!TextUtils.isEmpty(requestedMode)) {
            logMode = requestedMode;
        }

        logsAdapter = new LogsAdapter(displayedLines);
        layoutManager = new LinearLayoutManager(this);
        binding.recyclerProxyLogs.setLayoutManager(layoutManager);
        binding.recyclerProxyLogs.setAdapter(logsAdapter);

        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.toolbarLayout.setTitle(resolveTitle());
        binding.textLogsHeadline.setText(resolveTitle());
        binding.textLogsSubtitle.setText(resolveSubtitle());
        binding.textLogsOutputTitle.setText(resolveOutputTitle());
        binding.textProxyLogsEmpty.setText(resolveEmptyText());
        binding.switchLogsAutoScroll.setChecked(autoScrollEnabled);
        binding.switchLogsAutoScroll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoScrollEnabled = isChecked;
            Haptics.softSliderStep(buttonView);
            if (autoScrollEnabled) {
                scrollToBottom();
            }
        });
        binding.buttonCopyLogs.setOnClickListener(view -> {
            Haptics.softSelection(view);
            copyCurrentLogText();
        });
        refreshLogs();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_AUTO_SCROLL, autoScrollEnabled);
        super.onSaveInstanceState(outState);
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
        boolean shouldStickToBottom = autoScrollEnabled && isNearBottom();
        if (MODE_XRAY.equals(logMode)) {
            refreshXrayLogsAsync(currentVersion, shouldStickToBottom);
            return;
        }
        applySnapshot(resolveSnapshot(), currentVersion, shouldStickToBottom);
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
            return errorLog.lastModified() ^ accessLog.lastModified() ^ errorLog.length() ^ accessLog.length();
        }
        return ProxyTunnelService.getProxyLogVersion();
    }

    private String resolveSnapshot() {
        if (MODE_RUNTIME.equals(logMode)) {
            return ProxyTunnelService.getRuntimeLogSnapshot();
        }
        return ProxyTunnelService.getProxyLogSnapshot();
    }

    private void refreshXrayLogsAsync(long currentVersion, boolean shouldStickToBottom) {
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
        List<String> newLines = splitLines(snapshot);
        if (newLines.isEmpty()) {
            displayedLines.clear();
            logsAdapter.notifyDataSetChanged();
            binding.textProxyLogsEmpty.setVisibility(View.VISIBLE);
        } else {
            binding.textProxyLogsEmpty.setVisibility(View.GONE);
            mergeSnapshotLines(newLines);
        }
        lastRenderedLogVersion = currentVersion;
        if (autoScrollEnabled && shouldStickToBottom) {
            scrollToBottom();
        }
    }

    private void mergeSnapshotLines(List<String> newLines) {
        if (displayedLines.isEmpty()) {
            displayedLines.addAll(newLines);
            trimDisplayedLines();
            logsAdapter.notifyDataSetChanged();
            return;
        }
        if (displayedLines.equals(newLines)) {
            return;
        }
        int overlap = findOverlap(displayedLines, newLines);
        if (overlap <= 0) {
            displayedLines.clear();
            displayedLines.addAll(newLines);
            trimDisplayedLines();
            logsAdapter.notifyDataSetChanged();
            return;
        }
        if (overlap >= newLines.size()) {
            return;
        }
        int insertStart = displayedLines.size();
        for (int index = overlap; index < newLines.size(); index++) {
            displayedLines.add(newLines.get(index));
        }
        int removed = trimDisplayedLines();
        if (removed > 0) {
            logsAdapter.notifyDataSetChanged();
            return;
        }
        logsAdapter.notifyItemRangeInserted(insertStart, newLines.size() - overlap);
    }

    private int trimDisplayedLines() {
        int removed = 0;
        while (displayedLines.size() > MAX_DISPLAY_LINES) {
            displayedLines.remove(0);
            removed++;
        }
        return removed;
    }

    private int findOverlap(List<String> existingLines, List<String> newLines) {
        int max = Math.min(existingLines.size(), newLines.size());
        for (int overlap = max; overlap > 0; overlap--) {
            boolean matches = true;
            int existingStart = existingLines.size() - overlap;
            for (int index = 0; index < overlap; index++) {
                if (!TextUtils.equals(existingLines.get(existingStart + index), newLines.get(index))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return overlap;
            }
        }
        return 0;
    }

    private List<String> splitLines(String snapshot) {
        ArrayList<String> lines = new ArrayList<>();
        if (TextUtils.isEmpty(snapshot)) {
            return lines;
        }
        String[] rawLines = snapshot.split("\\r?\\n", -1);
        lines.addAll(Arrays.asList(rawLines));
        return lines;
    }

    private void copyCurrentLogText() {
        ClipboardManager clipboardManager = getSystemService(ClipboardManager.class);
        if (clipboardManager == null) {
            Toast.makeText(this, R.string.proxy_logs_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String text = TextUtils.join("\n", displayedLines);
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, R.string.proxy_logs_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText(resolveTitle(), text));
        Toast.makeText(this, R.string.proxy_logs_copy_done, Toast.LENGTH_SHORT).show();
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
        } catch (java.io.IOException ignored) {
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
        if (layoutManager == null || logsAdapter == null) {
            return true;
        }
        int itemCount = logsAdapter.getItemCount();
        if (itemCount <= 0) {
            return true;
        }
        return layoutManager.findLastVisibleItemPosition() >= itemCount - 3;
    }

    private void scrollToBottom() {
        if (binding == null || logsAdapter == null) {
            return;
        }
        int itemCount = logsAdapter.getItemCount();
        if (itemCount <= 0) {
            return;
        }
        binding.recyclerProxyLogs.post(() -> binding.recyclerProxyLogs.scrollToPosition(itemCount - 1));
    }

    private static final class LogsAdapter extends RecyclerView.Adapter<LogsAdapter.LogLineViewHolder> {

        private final List<String> lines;

        LogsAdapter(List<String> lines) {
            this.lines = lines;
        }

        @NonNull
        @Override
        public LogLineViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            return new LogLineViewHolder(ItemLogLineBinding.inflate(inflater, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull LogLineViewHolder holder, int position) {
            holder.textView.setText(lines.get(position));
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        static final class LogLineViewHolder extends RecyclerView.ViewHolder {

            final TextView textView;

            LogLineViewHolder(ItemLogLineBinding binding) {
                super(binding.getRoot());
                this.textView = binding.textLogLine;
            }
        }
    }
}
