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

public final class ByeDpiSiteChecker {
    private ByeDpiSiteChecker() {
    }

    public static int countSuccessfulRequests(@NonNull List<String> sites,
                                              int requestsCount,
                                              int timeoutSeconds,
                                              int concurrencyLimit,
                                              @NonNull String proxyHost,
                                              int proxyPort) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, concurrencyLimit));
        try {
            ArrayList<Future<Integer>> futures = new ArrayList<>();
            for (String site : sites) {
                futures.add(executor.submit(() ->
                        checkSiteAccess(site, requestsCount, timeoutSeconds, proxyHost, proxyPort)
                ));
            }
            int successCount = 0;
            for (Future<Integer> future : futures) {
                successCount += future.get();
            }
            return successCount;
        } finally {
            executor.shutdownNow();
        }
    }

    private static int checkSiteAccess(String site,
                                       int requestsCount,
                                       int timeoutSeconds,
                                       String proxyHost,
                                       int proxyPort) {
        int responseCount = 0;
        String formattedUrl = site.startsWith("http://") || site.startsWith("https://")
                ? site
                : "https://" + site;
        URL url;
        try {
            url = new URL(formattedUrl);
        } catch (Exception ignored) {
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
                try (InputStream inputStream = responseCode >= 200 && responseCode <= 299
                        ? connection.getInputStream()
                        : connection.getErrorStream()) {
                    if (inputStream != null) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long limit = declaredLength > 0L ? declaredLength : 1024L * 1024L;
                        while (actualLength < limit
                                && (bytesRead = inputStream.read(buffer, 0, (int) Math.min(buffer.length, limit - actualLength))) != -1) {
                            actualLength += bytesRead;
                        }
                    }
                } catch (Exception ignored) {
                }
                if (declaredLength <= 0L || actualLength >= declaredLength) {
                    responseCount++;
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return responseCount;
    }
}
