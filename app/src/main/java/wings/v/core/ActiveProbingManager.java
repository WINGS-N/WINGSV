package wings.v.core;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import wings.v.ActiveProbingSettingsActivity;
import wings.v.R;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public final class ActiveProbingManager {

    public static final String KEY_OPEN_SETTINGS = "pref_open_active_probing_settings";
    public static final String KEY_OPEN_TARGETS = "pref_open_active_probing_targets";
    public static final String KEY_TUNNEL_ENABLED = "pref_active_probing_tunnel_enabled";
    public static final String KEY_VK_TURN_ENABLED = "pref_active_probing_vk_turn_enabled";
    public static final String KEY_BACKGROUND_ENABLED = "pref_active_probing_background_enabled";
    public static final String KEY_XRAY_FALLBACK_BACKEND = "pref_active_probing_xray_fallback_backend";
    public static final String KEY_RESTORE_BACKEND = "pref_active_probing_restore_backend";
    public static final String KEY_URLS = "pref_active_probing_urls";
    public static final String KEY_INTERVAL_SECONDS = "pref_active_probing_interval_seconds";
    public static final String KEY_TIMEOUT_SECONDS = "pref_active_probing_timeout_seconds";

    private static final String NOTIFICATION_CHANNEL_ID = "wingsv_active_probing";
    private static final int NOTIFICATION_ID = 4;
    private static final int DEFAULT_INTERVAL_SECONDS = 20;
    private static final int DEFAULT_TIMEOUT_SECONDS = 2;

    private ActiveProbingManager() {}

    public static final class Settings {

        public boolean tunnelEnabled;
        public boolean vkTurnEnabled;
        public boolean backgroundEnabled;
        public BackendType xrayFallbackBackend = BackendType.VK_TURN_WIREGUARD;
        public String rawUrls = serializeUrls(defaultUrls(null));
        public List<String> urls = defaultUrls(null);
        public int intervalSeconds = DEFAULT_INTERVAL_SECONDS;
        public int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        public long intervalMs() {
            return TimeUnit.SECONDS.toMillis(Math.max(1, intervalSeconds));
        }

        public long timeoutMs() {
            return TimeUnit.SECONDS.toMillis(Math.max(1, timeoutSeconds));
        }
    }

    public static final class ProbeResult {

        public final boolean hasUsablePhysicalNetwork;
        public final int totalCount;
        public final int reachableCount;
        public final List<String> failedTargets;

        ProbeResult(boolean hasUsablePhysicalNetwork, int totalCount, int reachableCount, List<String> failedTargets) {
            this.hasUsablePhysicalNetwork = hasUsablePhysicalNetwork;
            this.totalCount = Math.max(totalCount, 0);
            this.reachableCount = Math.max(reachableCount, 0);
            this.failedTargets = failedTargets != null ? new ArrayList<>(failedTargets) : new ArrayList<>();
        }

        public boolean shouldFallback() {
            return hasUsablePhysicalNetwork && totalCount > 0 && reachableCount <= 0;
        }

        @NonNull
        public String failedTargetsSummary() {
            if (failedTargets.isEmpty()) {
                return "";
            }
            ArrayList<String> labels = new ArrayList<>();
            for (String target : failedTargets) {
                if (TextUtils.isEmpty(target)) {
                    continue;
                }
                String normalized = target.trim();
                if (normalized.startsWith("https://")) {
                    normalized = normalized.substring("https://".length());
                } else if (normalized.startsWith("http://")) {
                    normalized = normalized.substring("http://".length());
                }
                int slashIndex = normalized.indexOf('/');
                if (slashIndex >= 0) {
                    normalized = normalized.substring(0, slashIndex);
                }
                if (!TextUtils.isEmpty(normalized)) {
                    labels.add(normalized);
                }
            }
            if (labels.isEmpty()) {
                return "";
            }
            if (labels.size() == 1) {
                return labels.get(0);
            }
            if (labels.size() == 2) {
                return labels.get(0) + ", " + labels.get(1);
            }
            return labels.get(0) + ", " + labels.get(1) + " +" + (labels.size() - 2);
        }
    }

    public static Settings getSettings(@Nullable Context context) {
        Settings settings = new Settings();
        if (context == null) {
            return settings;
        }
        Context appContext = context.getApplicationContext();
        settings.tunnelEnabled = prefs(appContext).getBoolean(KEY_TUNNEL_ENABLED, false);
        settings.vkTurnEnabled = prefs(appContext).getBoolean(KEY_VK_TURN_ENABLED, false);
        settings.backgroundEnabled = prefs(appContext).getBoolean(KEY_BACKGROUND_ENABLED, false);
        settings.xrayFallbackBackend = normalizeXrayFallbackBackend(
            BackendType.fromPrefValue(
                prefs(appContext).getString(KEY_XRAY_FALLBACK_BACKEND, BackendType.VK_TURN_WIREGUARD.prefValue)
            )
        );
        String storedUrls = prefs(appContext).getString(KEY_URLS, null);
        settings.rawUrls = storedUrls == null ? serializeUrls(defaultUrls(appContext)) : trim(storedUrls);
        settings.urls = parseUrls(appContext, settings.rawUrls);
        settings.intervalSeconds = parseInt(
            prefs(appContext).getString(KEY_INTERVAL_SECONDS, String.valueOf(DEFAULT_INTERVAL_SECONDS)),
            DEFAULT_INTERVAL_SECONDS
        );
        settings.timeoutSeconds = parseInt(
            prefs(appContext).getString(KEY_TIMEOUT_SECONDS, String.valueOf(DEFAULT_TIMEOUT_SECONDS)),
            DEFAULT_TIMEOUT_SECONDS
        );
        return settings;
    }

    public static boolean isTunnelProbingAvailable(@Nullable Context context) {
        if (context == null) {
            return false;
        }
        BackendType backendType = XrayStore.getBackendType(context);
        return (backendType != null && (backendType.usesXrayCore() || backendType.isPlainBackend()));
    }

    @NonNull
    public static BackendType normalizeXrayFallbackBackend(@Nullable BackendType backendType) {
        if (backendType == BackendType.AMNEZIAWG || backendType == BackendType.AMNEZIAWG_PLAIN) {
            return BackendType.AMNEZIAWG;
        }
        return BackendType.VK_TURN_WIREGUARD;
    }

    @NonNull
    public static String getBackendLabel(@Nullable Context context, @Nullable BackendType backendType) {
        BackendType resolved = backendType == null ? BackendType.VK_TURN_WIREGUARD : backendType;
        if (context == null) {
            if (resolved != null && resolved.usesXrayCore()) {
                return "Xray";
            }
            if (resolved == BackendType.AMNEZIAWG) {
                return "VK TURN + AmneziaWG";
            }
            if (resolved == BackendType.AMNEZIAWG_PLAIN) {
                return "AmneziaWG";
            }
            if (resolved == BackendType.WIREGUARD) {
                return "WireGuard";
            }
            return "VK TURN + WireGuard";
        }
        if (resolved != null && resolved.usesXrayCore()) {
            return context.getString(R.string.backend_xray_title);
        }
        if (resolved == BackendType.AMNEZIAWG) {
            return context.getString(R.string.backend_amneziawg_title);
        }
        if (resolved == BackendType.AMNEZIAWG_PLAIN) {
            return context.getString(R.string.backend_amneziawg_plain_title);
        }
        if (resolved == BackendType.WIREGUARD) {
            return context.getString(R.string.backend_wireguard_title);
        }
        return context.getString(R.string.backend_vk_turn_wireguard_title);
    }

    @Nullable
    public static BackendType getRestoreBackend(@Nullable Context context) {
        if (context == null) {
            return null;
        }
        String rawValue = prefs(context.getApplicationContext()).getString(KEY_RESTORE_BACKEND, null);
        if (TextUtils.isEmpty(rawValue)) {
            return null;
        }
        BackendType backendType = BackendType.fromPrefValue(rawValue);
        return backendType == BackendType.VK_TURN_WIREGUARD && !TextUtils.equals(rawValue, backendType.prefValue)
            ? null
            : backendType;
    }

    public static void setRestoreBackend(@Nullable Context context, @Nullable BackendType backendType) {
        if (context == null) {
            return;
        }
        if (backendType == null) {
            clearRestoreBackend(context);
            return;
        }
        prefs(context.getApplicationContext()).edit().putString(KEY_RESTORE_BACKEND, backendType.prefValue).commit();
    }

    public static void clearRestoreBackend(@Nullable Context context) {
        if (context == null) {
            return;
        }
        prefs(context.getApplicationContext()).edit().remove(KEY_RESTORE_BACKEND).commit();
    }

    @NonNull
    public static String buildUrlsSummary(@Nullable String rawValue) {
        List<String> urls = parseUrls(null, rawValue);
        if (urls.isEmpty()) {
            return "Нет сайтов";
        }
        if (urls.size() == 1) {
            return urls.get(0);
        }
        return urls.size() + " URL • " + urls.get(0) + " • " + urls.get(1);
    }

    @NonNull
    public static List<String> getUrls(@Nullable Context context) {
        return getSettings(context).urls;
    }

    public static void saveUrls(@Nullable Context context, @Nullable List<String> urls) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        prefs(appContext).edit().putString(KEY_URLS, serializeUrls(urls)).commit();
        ActiveProbingBackgroundScheduler.refresh(appContext);
    }

    @NonNull
    public static String normalizeUrl(@Nullable String rawValue) {
        List<String> urls = parseUrls(null, rawValue);
        return urls.isEmpty() ? "" : urls.get(0);
    }

    @NonNull
    public static ProbeResult runDirectProbes(@Nullable Context context, @Nullable Settings settings) {
        Settings resolvedSettings = settings != null ? settings : getSettings(context);
        List<String> urls =
            resolvedSettings.urls != null ? new ArrayList<>(resolvedSettings.urls) : defaultUrls(context);
        if (context == null || urls.isEmpty()) {
            return new ProbeResult(false, urls.size(), 0, urls);
        }
        Network network = findUsablePhysicalNetwork(context.getApplicationContext());
        if (network == null) {
            return new ProbeResult(false, urls.size(), 0, urls);
        }

        int timeoutMs = (int) Math.max(500L, resolvedSettings.timeoutMs());
        int successCount = 0;
        ArrayList<String> failedTargets = new ArrayList<>();
        try (ExecutorScope executorScope = new ExecutorScope(Math.max(1, Math.min(urls.size(), 4)))) {
            ArrayList<Future<Boolean>> futures = new ArrayList<>();
            for (String url : urls) {
                futures.add(executorScope.executor.submit(new ProbeTask(network, url, timeoutMs)));
            }
            for (int index = 0; index < urls.size(); index++) {
                boolean success;
                try {
                    success = futures.get(index).get(timeoutMs + 750L, TimeUnit.MILLISECONDS);
                } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException ignored) {
                    success = false;
                }
                if (success) {
                    successCount++;
                } else {
                    failedTargets.add(urls.get(index));
                }
            }
        }
        return new ProbeResult(true, urls.size(), successCount, failedTargets);
    }

    private static final class ExecutorScope implements AutoCloseable {

        private final ExecutorService executor;

        private ExecutorScope(int threadCount) {
            this.executor = Executors.newFixedThreadPool(Math.max(1, threadCount));
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    public static void showTunnelFallbackNotification(
        @Nullable Context context,
        @Nullable ProbeResult result,
        @Nullable BackendType fromBackend,
        @Nullable BackendType backendType
    ) {
        showTriggerNotification(
            context,
            R.string.active_probing_notification_tunnel_title,
            R.string.active_probing_notification_tunnel_text,
            result,
            fromBackend,
            backendType
        );
    }

    public static void showBackgroundFallbackNotification(
        @Nullable Context context,
        @Nullable ProbeResult result,
        @Nullable BackendType backendType
    ) {
        showTriggerNotification(
            context,
            R.string.active_probing_notification_background_title,
            R.string.active_probing_notification_background_text,
            result,
            null,
            backendType
        );
    }

    public static void showRestoreNotification(
        @Nullable Context context,
        @Nullable ProbeResult result,
        @Nullable BackendType fromBackend,
        @Nullable BackendType restoreBackend
    ) {
        showTriggerNotification(
            context,
            R.string.active_probing_notification_restore_title,
            R.string.active_probing_notification_restore_text,
            result,
            fromBackend,
            restoreBackend
        );
    }

    private static void showTriggerNotification(
        @Nullable Context context,
        int titleRes,
        int textRes,
        @Nullable ProbeResult result,
        @Nullable BackendType fromBackend,
        @Nullable BackendType backendType
    ) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        NotificationManager notificationManager = appContext.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        createNotificationChannel(appContext, notificationManager);
        String failedTargets = result != null ? result.failedTargetsSummary() : "";
        String resolvedFailedTargets = TextUtils.isEmpty(failedTargets)
            ? appContext.getString(R.string.active_probing_notification_targets_unknown)
            : failedTargets;
        String text =
            titleRes == R.string.active_probing_notification_background_title
                ? appContext.getString(textRes, resolvedFailedTargets, getBackendLabel(appContext, backendType))
                : appContext.getString(
                      textRes,
                      resolvedFailedTargets,
                      getBackendLabel(appContext, fromBackend),
                      getBackendLabel(appContext, backendType)
                  );
        Intent openIntent = ActiveProbingSettingsActivity.createIntent(appContext).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        PendingIntent pendingIntent = PendingIntent.getActivity(
            appContext,
            401,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(appContext.getString(titleRes))
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH);
        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {}
    }

    private static void createNotificationChannel(Context context, NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.active_probing_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.enableVibration(true);
        channel.setDescription(context.getString(R.string.active_probing_notification_channel_description));
        notificationManager.createNotificationChannel(channel);
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static Network findUsablePhysicalNetwork(Context context) {
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return null;
        }
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (isUsablePhysicalNetwork(connectivityManager, activeNetwork)) {
                return activeNetwork;
            }
            Network[] networks = connectivityManager.getAllNetworks();
            if (networks == null) {
                return null;
            }
            for (Network network : networks) {
                if (isUsablePhysicalNetwork(connectivityManager, network)) {
                    return network;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isUsablePhysicalNetwork(
        @Nullable ConnectivityManager connectivityManager,
        @Nullable Network network
    ) {
        if (connectivityManager == null || network == null) {
            return false;
        }
        try {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return false;
            }
            boolean physicalTransport =
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            if (!physicalTransport || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return false;
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            ) {
                return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @NonNull
    private static List<String> parseUrls(@Nullable Context context, @Nullable String rawValue) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String normalized = rawValue == null ? "" : rawValue.replace('\r', '\n');
        String[] parts = normalized.split("[\\n,;]+");
        for (String part : parts) {
            String candidate = trim(part);
            if (TextUtils.isEmpty(candidate)) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.US);
            if (lower.startsWith("https://") || lower.startsWith("http://")) {
                result.add(candidate);
            }
        }
        if (result.isEmpty() && TextUtils.isEmpty(trim(rawValue))) {
            result.addAll(defaultUrls(context));
        }
        return new ArrayList<>(result);
    }

    @NonNull
    private static List<String> defaultUrls(@Nullable Context context) {
        ArrayList<String> defaults = new ArrayList<>();
        if (context != null) {
            String[] values = context.getResources().getStringArray(R.array.active_probing_default_urls);
            for (String value : values) {
                String normalized = normalizeUrl(value);
                if (!TextUtils.isEmpty(normalized)) {
                    defaults.add(normalized);
                }
            }
        }
        if (defaults.isEmpty()) {
            defaults.add("https://1.1.1.1");
            defaults.add("https://github.com");
        }
        return defaults;
    }

    @NonNull
    private static String serializeUrls(@Nullable List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String url : urls) {
            String candidate = normalizeUrl(url);
            if (!TextUtils.isEmpty(candidate)) {
                normalized.add(candidate);
            }
        }
        return TextUtils.join("\n", normalized);
    }

    private static int parseInt(@Nullable String rawValue, int fallback) {
        if (TextUtils.isEmpty(rawValue)) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(rawValue.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @NonNull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    private static final class ProbeTask implements Callable<Boolean> {

        private final Network network;
        private final String urlValue;
        private final int timeoutMs;

        ProbeTask(Network network, String urlValue, int timeoutMs) {
            this.network = network;
            this.urlValue = urlValue;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public Boolean call() {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) network.openConnection(new URL(urlValue));
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setRequestProperty("Connection", "close");
                return connection.getResponseCode() > 0;
            } catch (Exception ignored) {
                return false;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }
}
