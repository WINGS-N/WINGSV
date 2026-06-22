package wings.v.byedpi;

import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import wings.v.R;
import wings.v.WingsApplication;
import wings.v.core.ByeDpiSettings;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.AvoidUsingHardCodedIP",
        "PMD.AvoidSynchronizedAtMethodLevel",
        "PMD.AvoidCatchingGenericException",
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.LongVariable",
        "PMD.SignatureDeclareThrowsException",
        "PMD.AtLeastOneConstructor",
    }
)
public final class ByeDpiLocalRunner implements AutoCloseable {

    private static final long START_POLL_INTERVAL_MS = 100L;
    private static final long STOP_TIMEOUT_MS = 2_000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService readinessExecutor = Executors.newSingleThreadScheduledExecutor();
    private ByeDpiNative nativeProxy;
    private Future<Integer> task;
    private String dialHost = "127.0.0.1";
    private int dialPort = 1080;

    public synchronized void start(@NonNull ByeDpiSettings settings, @Nullable String protectPath, long timeoutMs)
        throws Exception {
        start(settings, protectPath, timeoutMs, 0L);
    }

    public synchronized void start(
        @NonNull ByeDpiSettings settings,
        @Nullable String protectPath,
        long timeoutMs,
        long settleMs
    ) throws Exception {
        stop();
        nativeProxy = new ByeDpiNative();
        dialHost = settings.resolveRuntimeDialHost();
        dialPort = settings.resolveRuntimeListenPort();
        List<String> arguments = settings.buildRuntimeArguments(protectPath);
        task = executor.submit(() -> nativeProxy.startProxy(arguments.toArray(new String[0])));
        waitUntilReady(timeoutMs);
        // The listen socket accepts the moment listen() runs, before the proxy
        // can actually relay. Give it a settle window so the first requests do
        // not land on a not-yet-ready proxy and score as failures. Mirrors the
        // warm-up the auto-search uses. Re-check the worker did not exit during
        // the settle, otherwise surface the same early-exit reason as readiness.
        if (settleMs > 0L) {
            Thread.sleep(settleMs);
            Future<Integer> settledTask = task;
            if (settledTask != null && settledTask.isDone()) {
                throw buildEarlyExitException(settledTask);
            }
        }
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
        ByeDpiNative currentNative = nativeProxy;
        Future<Integer> currentTask = task;
        if (currentNative != null) {
            currentNative.stopProxy();
        }
        if (currentTask != null) {
            try {
                currentTask.get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                if (currentNative != null) {
                    currentNative.forceClose();
                }
                currentTask.cancel(true);
            } catch (ExecutionException | TimeoutException error) {
                if (currentNative != null) {
                    currentNative.forceClose();
                }
                currentTask.cancel(true);
            }
        }
        task = null;
        nativeProxy = null;
        dialHost = "127.0.0.1";
        dialPort = 1080;
    }

    private void waitUntilReady(long timeoutMs) throws Exception {
        long effectiveTimeoutMs = Math.max(timeoutMs, START_POLL_INTERVAL_MS);
        Future<Integer> startingTask = task;
        String readyHost = dialHost;
        int readyPort = dialPort;
        CompletableFuture<Void> readiness = new CompletableFuture<>();
        ScheduledFuture<?> readinessTask = readinessExecutor.scheduleWithFixedDelay(
            () -> {
                if (readiness.isDone()) {
                    return;
                }
                try {
                    if (startingTask != null && startingTask.isDone()) {
                        readiness.completeExceptionally(buildEarlyExitException(startingTask));
                        return;
                    }
                    if (isLocalTcpPortReady(readyHost, readyPort)) {
                        readiness.complete(null);
                    }
                } catch (RuntimeException error) {
                    readiness.completeExceptionally(error);
                }
            },
            0L,
            START_POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        try {
            readiness.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            throw new IllegalStateException(firstNonEmpty(error.getMessage()), error);
        } finally {
            readinessTask.cancel(false);
        }
    }

    private Exception buildEarlyExitException(Future<Integer> currentTask) {
        try {
            Integer exitCode = currentTask.get();
            return new IllegalStateException(
                WingsApplication.getStringSafe(R.string.proxy_byedpi_exited_code, exitCode)
            );
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            return new IllegalStateException(firstNonEmpty(cause.getMessage()), cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return error;
        }
    }

    private boolean isLocalTcpPortReady(String host, int port) {
        if (TextUtils.isEmpty(host) || port <= 0) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) START_POLL_INTERVAL_MS);
            return true;
        } catch (java.io.IOException ignored) {
            return false;
        }
    }

    private String firstNonEmpty(@Nullable String first) {
        if (!TextUtils.isEmpty(first)) {
            return first;
        }
        return WingsApplication.getStringSafe(R.string.proxy_byedpi_exited_before_start);
    }

    @Override
    public synchronized void close() {
        stop();
        executor.shutdownNow();
        readinessExecutor.shutdownNow();
    }
}
