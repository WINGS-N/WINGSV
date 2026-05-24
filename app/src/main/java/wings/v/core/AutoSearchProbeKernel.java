package wings.v.core;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import wings.v.xray.XrayAutoSearchConfigFactory;
import wings.v.xray.XrayBridge;

@SuppressWarnings(
    {
        "PMD.AvoidUsingHardCodedIP",
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LongVariable",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.GodClass",
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.NPathComplexity",
        "PMD.LawOfDemeter",
        "PMD.AvoidDuplicateLiterals",
        "PMD.CloseResource",
        "PMD.UseTryWithResources",
    }
)
public final class AutoSearchProbeKernel {

    private static final int SOCKS5_VERSION = 0x05;
    private static final int SOCKS5_METHOD_NO_AUTH = 0x00;
    private static final int SOCKS5_METHOD_USERNAME_PASSWORD = 0x02;
    private static final int SOCKS5_METHOD_NOT_ACCEPTABLE = 0xff;
    private static final int SOCKS5_AUTH_VERSION = 0x01;
    private static final int SOCKS5_COMMAND_CONNECT = 0x01;
    private static final int SOCKS5_ADDRESS_TYPE_IPV4 = 0x01;
    private static final int SOCKS5_ADDRESS_TYPE_DOMAIN = 0x03;
    private static final int SOCKS5_ADDRESS_TYPE_IPV6 = 0x04;
    private static final int SOCKS5_HOST_MAX_LENGTH = 255;
    private static final int IPV4_ADDRESS_LENGTH = 4;
    private static final int IPV6_ADDRESS_LENGTH = 16;
    private static final int HTTP_STATUS_PARTS_MIN = 2;
    private static final int HTTP_STATUS_PARTS_LIMIT = 3;
    private static final int LINE_FEED = '\n';
    private static final int CARRIAGE_RETURN = '\r';
    private static final long MIN_SKIPPED_BYTES = 1L;

    private static final String DOWNLOAD_TEST_URL_PREFIX = "https://speed.cloudflare.com/__down?bytes=";
    private static final String[] TRAFFIC_PROBE_URLS = {
        "https://cp.cloudflare.com/generate_204",
        "https://cloudflare.com/cdn-cgi/trace",
        "https://1.1.1.1/cdn-cgi/trace",
    };
    private static final int TRAFFIC_PROBE_CONNECT_TIMEOUT_MS = 3_000;
    private static final int TRAFFIC_PROBE_READ_TIMEOUT_MS = 5_000;
    private static final int TRAFFIC_PROBE_RESPONSIVE_CONNECT_TIMEOUT_MS = 8_000;
    private static final int TRAFFIC_PROBE_RESPONSIVE_READ_TIMEOUT_MS = 12_000;
    private static final int TRAFFIC_PROBE_MAX_BYTES = 4 * 1024;

    private static final long XRAY_PROXY_START_TIMEOUT_MS = 4_000L;
    private static final long XRAY_PROXY_START_POLL_MS = 100L;
    private static final long XRAY_PROXY_WARMUP_MS = 700L;
    private static final long INTER_ATTEMPT_DELAY_MS = 3_000L;

    public static final String STAGE_WARMUP = "warmup";
    public static final String STAGE_PREFLIGHT = "preflight";
    public static final String STAGE_DOWNLOAD = "download";
    public static final String PROGRESS_KEY_STAGE = "stage";
    public static final String PROGRESS_KEY_ATTEMPT = "attempt";
    public static final String PROGRESS_KEY_ATTEMPT_COUNT = "attempt_count";
    public static final String PROGRESS_KEY_BYTES_READ = "bytes_read";
    public static final String PROGRESS_KEY_TARGET_BYTES = "target_bytes";
    public static final String PROGRESS_KEY_SPEED = "speed_bytes_per_second";
    public static final String PROGRESS_KEY_CANDIDATE_ORDINAL = "candidate_ordinal";

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(@NonNull Bundle bundle);
    }

    private AutoSearchProbeKernel() {}

    @NonNull
    public static AutoSearchProbeResult run(@NonNull Context context, @NonNull AutoSearchProbeRequest request) {
        return run(context, request, null, null);
    }

    @NonNull
    public static AutoSearchProbeResult run(
        @NonNull Context context,
        @NonNull AutoSearchProbeRequest request,
        @Nullable ProgressCallback progress
    ) {
        return run(context, request, progress, null);
    }

    @NonNull
    public static AutoSearchProbeResult run(
        @NonNull Context context,
        @NonNull AutoSearchProbeRequest request,
        @Nullable ProgressCallback progress,
        @Nullable java.util.function.BooleanSupplier cancelSignal
    ) {
        AutoSearchProbeResult result = new AutoSearchProbeResult();
        if (request.profile == null || TextUtils.isEmpty(request.profile.rawLink)) {
            result.errorMessage = "missing profile";
            return result;
        }

        int localPort;
        try {
            localPort = findAvailableTcpPort();
        } catch (IOException error) {
            result.errorMessage = "no free port: " + error.getMessage();
            return result;
        }

        String configJson;
        try {
            configJson = XrayAutoSearchConfigFactory.buildConfigJson(
                context,
                request.profile,
                request.xraySettings,
                localPort,
                request.byeDpiSettings,
                request.whitelistMode
            );
        } catch (Exception error) {
            result.errorMessage = "build config: " + error.getMessage();
            return result;
        }

        try {
            stopXrayQuietly();
            try {
                XrayBridge.prepareRuntimeDirect(
                    request.xraySettings != null ? request.xraySettings.remoteDns : null,
                    request.xraySettings != null ? request.xraySettings.directDns : null
                );
                XrayBridge.runFromJson(context, configJson, 0);
            } catch (Exception error) {
                result.errorMessage = "xray runtime: " + error.getMessage();
                return result;
            }

            try {
                waitForLocalProxy(localPort);
            } catch (Exception error) {
                result.errorMessage = "xray proxy not ready: " + error.getMessage();
                return result;
            }
            emitStage(progress, request, STAGE_WARMUP, 0, 0);
            SystemClock.sleep(XRAY_PROXY_WARMUP_MS);

            if (request.pingResponsive) {
                result.live = true;
            } else {
                emitStage(progress, request, STAGE_PREFLIGHT, 0, 0);
                ProbeResult preflight = ensureTrafficUp(context, request.xraySettings, localPort);
                result.live = preflight.success;
                if (!preflight.success) {
                    result.errorMessage = "preflight failed";
                    return result;
                }
            }

            int downloadAttempts = Math.max(1, request.downloadAttempts);
            long stableBytes = Math.max(1L, request.stableBytes);
            for (int attempt = 1; attempt <= downloadAttempts; attempt++) {
                if (isCancelled(cancelSignal)) {
                    break;
                }
                emitStage(progress, request, STAGE_DOWNLOAD, attempt, downloadAttempts);
                int currentAttempt = attempt;
                DownloadResult dr = downloadThroughProxy(
                    context,
                    request,
                    localPort,
                    progress,
                    currentAttempt,
                    downloadAttempts,
                    cancelSignal
                );
                result.downloadedBytes = Math.max(result.downloadedBytes, dr.bytesRead);
                result.successfulAttempts += dr.success ? 1 : 0;
                result.completedRuns++;
                result.totalSpeedBytesPerSecond += dr.averageSpeedBytesPerSecond;
                result.averageSpeedBytesPerSecond =
                    result.completedRuns > 0 ? result.totalSpeedBytesPerSecond / result.completedRuns : 0L;
                result.live = result.live || dr.bytesRead > 0L;
                if (meetsDownloadTarget(dr.bytesRead, stableBytes) && dr.success) {
                    result.stableRuns++;
                }
                if (attempt < downloadAttempts && interruptibleSleep(INTER_ATTEMPT_DELAY_MS, cancelSignal)) {
                    break;
                }
            }
            result.stable = result.stableRuns >= downloadAttempts;
            return result;
        } finally {
            stopXrayQuietly();
        }
    }

    private static boolean isCancelled(@Nullable java.util.function.BooleanSupplier signal) {
        return signal != null && signal.getAsBoolean();
    }

    private static boolean interruptibleSleep(
        long durationMs,
        @Nullable java.util.function.BooleanSupplier cancelSignal
    ) {
        long deadline = SystemClock.elapsedRealtime() + durationMs;
        while (true) {
            if (isCancelled(cancelSignal)) {
                return true;
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) {
                return false;
            }
            SystemClock.sleep(Math.min(100L, remaining));
        }
    }

    private static void emitStage(
        @Nullable ProgressCallback progress,
        @NonNull AutoSearchProbeRequest request,
        @NonNull String stage,
        int attempt,
        int attemptCount
    ) {
        if (progress == null) {
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putInt(PROGRESS_KEY_CANDIDATE_ORDINAL, request.candidateOrdinal);
        bundle.putString(PROGRESS_KEY_STAGE, stage);
        bundle.putInt(PROGRESS_KEY_ATTEMPT, attempt);
        bundle.putInt(PROGRESS_KEY_ATTEMPT_COUNT, attemptCount);
        bundle.putLong(PROGRESS_KEY_TARGET_BYTES, request.downloadSizeBytes);
        progress.onProgress(bundle);
    }

    private static void emitDownloadProgress(
        @Nullable ProgressCallback progress,
        @NonNull AutoSearchProbeRequest request,
        int attempt,
        int attemptCount,
        long bytesRead,
        long targetBytes,
        long speedBytesPerSecond
    ) {
        if (progress == null) {
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putInt(PROGRESS_KEY_CANDIDATE_ORDINAL, request.candidateOrdinal);
        bundle.putString(PROGRESS_KEY_STAGE, STAGE_DOWNLOAD);
        bundle.putInt(PROGRESS_KEY_ATTEMPT, attempt);
        bundle.putInt(PROGRESS_KEY_ATTEMPT_COUNT, attemptCount);
        bundle.putLong(PROGRESS_KEY_BYTES_READ, bytesRead);
        bundle.putLong(PROGRESS_KEY_TARGET_BYTES, targetBytes);
        bundle.putLong(PROGRESS_KEY_SPEED, speedBytesPerSecond);
        progress.onProgress(bundle);
    }

    private static int findAvailableTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void stopXrayQuietly() {
        try {
            XrayBridge.stop();
        } catch (Exception ignored) {}
    }

    private static void waitForLocalProxy(int port) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + XRAY_PROXY_START_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException ignored) {
                SystemClock.sleep(XRAY_PROXY_START_POLL_MS);
            }
        }
        throw new IOException("xray local proxy did not open on port " + port);
    }

    @NonNull
    private static ProbeResult ensureTrafficUp(
        @NonNull Context context,
        @Nullable XraySettings xraySettings,
        int localPort
    ) {
        for (String url : TRAFFIC_PROBE_URLS) {
            ProbeResult result = probeTrafficThroughSocks(context, xraySettings, "127.0.0.1", localPort, url, false);
            if (result.success) {
                return result;
            }
        }
        return ProbeResult.failed();
    }

    @NonNull
    private static ProbeResult probeTrafficThroughSocks(
        @NonNull Context context,
        @Nullable XraySettings xraySettings,
        @NonNull String host,
        int port,
        @NonNull String url,
        boolean responsiveCandidate
    ) {
        SocksHttpResult result = requestHttpViaSocks(
            context,
            host,
            port,
            resolveLocalSocksUsername(xraySettings),
            resolveLocalSocksPassword(xraySettings),
            url,
            responsiveCandidate ? TRAFFIC_PROBE_RESPONSIVE_CONNECT_TIMEOUT_MS : TRAFFIC_PROBE_CONNECT_TIMEOUT_MS,
            responsiveCandidate ? TRAFFIC_PROBE_RESPONSIVE_READ_TIMEOUT_MS : TRAFFIC_PROBE_READ_TIMEOUT_MS,
            TRAFFIC_PROBE_MAX_BYTES
        );
        if (!hasSuccessfulTrafficResponse(result)) {
            return ProbeResult.failed();
        }
        return new ProbeResult(true, result.responseCode, result.bytesRead);
    }

    private static boolean hasSuccessfulTrafficResponse(@Nullable SocksHttpResult result) {
        return (
            result != null &&
            result.responseCode >= 200 &&
            result.responseCode < 400 &&
            (result.success || result.bytesRead > 0L)
        );
    }

    @NonNull
    private static DownloadResult downloadThroughProxy(
        @NonNull Context context,
        @NonNull AutoSearchProbeRequest request,
        int localPort,
        @Nullable ProgressCallback progress,
        int attempt,
        int attemptCount,
        @Nullable java.util.function.BooleanSupplier cancelSignal
    ) {
        final long startedAtMs = SystemClock.elapsedRealtime();
        final long singleSuccessBytes = Math.max(1L, request.downloadSizeBytes);
        final int timeoutMs = Math.max(1, request.downloadTimeoutSeconds) * 1000;
        long readLimitBytes = Math.min(singleSuccessBytes, Math.max(1L, request.stableBytes));
        long deadlineMs = startedAtMs + timeoutMs;
        long attemptBytesRead = 0L;
        boolean success = false;
        while (attemptBytesRead < readLimitBytes) {
            if (isCancelled(cancelSignal)) {
                break;
            }
            long nowMs = SystemClock.elapsedRealtime();
            long remainingTimeMs = deadlineMs - nowMs;
            if (remainingTimeMs <= 0L) {
                break;
            }
            long remainingBytes = readLimitBytes - attemptBytesRead;
            int requestTimeoutMs = (int) Math.max(1L, remainingTimeMs);
            final long baseAttemptBytes = attemptBytesRead;
            SocksHttpResult result = requestHttpViaSocks(
                context,
                "127.0.0.1",
                localPort,
                resolveLocalSocksUsername(request.xraySettings),
                resolveLocalSocksPassword(request.xraySettings),
                DOWNLOAD_TEST_URL_PREFIX + singleSuccessBytes,
                requestTimeoutMs,
                requestTimeoutMs,
                remainingBytes,
                (requestBytes, totalAttemptLimit) -> {
                    long totalBytes = baseAttemptBytes + requestBytes;
                    long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - startedAtMs);
                    long speed = (totalBytes * 1000L) / elapsed;
                    emitDownloadProgress(progress, request, attempt, attemptCount, totalBytes, readLimitBytes, speed);
                }
            );
            attemptBytesRead += result.bytesRead;
            if (hasSuccessfulTrafficResponse(result)) {
                success = true;
            }
            if (attemptBytesRead >= readLimitBytes || result.bytesRead <= 0L) {
                break;
            }
        }
        long elapsedMs = Math.max(1L, SystemClock.elapsedRealtime() - startedAtMs);
        long averageSpeedBytesPerSecond = (attemptBytesRead * 1000L) / elapsedMs;
        return new DownloadResult(success, attemptBytesRead, averageSpeedBytesPerSecond);
    }

    @FunctionalInterface
    private interface DownloadProgress {
        void onBytesRead(long bytesRead, long targetBytes);
    }

    @NonNull
    private static SocksHttpResult requestHttpViaSocks(
        @NonNull Context context,
        @NonNull String proxyHost,
        int proxyPort,
        @Nullable String username,
        @Nullable String password,
        @NonNull String urlValue,
        int connectTimeoutMs,
        int readTimeoutMs,
        long maxBodyBytes
    ) {
        return requestHttpViaSocks(
            context,
            proxyHost,
            proxyPort,
            username,
            password,
            urlValue,
            connectTimeoutMs,
            readTimeoutMs,
            maxBodyBytes,
            null
        );
    }

    @NonNull
    private static SocksHttpResult requestHttpViaSocks(
        @NonNull Context context,
        @NonNull String proxyHost,
        int proxyPort,
        @Nullable String username,
        @Nullable String password,
        @NonNull String urlValue,
        int connectTimeoutMs,
        int readTimeoutMs,
        long maxBodyBytes,
        @Nullable DownloadProgress progress
    ) {
        Socket socket = null;
        BufferedOutputStream outputStream = null;
        BufferedInputStream inputStream = null;
        int responseCode = -1;
        long bytesRead = 0L;
        try {
            URL url = new URL(urlValue);
            String scheme = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
            boolean tls = TextUtils.equals(scheme, "https");
            String targetHost = url.getHost();
            int targetPort = url.getPort() > 0 ? url.getPort() : tls ? 443 : 80;
            String path = TextUtils.isEmpty(url.getFile()) ? "/" : url.getFile();
            socket = openPreparedSocksSocket(
                proxyHost,
                proxyPort,
                targetHost,
                targetPort,
                username,
                password,
                connectTimeoutMs,
                readTimeoutMs,
                tls
            );
            outputStream = new BufferedOutputStream(socket.getOutputStream());
            inputStream = new BufferedInputStream(socket.getInputStream());

            String request =
                "GET " +
                path +
                " HTTP/1.1\r\n" +
                "Host: " +
                targetHost +
                "\r\n" +
                "User-Agent: " +
                resolveUserAgent(context) +
                "\r\n" +
                "Accept: */*\r\n" +
                "Accept-Encoding: identity\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            outputStream.write(request.getBytes(StandardCharsets.US_ASCII));
            outputStream.flush();

            String statusLine = readAsciiLine(inputStream, 4096);
            responseCode = parseHttpStatusCode(statusLine);
            if (responseCode <= 0) {
                return SocksHttpResult.failed();
            }
            while (true) {
                String header = readAsciiLine(inputStream, 16 * 1024);
                if (header == null || header.length() == 0) {
                    break;
                }
            }
            if (maxBodyBytes <= 0 || responseCode == 204 || responseCode == 304) {
                return new SocksHttpResult(responseCode >= 200 && responseCode < 400, responseCode, 0L);
            }

            byte[] buffer = new byte[16 * 1024];
            while (bytesRead < maxBodyBytes) {
                int limit = (int) Math.min(buffer.length, maxBodyBytes - bytesRead);
                int read;
                try {
                    read = inputStream.read(buffer, 0, limit);
                } catch (SocketTimeoutException timeout) {
                    return new SocksHttpResult(false, responseCode, bytesRead);
                } catch (IOException bodyReadFailure) {
                    if (responseCode > 0 && bytesRead > 0L) {
                        return new SocksHttpResult(false, responseCode, bytesRead);
                    }
                    throw bodyReadFailure;
                }
                if (read == -1) {
                    break;
                }
                bytesRead += read;
                if (progress != null) {
                    progress.onBytesRead(bytesRead, maxBodyBytes);
                }
            }
            return new SocksHttpResult(responseCode >= 200 && responseCode < 400, responseCode, bytesRead);
        } catch (IOException | IllegalArgumentException ignored) {
            if (responseCode > 0 && bytesRead > 0L) {
                return new SocksHttpResult(false, responseCode, bytesRead);
            }
            return SocksHttpResult.failed();
        } finally {
            closeQuietly(inputStream);
            closeQuietly(outputStream);
            closeQuietly(socket);
        }
    }

    @NonNull
    private static Socket openPreparedSocksSocket(
        @NonNull String proxyHost,
        int proxyPort,
        @NonNull String targetHost,
        int targetPort,
        @Nullable String username,
        @Nullable String password,
        int connectTimeoutMs,
        int readTimeoutMs,
        boolean tls
    ) throws IOException {
        Socket socket = null;
        try {
            socket = openSocksTunnel(
                proxyHost,
                proxyPort,
                targetHost,
                targetPort,
                username,
                password,
                connectTimeoutMs,
                readTimeoutMs
            );
            if (tls) {
                socket = wrapTlsSocket(socket, targetHost, targetPort);
                socket.setSoTimeout(readTimeoutMs);
            }
            Socket preparedSocket = socket;
            socket = null;
            return preparedSocket;
        } finally {
            closeQuietly(socket);
        }
    }

    @NonNull
    private static Socket openSocksTunnel(
        @NonNull String proxyHost,
        int proxyPort,
        @NonNull String targetHost,
        int targetPort,
        @Nullable String username,
        @Nullable String password,
        int connectTimeoutMs,
        int readTimeoutMs
    ) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
        byte[] usernameBytes = bytesForSocksAuth(username);
        byte[] passwordBytes = bytesForSocksAuth(password);
        boolean auth = usernameBytes.length > 0 && passwordBytes.length > 0;

        outputStream.write(
            new byte[] {
                SOCKS5_VERSION,
                SOCKS5_COMMAND_CONNECT,
                (byte) (auth ? SOCKS5_METHOD_USERNAME_PASSWORD : SOCKS5_METHOD_NO_AUTH),
            }
        );
        outputStream.flush();
        int version = readByte(inputStream);
        int method = readByte(inputStream);
        if (version != SOCKS5_VERSION || method == SOCKS5_METHOD_NOT_ACCEPTABLE) {
            throw new IOException("SOCKS5 authentication method rejected");
        }
        if (method == SOCKS5_METHOD_USERNAME_PASSWORD) {
            outputStream.write(SOCKS5_AUTH_VERSION);
            outputStream.write(usernameBytes.length);
            outputStream.write(usernameBytes);
            outputStream.write(passwordBytes.length);
            outputStream.write(passwordBytes);
            outputStream.flush();
            int authVersion = readByte(inputStream);
            int authStatus = readByte(inputStream);
            if (authVersion != SOCKS5_AUTH_VERSION || authStatus != SOCKS5_METHOD_NO_AUTH) {
                throw new IOException("SOCKS5 username/password rejected");
            }
        } else if (method != SOCKS5_METHOD_NO_AUTH) {
            throw new IOException("Unsupported SOCKS5 method: " + method);
        }

        byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length == 0 || hostBytes.length > SOCKS5_HOST_MAX_LENGTH) {
            throw new IOException("Invalid SOCKS target host");
        }
        outputStream.write(
            new byte[] {
                SOCKS5_VERSION,
                SOCKS5_COMMAND_CONNECT,
                SOCKS5_METHOD_NO_AUTH,
                SOCKS5_ADDRESS_TYPE_DOMAIN,
                (byte) hostBytes.length,
            }
        );
        outputStream.write(hostBytes);
        outputStream.write((targetPort >>> 8) & 0xff);
        outputStream.write(targetPort & 0xff);
        outputStream.flush();

        int replyVersion = readByte(inputStream);
        int replyCode = readByte(inputStream);
        readByte(inputStream);
        int addressType = readByte(inputStream);
        if (replyVersion != SOCKS5_VERSION || replyCode != SOCKS5_METHOD_NO_AUTH) {
            throw new IOException("SOCKS5 connect failed: " + replyCode);
        }
        int addressBytes;
        if (addressType == SOCKS5_ADDRESS_TYPE_IPV4) {
            addressBytes = IPV4_ADDRESS_LENGTH;
        } else if (addressType == SOCKS5_ADDRESS_TYPE_IPV6) {
            addressBytes = IPV6_ADDRESS_LENGTH;
        } else if (addressType == SOCKS5_ADDRESS_TYPE_DOMAIN) {
            addressBytes = readByte(inputStream);
        } else {
            throw new IOException("Invalid SOCKS5 bind address type: " + addressType);
        }
        skipFully(inputStream, addressBytes + 2);
        return socket;
    }

    @NonNull
    private static Socket wrapTlsSocket(@NonNull Socket socket, @NonNull String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(socket, host, port, true);
        try {
            SSLParameters parameters = sslSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (!isIpLiteral(host)) {
                parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
            }
            sslSocket.setSSLParameters(parameters);
        } catch (IllegalArgumentException | UnsupportedOperationException ignored) {}
        sslSocket.startHandshake();
        return sslSocket;
    }

    @NonNull
    private static byte[] bytesForSocksAuth(@Nullable String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return new byte[0];
        }
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= SOCKS5_HOST_MAX_LENGTH) {
            return bytes;
        }
        byte[] truncated = new byte[SOCKS5_HOST_MAX_LENGTH];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);
        return truncated;
    }

    private static int readByte(@NonNull BufferedInputStream inputStream) throws IOException {
        int value = inputStream.read();
        if (value < 0) {
            throw new IOException("Unexpected EOF");
        }
        return value;
    }

    private static void skipFully(@NonNull BufferedInputStream inputStream, int bytes) throws IOException {
        int remaining = Math.max(0, bytes);
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped < MIN_SKIPPED_BYTES) {
                readByte(inputStream);
                skipped = MIN_SKIPPED_BYTES;
            }
            remaining -= (int) skipped;
        }
    }

    @Nullable
    private static String readAsciiLine(@NonNull BufferedInputStream inputStream, int maxBytes) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < maxBytes; index++) {
            int value = inputStream.read();
            if (value < 0) {
                return builder.length() > 0 ? builder.toString() : null;
            }
            if (value == LINE_FEED) {
                break;
            }
            if (value != CARRIAGE_RETURN) {
                builder.append((char) value);
            }
        }
        return builder.toString();
    }

    private static int parseHttpStatusCode(@Nullable String statusLine) {
        if (statusLine == null || !statusLine.startsWith("HTTP/")) {
            return -1;
        }
        String[] parts = statusLine.split(" ", HTTP_STATUS_PARTS_LIMIT);
        if (parts.length < HTTP_STATUS_PARTS_MIN) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean isIpLiteral(@NonNull String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d+(\\.\\d+){3}");
    }

    @Nullable
    private static String resolveLocalSocksUsername(@Nullable XraySettings settings) {
        if (settings == null || !settings.localProxyAuthEnabled) {
            return null;
        }
        return settings.localProxyUsername;
    }

    @Nullable
    private static String resolveLocalSocksPassword(@Nullable XraySettings settings) {
        if (settings == null || !settings.localProxyAuthEnabled) {
            return null;
        }
        return settings.localProxyPassword;
    }

    @NonNull
    private static String resolveUserAgent(@NonNull Context context) {
        try {
            String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            if (!TextUtils.isEmpty(versionName)) {
                return "WINGSV/" + versionName;
            }
        } catch (Exception ignored) {}
        return "WINGSV";
    }

    private static boolean meetsDownloadTarget(long actualBytes, long targetBytes) {
        long normalizedTarget = Math.max(1L, targetBytes);
        long tolerance = Math.max(64L * 1024L, normalizedTarget / 50L);
        tolerance = Math.min(256L * 1024L, tolerance);
        long requiredBytes = Math.max(1L, normalizedTarget - tolerance);
        return Math.max(0L, actualBytes) >= requiredBytes;
    }

    private static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {}
    }

    private static final class SocksHttpResult {

        final boolean success;
        final int responseCode;
        final long bytesRead;

        SocksHttpResult(boolean success, int responseCode, long bytesRead) {
            this.success = success;
            this.responseCode = responseCode;
            this.bytesRead = Math.max(0L, bytesRead);
        }

        static SocksHttpResult failed() {
            return new SocksHttpResult(false, -1, 0L);
        }
    }

    private static final class ProbeResult {

        final boolean success;
        final int responseCode;
        final long bytesRead;

        ProbeResult(boolean success, int responseCode, long bytesRead) {
            this.success = success;
            this.responseCode = responseCode;
            this.bytesRead = Math.max(0L, bytesRead);
        }

        static ProbeResult failed() {
            return new ProbeResult(false, 0, 0L);
        }
    }

    private static final class DownloadResult {

        final boolean success;
        final long bytesRead;
        final long averageSpeedBytesPerSecond;

        DownloadResult(boolean success, long bytesRead, long averageSpeedBytesPerSecond) {
            this.success = success;
            this.bytesRead = Math.max(0L, bytesRead);
            this.averageSpeedBytesPerSecond = Math.max(0L, averageSpeedBytesPerSecond);
        }
    }
}
