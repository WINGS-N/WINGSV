package wings.v.byedpi;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import wings.v.core.ByeDpiSettings;

public final class ByeDpiLocalRunner implements AutoCloseable {
    private static final long START_POLL_INTERVAL_MS = 100L;
    private static final long STOP_TIMEOUT_MS = 2_000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ByeDpiNative nativeProxy;
    private Future<Integer> task;
    private String dialHost = "127.0.0.1";
    private int dialPort = 1080;
    private boolean stopping;

    public synchronized void start(@NonNull ByeDpiSettings settings,
                                   @Nullable String protectPath,
                                   long timeoutMs) throws Exception {
        stop();
        nativeProxy = new ByeDpiNative();
        dialHost = settings.resolveRuntimeDialHost();
        dialPort = settings.resolveRuntimeListenPort();
        List<String> arguments = settings.buildRuntimeArguments(protectPath);
        stopping = false;
        task = executor.submit(() -> nativeProxy.startProxy(arguments.toArray(new String[0])));
        waitUntilReady(timeoutMs);
    }

    public synchronized boolean isRunning() {
        return task != null && !task.isDone();
    }

    public synchronized String getDialHost() {
        return dialHost;
    }

    public synchronized int getDialPort() {
        return dialPort;
    }

    public synchronized void stop() {
        stopping = true;
        ByeDpiNative currentNative = nativeProxy;
        Future<Integer> currentTask = task;
        if (currentNative != null) {
            try {
                currentNative.stopProxy();
            } catch (Exception ignored) {
            }
        }
        if (currentTask != null) {
            try {
                currentTask.get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception error) {
                if (currentNative != null) {
                    try {
                        currentNative.forceClose();
                    } catch (Exception ignored) {
                    }
                }
                currentTask.cancel(true);
            }
        }
        task = null;
        nativeProxy = null;
        dialHost = "127.0.0.1";
        dialPort = 1080;
        stopping = false;
    }

    private void waitUntilReady(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, START_POLL_INTERVAL_MS);
        while (System.currentTimeMillis() < deadline) {
            Future<Integer> currentTask = task;
            if (currentTask != null && currentTask.isDone()) {
                try {
                    Integer exitCode = currentTask.get();
                    throw new IllegalStateException("ByeDPI завершился с кодом " + exitCode);
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    throw new IllegalStateException(
                            firstNonEmpty(cause.getMessage(), "ByeDPI завершился до старта"),
                            cause
                    );
                }
            }
            if (isLocalTcpPortReady(dialHost, dialPort)) {
                return;
            }
            Thread.sleep(START_POLL_INTERVAL_MS);
        }
        throw new TimeoutException("ByeDPI не открыл локальный proxy вовремя");
    }

    private boolean isLocalTcpPortReady(String host, int port) {
        if (TextUtils.isEmpty(host) || port <= 0) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) START_POLL_INTERVAL_MS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String firstNonEmpty(@Nullable String first, @Nullable String second) {
        if (!TextUtils.isEmpty(first)) {
            return first;
        }
        return second == null ? "" : second;
    }

    @Override
    public synchronized void close() {
        stop();
        executor.shutdownNow();
    }
}
