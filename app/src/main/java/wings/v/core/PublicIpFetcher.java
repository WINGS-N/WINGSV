package wings.v.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PublicIpFetcher {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Endpoint[] ENDPOINTS = new Endpoint[]{
            new Endpoint("https://ipwho.is/", EndpointType.IPWHO_IS),
            new Endpoint("https://ipapi.co/json/", EndpointType.IPAPI_CO),
            new Endpoint("https://ipinfo.io/json", EndpointType.IPINFO_IO)
    };

    private PublicIpFetcher() {
    }

    public static final class IpInfo {
        public final String ip;
        public final String country;
        public final String isp;

        public IpInfo(String ip, String country, String isp) {
            this.ip = sanitize(ip);
            this.country = sanitize(country);
            this.isp = sanitize(isp);
        }

        private static String sanitize(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    public interface Callback {
        void onResult(IpInfo value);
    }

    public interface Request {
        void cancel();
    }

    private enum EndpointType {
        IPWHO_IS,
        IPAPI_CO,
        IPINFO_IO
    }

    private static final class Endpoint {
        private final String url;
        private final EndpointType type;

        private Endpoint(String url, EndpointType type) {
            this.url = url;
            this.type = type;
        }
    }

    public static void fetchAsync(Callback callback) {
        fetchAsyncCancelable(callback);
    }

    public static void fetchAsync(Context context, boolean preferVpn, Callback callback) {
        fetchAsyncCancelable(context, preferVpn, callback);
    }

    public static Request fetchAsyncCancelable(Callback callback) {
        return fetchAsyncCancelable(null, false, callback);
    }

    public static Request fetchAsyncCancelable(Context context, boolean preferVpn, Callback callback) {
        AsyncRequest request = new AsyncRequest(callback);
        request.context = context != null ? context.getApplicationContext() : null;
        request.preferVpn = preferVpn;
        request.start();
        return request;
    }

    public static String fetchSync() {
        IpInfo info = fetchInfoSync();
        return info != null ? info.ip : null;
    }

    public static IpInfo fetchInfoSync() {
        return fetchInfoSync(null, false);
    }

    public static IpInfo fetchInfoSync(Context context, boolean preferVpn) {
        return fetchInfoSyncInternal(context != null ? context.getApplicationContext() : null, preferVpn, null, null);
    }

    private static IpInfo fetchInfoSyncInternal(Context context,
                                                boolean preferVpn,
                                                AtomicReference<HttpURLConnection> connectionRef,
                                                AtomicBoolean cancelled) {
        String resolvedIp = null;
        String resolvedCountry = null;
        String resolvedIsp = null;

        for (Endpoint endpoint : ENDPOINTS) {
            if (isCancelled(cancelled)) {
                return null;
            }
            IpInfo candidate = fetchFromEndpoint(context, preferVpn, endpoint, connectionRef, cancelled);
            if (candidate == null) {
                continue;
            }
            if (TextUtils.isEmpty(resolvedIp) && !TextUtils.isEmpty(candidate.ip)) {
                resolvedIp = candidate.ip;
            }
            if (TextUtils.isEmpty(resolvedCountry) && !TextUtils.isEmpty(candidate.country)) {
                resolvedCountry = candidate.country;
            }
            if (TextUtils.isEmpty(resolvedIsp) && !TextUtils.isEmpty(candidate.isp)) {
                resolvedIsp = candidate.isp;
            }
            if (!TextUtils.isEmpty(resolvedIp)
                    && !TextUtils.isEmpty(resolvedCountry)
                    && !TextUtils.isEmpty(resolvedIsp)) {
                break;
            }
        }

        if (TextUtils.isEmpty(resolvedIp)
                && TextUtils.isEmpty(resolvedCountry)
                && TextUtils.isEmpty(resolvedIsp)) {
            return null;
        }
        return new IpInfo(resolvedIp, resolvedCountry, resolvedIsp);
    }

    private static IpInfo fetchFromEndpoint(Context context,
                                            boolean preferVpn,
                                            Endpoint endpoint,
                                            AtomicReference<HttpURLConnection> connectionRef,
                                            AtomicBoolean cancelled) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint.url);
            connection = openConnection(context, preferVpn, url);
            if (connectionRef != null) {
                connectionRef.set(connection);
            }
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isCancelled(cancelled)) {
                        return null;
                    }
                    response.append(line);
                }
            }
            return parseIpInfo(endpoint.type, new JSONObject(response.toString()));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connectionRef != null) {
                connectionRef.set(null);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static IpInfo parseIpInfo(EndpointType endpointType, JSONObject jsonObject) {
        if (jsonObject == null) {
            return null;
        }
        switch (endpointType) {
            case IPWHO_IS:
                if (!jsonObject.optBoolean("success", true)) {
                    return null;
                }
                JSONObject connectionObject = jsonObject.optJSONObject("connection");
                return new IpInfo(
                        jsonObject.optString("ip", null),
                        jsonObject.optString("country", null),
                        connectionObject != null ? connectionObject.optString("isp", null) : null
                );
            case IPAPI_CO:
                return new IpInfo(
                        jsonObject.optString("ip", null),
                        jsonObject.optString("country_name", null),
                        firstNonEmpty(
                                jsonObject.optString("org", null),
                                jsonObject.optString("asn_org", null)
                        )
                );
            case IPINFO_IO:
                return new IpInfo(
                        jsonObject.optString("ip", null),
                        jsonObject.optString("country", null),
                        jsonObject.optString("org", null)
                );
            default:
                return null;
        }
    }

    private static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    private static String firstNonEmpty(String primary, String fallback) {
        return TextUtils.isEmpty(primary) ? fallback : primary;
    }

    private static HttpURLConnection openConnection(Context context, boolean preferVpn, URL url) throws Exception {
        Network vpnNetwork = preferVpn ? findVpnNetwork(context) : null;
        if (vpnNetwork != null) {
            return (HttpURLConnection) vpnNetwork.openConnection(url);
        }
        return (HttpURLConnection) url.openConnection();
    }

    private static Network findVpnNetwork(Context context) {
        if (context == null) {
            return null;
        }
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return null;
        }
        Network[] networks = connectivityManager.getAllNetworks();
        if (networks == null) {
            return null;
        }
        for (Network network : networks) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null) {
                continue;
            }
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue;
            }
            if (isVpnUsable(capabilities)) {
                return network;
            }
        }
        return null;
    }

    private static boolean isVpnUsable(NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
            return false;
        }
        return true;
    }

    private static final class AsyncRequest implements Request, Runnable {
        private final Callback callback;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<HttpURLConnection> connectionRef = new AtomicReference<>();
        private Context context;
        private boolean preferVpn;
        private volatile Future<?> future;

        private AsyncRequest(Callback callback) {
            this.callback = callback;
        }

        private void start() {
            future = EXECUTOR.submit(this);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            HttpURLConnection connection = connectionRef.getAndSet(null);
            if (connection != null) {
                connection.disconnect();
            }
            Future<?> activeFuture = future;
            if (activeFuture != null) {
                activeFuture.cancel(true);
            }
        }

        @Override
        public void run() {
            IpInfo value = fetchInfoSyncInternal(context, preferVpn, connectionRef, cancelled);
            if (cancelled.get()) {
                return;
            }
            MAIN_HANDLER.post(() -> {
                if (!cancelled.get()) {
                    callback.onResult(value);
                }
            });
        }
    }
}
