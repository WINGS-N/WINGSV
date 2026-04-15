package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.SignatureDeclareThrowsException",
        "PMD.CommentRequired",
        "PMD.CommentDefaultAccessModifier",
        "PMD.GodClass",
        "PMD.CognitiveComplexity",
        "PMD.CyclomaticComplexity",
        "PMD.NPathComplexity",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.LooseCoupling",
        "PMD.AvoidInstantiatingObjectsInLoops",
    }
)
public final class XraySubscriptionUpdater {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final long MILLIS_PER_MINUTE = 60L * 1000L;
    private static final Pattern USERINFO_PART_PATTERN = Pattern.compile(
        "(upload|download|total|expire)\\s*=\\s*([0-9]+)",
        Pattern.CASE_INSENSITIVE
    );

    private XraySubscriptionUpdater() {}

    public static RefreshResult refreshAll(Context context) throws Exception {
        return refreshAll(context, null);
    }

    public static RefreshResult refreshAll(Context context, ProgressListener listener) throws Exception {
        return refreshAll(context, listener, true);
    }

    public static RefreshResult refreshAll(Context context, ProgressListener listener, boolean allowUniversalSeed)
        throws Exception {
        return refreshSubscriptions(context, listener, allowUniversalSeed, false);
    }

    public static RefreshResult refreshDue(Context context) throws Exception {
        return refreshDue(context, null);
    }

    public static RefreshResult refreshDue(Context context, ProgressListener listener) throws Exception {
        return refreshSubscriptions(context, listener, true, true);
    }

    private static RefreshResult refreshSubscriptions(
        Context context,
        ProgressListener listener,
        boolean allowUniversalSeed,
        boolean dueOnly
    ) throws Exception {
        List<XraySubscription> subscriptions = XrayStore.getSubscriptions(context, allowUniversalSeed);
        LinkedHashMap<String, XrayProfile> profiles = new LinkedHashMap<>();
        LinkedHashMap<String, List<XrayProfile>> existingProfilesBySubscription = new LinkedHashMap<>();
        for (XrayProfile existingProfile : XrayStore.getProfiles(context)) {
            if (existingProfile == null || TextUtils.isEmpty(existingProfile.rawLink)) {
                continue;
            }
            if (TextUtils.isEmpty(existingProfile.subscriptionId)) {
                profiles.put(XrayStore.getProfileStorageKey(existingProfile), existingProfile);
            } else {
                List<XrayProfile> subscriptionProfiles = existingProfilesBySubscription.computeIfAbsent(
                    existingProfile.subscriptionId,
                    ignored -> new ArrayList<>()
                );
                subscriptionProfiles.add(existingProfile);
            }
        }
        List<XraySubscription> updatedSubscriptions = new ArrayList<>();
        long now = System.currentTimeMillis();
        int defaultRefreshIntervalMinutes = Math.max(1, XrayStore.getRefreshIntervalMinutes(context));
        String firstError = null;
        boolean anySubscriptionUpdated = false;

        for (XraySubscription subscription : subscriptions) {
            if (subscription == null || TextUtils.isEmpty(subscription.url)) {
                continue;
            }
            if (dueOnly && !shouldRefreshSubscription(subscription, now, defaultRefreshIntervalMinutes)) {
                updatedSubscriptions.add(subscription);
                restoreExistingProfiles(existingProfilesBySubscription, profiles, subscription.id);
                continue;
            }
            if (listener != null) {
                listener.onSubscriptionStarted(subscription);
            }
            try {
                FetchResult fetched = fetch(context, subscription.url);
                for (XrayProfile profile : XraySubscriptionParser.parseProfiles(
                    fetched.body,
                    subscription.id,
                    subscription.title
                )) {
                    if (profile != null) {
                        profiles.put(XrayStore.getProfileStorageKey(profile), profile);
                    }
                }
                SubscriptionMetadata metadata = fetched.metadata.withFallback(subscription);
                updatedSubscriptions.add(
                    new XraySubscription(
                        subscription.id,
                        subscription.title,
                        subscription.url,
                        subscription.formatHint,
                        subscription.refreshIntervalMinutes,
                        subscription.autoUpdate,
                        now,
                        metadata.uploadBytes,
                        metadata.downloadBytes,
                        metadata.totalBytes,
                        metadata.expireAt
                    )
                );
                anySubscriptionUpdated = true;
                if (listener != null) {
                    listener.onSubscriptionFinished(subscription, null);
                }
            } catch (Exception error) {
                if (TextUtils.isEmpty(firstError)) {
                    firstError = error.getMessage();
                }
                updatedSubscriptions.add(subscription);
                List<XrayProfile> existingSubscriptionProfiles = existingProfilesBySubscription.get(subscription.id);
                if (existingSubscriptionProfiles != null) {
                    for (XrayProfile existingProfile : existingSubscriptionProfiles) {
                        if (existingProfile != null && !TextUtils.isEmpty(existingProfile.rawLink)) {
                            profiles.put(XrayStore.getProfileStorageKey(existingProfile), existingProfile);
                        }
                    }
                }
                if (listener != null) {
                    listener.onSubscriptionFinished(subscription, error.getMessage());
                }
            }
        }

        XrayStore.setSubscriptions(context, updatedSubscriptions);
        XrayStore.setProfiles(context, new ArrayList<>(profiles.values()));
        if (anySubscriptionUpdated) {
            XrayStore.setLastSubscriptionsRefreshAt(context, now);
        }
        XrayStore.setLastSubscriptionsError(context, firstError);

        XrayProfile activeProfile = XrayStore.getActiveProfile(context);
        if (activeProfile == null && !profiles.isEmpty()) {
            XrayStore.setActiveProfileId(context, profiles.values().iterator().next().id);
        }

        return new RefreshResult(new ArrayList<>(profiles.values()), updatedSubscriptions, firstError);
    }

    private static boolean shouldRefreshSubscription(
        XraySubscription subscription,
        long now,
        int defaultRefreshIntervalMinutes
    ) {
        if (subscription == null || !subscription.autoUpdate || TextUtils.isEmpty(subscription.url)) {
            return false;
        }
        int refreshMinutes =
            subscription.refreshIntervalMinutes > 0
                ? subscription.refreshIntervalMinutes
                : defaultRefreshIntervalMinutes;
        if (subscription.lastUpdatedAt <= 0L) {
            return true;
        }
        return now - subscription.lastUpdatedAt >= refreshMinutes * MILLIS_PER_MINUTE;
    }

    private static void restoreExistingProfiles(
        Map<String, List<XrayProfile>> existingProfilesBySubscription,
        Map<String, XrayProfile> profiles,
        String subscriptionId
    ) {
        List<XrayProfile> existingSubscriptionProfiles = existingProfilesBySubscription.get(subscriptionId);
        if (existingSubscriptionProfiles == null) {
            return;
        }
        for (XrayProfile existingProfile : existingSubscriptionProfiles) {
            if (existingProfile != null && !TextUtils.isEmpty(existingProfile.rawLink)) {
                profiles.put(XrayStore.getProfileStorageKey(existingProfile), existingProfile);
            }
        }
    }

    private static FetchResult fetch(Context context, String urlString) throws Exception {
        HttpURLConnection connection = DirectNetworkConnection.openHttpConnection(context, new URL(urlString));
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", resolveUserAgent(context));
        SubscriptionHwidStore.Payload hwidPayload = SubscriptionHwidStore.getEffectivePayload(context);
        if (hwidPayload != null) {
            if (!TextUtils.isEmpty(hwidPayload.hwid)) {
                connection.setRequestProperty("x-hwid", hwidPayload.hwid);
            }
            if (!TextUtils.isEmpty(hwidPayload.deviceOs)) {
                connection.setRequestProperty("x-device-os", hwidPayload.deviceOs);
            }
            if (!TextUtils.isEmpty(hwidPayload.verOs)) {
                connection.setRequestProperty("x-ver-os", hwidPayload.verOs);
            }
            if (!TextUtils.isEmpty(hwidPayload.deviceModel)) {
                connection.setRequestProperty("x-device-model", hwidPayload.deviceModel);
            }
        }
        connection.connect();
        int responseCode = connection.getResponseCode();
        SubscriptionMetadata metadata = parseSubscriptionMetadata(readSubscriptionUserinfoHeader(connection));
        try (
            InputStream inputStream =
                responseCode >= 200 && responseCode < 400 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            if (inputStream == null) {
                throw new IllegalStateException("Subscription returned empty response");
            }
            byte[] buffer = new byte[4096];
            int read;
            read = inputStream.read(buffer);
            while (read != -1) {
                output.write(buffer, 0, read);
                read = inputStream.read(buffer);
            }
            if (responseCode < 200 || responseCode >= 400) {
                throw new IllegalStateException("Subscription HTTP " + responseCode);
            }
            return new FetchResult(output.toString(StandardCharsets.UTF_8.name()), metadata);
        } finally {
            connection.disconnect();
        }
    }

    private static SubscriptionMetadata parseSubscriptionMetadata(String rawHeader) {
        if (TextUtils.isEmpty(rawHeader)) {
            return SubscriptionMetadata.EMPTY;
        }
        long uploadBytes = 0L;
        long downloadBytes = 0L;
        long totalBytes = 0L;
        long expireAt = 0L;
        Matcher matcher = USERINFO_PART_PATTERN.matcher(rawHeader);
        while (matcher.find()) {
            String key = matcher.group(1);
            long value = parsePositiveLong(matcher.group(2));
            if ("upload".equalsIgnoreCase(key)) {
                uploadBytes = value;
            } else if ("download".equalsIgnoreCase(key)) {
                downloadBytes = value;
            } else if ("total".equalsIgnoreCase(key)) {
                totalBytes = value;
            } else if ("expire".equalsIgnoreCase(key)) {
                expireAt = value > 0L ? value * 1000L : 0L;
            }
        }
        return new SubscriptionMetadata(uploadBytes, downloadBytes, totalBytes, expireAt, true);
    }

    private static String readSubscriptionUserinfoHeader(HttpURLConnection connection) {
        String header = connection.getHeaderField("Subscription-Userinfo");
        if (!TextUtils.isEmpty(header)) {
            return header;
        }
        Map<String, List<String>> headerFields = connection.getHeaderFields();
        if (headerFields == null) {
            return "";
        }
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
            String key = entry.getKey();
            if (key == null || !"Subscription-Userinfo".equalsIgnoreCase(key)) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values == null) {
                return "";
            }
            for (String value : values) {
                if (!TextUtils.isEmpty(value)) {
                    return value;
                }
            }
            return "";
        }
        return "";
    }

    private static long parsePositiveLong(String rawValue) {
        if (TextUtils.isEmpty(rawValue)) {
            return 0L;
        }
        try {
            return Math.max(Long.parseLong(rawValue.trim()), 0L);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    @NonNull
    private static String resolveUserAgent(Context context) {
        try {
            Context appContext = context.getApplicationContext();
            String versionName = appContext
                .getPackageManager()
                .getPackageInfo(appContext.getPackageName(), 0)
                .versionName;
            if (!TextUtils.isEmpty(versionName)) {
                return "WINGSV/" + versionName;
            }
        } catch (Exception ignored) {}
        return "WINGSV";
    }

    public static final class RefreshResult {

        public final List<XrayProfile> profiles;
        public final List<XraySubscription> subscriptions;
        public final String error;

        RefreshResult(List<XrayProfile> profiles, List<XraySubscription> subscriptions, String error) {
            this.profiles = profiles;
            this.subscriptions = subscriptions;
            this.error = error;
        }
    }

    public interface ProgressListener {
        void onSubscriptionStarted(XraySubscription subscription);
        void onSubscriptionFinished(XraySubscription subscription, String error);
    }

    private static final class FetchResult {

        final String body;
        final SubscriptionMetadata metadata;

        FetchResult(String body, SubscriptionMetadata metadata) {
            this.body = body == null ? "" : body;
            this.metadata = metadata == null ? SubscriptionMetadata.EMPTY : metadata;
        }
    }

    private static final class SubscriptionMetadata {

        static final SubscriptionMetadata EMPTY = new SubscriptionMetadata(0L, 0L, 0L, 0L, false);

        final long uploadBytes;
        final long downloadBytes;
        final long totalBytes;
        final long expireAt;
        final boolean hasUserinfoHeader;

        SubscriptionMetadata(
            long uploadBytes,
            long downloadBytes,
            long totalBytes,
            long expireAt,
            boolean hasUserinfoHeader
        ) {
            this.uploadBytes = Math.max(uploadBytes, 0L);
            this.downloadBytes = Math.max(downloadBytes, 0L);
            this.totalBytes = Math.max(totalBytes, 0L);
            this.expireAt = Math.max(expireAt, 0L);
            this.hasUserinfoHeader = hasUserinfoHeader;
        }

        SubscriptionMetadata withFallback(XraySubscription subscription) {
            if (hasUserinfoHeader || subscription == null) {
                return this;
            }
            return new SubscriptionMetadata(
                subscription.advertisedUploadBytes,
                subscription.advertisedDownloadBytes,
                subscription.advertisedTotalBytes,
                subscription.advertisedExpireAt,
                false
            );
        }
    }
}
