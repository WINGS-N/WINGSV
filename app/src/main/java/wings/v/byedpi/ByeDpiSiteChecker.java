package wings.v.byedpi;

import androidx.annotation.NonNull;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import wings.v.core.SocksProxyAuthenticator;

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
        "PMD.PrematureDeclaration",
        "PMD.OnlyOneReturn",
        "PMD.AvoidInstantiatingObjectsInLoops",
    }
)
public final class ByeDpiSiteChecker {

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
        return SocksProxyAuthenticator.run(proxyHost, proxyPort, proxyUsername, proxyPassword, () ->
            countSuccessfulRequestsInternal(
                sites,
                requestsCount,
                timeoutSeconds,
                concurrencyLimit,
                proxyHost,
                proxyPort
            )
        );
    }

    private static int countSuccessfulRequestsInternal(
        @NonNull List<String> sites,
        int requestsCount,
        int timeoutSeconds,
        int concurrencyLimit,
        @NonNull String proxyHost,
        int proxyPort
    ) throws Exception {
        try (ExecutorScope executorScope = new ExecutorScope(Math.max(1, concurrencyLimit))) {
            ArrayList<Future<Integer>> futures = new ArrayList<>();
            for (String site : sites) {
                futures.add(
                    executorScope.executor.submit(() ->
                        checkSiteAccess(site, requestsCount, timeoutSeconds, proxyHost, proxyPort)
                    )
                );
            }
            int successCount = 0;
            for (Future<Integer> future : futures) {
                successCount += future.get();
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
        int proxyPort
    ) {
        int responseCount = 0;
        String formattedUrl = site.startsWith("http://") || site.startsWith("https://") ? site : "https://" + site;
        URL url;
        try {
            url = new URL(formattedUrl);
        } catch (java.net.MalformedURLException ignored) {
            return 0;
        }
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort));

        for (int attempt = 0; attempt < Math.max(1, requestsCount); attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) url.openConnection(proxy);
                connection.setConnectTimeout(timeoutSeconds * 1000);
                connection.setReadTimeout(timeoutSeconds * 1000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Connection", "close");
                int responseCode = connection.getResponseCode();
                long declaredLength = connection.getContentLengthLong();
                long actualLength = 0L;
                try (
                    InputStream inputStream =
                        responseCode >= 200 && responseCode <= 299
                            ? connection.getInputStream()
                            : connection.getErrorStream()
                ) {
                    if (inputStream != null) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long limit = declaredLength > 0L ? declaredLength : 1024L * 1024L;
                        while (actualLength < limit) {
                            bytesRead = inputStream.read(
                                buffer,
                                0,
                                (int) Math.min(buffer.length, limit - actualLength)
                            );
                            if (bytesRead == -1) {
                                break;
                            }
                            actualLength += bytesRead;
                        }
                    }
                } catch (java.io.IOException ignored) {}
                if (declaredLength <= 0L || actualLength >= declaredLength) {
                    responseCount++;
                }
            } catch (java.io.IOException ignored) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return responseCount;
    }
}
