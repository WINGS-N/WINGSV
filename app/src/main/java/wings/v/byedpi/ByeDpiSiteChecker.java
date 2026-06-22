package wings.v.byedpi;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import wings.v.service.ProxyTunnelService;

/**
 * Counts how many test requests reach their target through the local ByeDPI
 * SOCKS5 proxy. The handshake (including RFC 1929 username/password auth) is
 * done by hand instead of via java.net Proxy.Type.SOCKS, whose SOCKS5
 * user/pass path is unreliable on Android and fails every request with
 * "authentication failed" when the local proxy is started with -socks-user.
 */
@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.CommentRequired",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LawOfDemeter",
        "PMD.SignatureDeclareThrowsException",
        "PMD.LooseCoupling",
        "PMD.CognitiveComplexity",
        "PMD.CyclomaticComplexity",
        "PMD.NPathComplexity",
        "PMD.ExcessiveMethodLength",
        "PMD.PrematureDeclaration",
        "PMD.OnlyOneReturn",
        "PMD.AvoidInstantiatingObjectsInLoops",
        "PMD.CloseResource",
        "PMD.NullAssignment",
        "PMD.ShortVariable",
        "PMD.AvoidLiteralsInIfCondition",
        "PMD.ConfusingTernary",
    }
)
public final class ByeDpiSiteChecker {

    private static final int SOCKS5_VERSION = 0x05;
    private static final int SOCKS5_METHOD_NO_AUTH = 0x00;
    private static final int SOCKS5_METHOD_USERNAME_PASSWORD = 0x02;
    private static final int SOCKS5_METHOD_NOT_ACCEPTABLE = 0xff;
    private static final int SOCKS5_AUTH_VERSION = 0x01;
    private static final int SOCKS5_AUTH_OK = 0x00;
    private static final int SOCKS5_COMMAND_CONNECT = 0x01;
    private static final int SOCKS5_RESERVED = 0x00;
    private static final int SOCKS5_REPLY_OK = 0x00;
    private static final int SOCKS5_ADDRESS_TYPE_IPV4 = 0x01;
    private static final int SOCKS5_ADDRESS_TYPE_DOMAIN = 0x03;
    private static final int SOCKS5_ADDRESS_TYPE_IPV6 = 0x04;
    private static final int SOCKS5_HOST_MAX_LENGTH = 255;
    private static final int IPV4_ADDRESS_LENGTH = 4;
    private static final int IPV6_ADDRESS_LENGTH = 16;
    private static final int HTTPS_PORT = 443;
    private static final int HTTP_PORT = 80;
    private static final int STATUS_LINE_MAX = 256;

    private ByeDpiSiteChecker() {}

    public static int countSuccessfulRequests(
        @NonNull List<String> sites,
        int requestsCount,
        int timeoutSeconds,
        int concurrencyLimit,
        @NonNull String proxyHost,
        int proxyPort,
        @NonNull String proxyUsername,
        @NonNull String proxyPassword
    ) throws Exception {
        AtomicReference<String> firstError = new AtomicReference<>();
        try (ExecutorScope executorScope = new ExecutorScope(Math.max(1, concurrencyLimit))) {
            ArrayList<Future<Integer>> futures = new ArrayList<>();
            for (String site : sites) {
                futures.add(
                    executorScope.executor.submit(() ->
                        checkSiteAccess(
                            site,
                            requestsCount,
                            timeoutSeconds,
                            proxyHost,
                            proxyPort,
                            proxyUsername,
                            proxyPassword,
                            firstError
                        )
                    )
                );
            }
            int successCount = 0;
            for (Future<Integer> future : futures) {
                successCount += future.get();
            }
            // Surface why a strategy scored zero: silent zeros are indistinguishable
            // from a dead proxy. One line per strategy that failed every request.
            if (successCount == 0 && firstError.get() != null) {
                ProxyTunnelService.writeRuntimeLogLine("[byedpi-test] request error: " + firstError.get());
            }
            return successCount;
        }
    }

    private static final class ExecutorScope implements AutoCloseable {

        private final ExecutorService executor;

        private ExecutorScope(int threadCount) {
            executor = Executors.newFixedThreadPool(threadCount);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static int checkSiteAccess(
        String site,
        int requestsCount,
        int timeoutSeconds,
        String proxyHost,
        int proxyPort,
        String proxyUsername,
        String proxyPassword,
        AtomicReference<String> firstError
    ) {
        String formattedUrl = site.startsWith("http://") || site.startsWith("https://") ? site : "https://" + site;
        URL url;
        try {
            url = new URL(formattedUrl);
        } catch (java.net.MalformedURLException ignored) {
            firstError.compareAndSet(null, site + ": malformed url");
            return 0;
        }
        boolean https = "https".equalsIgnoreCase(url.getProtocol());
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : (https ? HTTPS_PORT : HTTP_PORT);
        String path = url.getFile();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        int timeoutMs = Math.max(1, timeoutSeconds) * 1000;

        int responseCount = 0;
        for (int attempt = 0; attempt < Math.max(1, requestsCount); attempt++) {
            Socket socket = null;
            try {
                socket = openSocksTunnel(
                    proxyHost,
                    proxyPort,
                    host,
                    port,
                    proxyUsername,
                    proxyPassword,
                    timeoutMs,
                    timeoutMs
                );
                Socket stream = https ? wrapTlsSocket(socket, host, port) : socket;
                sendHttpGet(stream, host, path);
                if (readHttpStatus(stream) > 0) {
                    responseCount++;
                } else {
                    firstError.compareAndSet(null, site + ": no HTTP response");
                }
            } catch (IOException error) {
                firstError.compareAndSet(
                    null,
                    site + ": " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                );
            } finally {
                closeQuietly(socket);
            }
        }
        return responseCount;
    }

    private static void sendHttpGet(@NonNull Socket socket, @NonNull String host, @NonNull String path)
        throws IOException {
        String request =
            "GET " +
            path +
            " HTTP/1.1\r\n" +
            "Host: " +
            host +
            "\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Accept: */*\r\n" +
            "Connection: close\r\n\r\n";
        OutputStream outputStream = socket.getOutputStream();
        outputStream.write(request.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /** Reads the HTTP status line and returns the status code, or -1 if absent. */
    private static int readHttpStatus(@NonNull Socket socket) throws IOException {
        InputStream inputStream = socket.getInputStream();
        StringBuilder line = new StringBuilder(STATUS_LINE_MAX);
        int c = inputStream.read();
        while (c != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r' && line.length() < STATUS_LINE_MAX) {
                line.append((char) c);
            }
            c = inputStream.read();
        }
        String status = line.toString();
        if (!status.startsWith("HTTP/")) {
            return -1;
        }
        int firstSpace = status.indexOf(' ');
        if (firstSpace < 0) {
            return -1;
        }
        int secondSpace = status.indexOf(' ', firstSpace + 1);
        String code =
            secondSpace > firstSpace ? status.substring(firstSpace + 1, secondSpace) : status.substring(firstSpace + 1);
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException ignored) {
            return -1;
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
        boolean ready = false;
        try {
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
            byte[] usernameBytes = bytesForSocksAuth(username);
            byte[] passwordBytes = bytesForSocksAuth(password);
            boolean auth = usernameBytes.length > 0 && passwordBytes.length > 0;

            outputStream.write(
                new byte[] {
                    (byte) SOCKS5_VERSION,
                    (byte) 1,
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
                if (authVersion != SOCKS5_AUTH_VERSION || authStatus != SOCKS5_AUTH_OK) {
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
                    (byte) SOCKS5_VERSION,
                    (byte) SOCKS5_COMMAND_CONNECT,
                    (byte) SOCKS5_RESERVED,
                    (byte) SOCKS5_ADDRESS_TYPE_DOMAIN,
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
            if (replyVersion != SOCKS5_VERSION || replyCode != SOCKS5_REPLY_OK) {
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
            ready = true;
            return socket;
        } finally {
            if (!ready) {
                closeQuietly(socket);
            }
        }
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

    private static boolean isIpLiteral(@Nullable String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
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
            if (skipped < 1L) {
                readByte(inputStream);
                remaining--;
            } else {
                remaining -= (int) skipped;
            }
        }
    }

    private static void closeQuietly(@Nullable Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
