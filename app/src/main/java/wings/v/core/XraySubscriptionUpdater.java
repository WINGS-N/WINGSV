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
    private static final long MILLIS_PER_HOUR = 60L * 60L * 1000L;

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
                profiles.put(existingProfile.stableDedupKey(), existingProfile);
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
        int defaultRefreshIntervalHours = Math.max(1, XrayStore.getRefreshIntervalHours(context));
        String firstError = null;
        boolean anySubscriptionUpdated = false;

        for (XraySubscription subscription : subscriptions) {
            if (subscription == null || TextUtils.isEmpty(subscription.url)) {
                continue;
            }
            if (dueOnly && !shouldRefreshSubscription(subscription, now, defaultRefreshIntervalHours)) {
                updatedSubscriptions.add(subscription);
                restoreExistingProfiles(existingProfilesBySubscription, profiles, subscription.id);
                continue;
            }
            if (listener != null) {
                listener.onSubscriptionStarted(subscription);
            }
            try {
                String body = fetch(context, subscription.url);
                for (String link : XraySubscriptionParser.parseLinks(body)) {
                    XrayProfile profile = VlessLinkParser.parseProfile(link, subscription.id, subscription.title);
                    if (profile != null) {
                        profiles.put(profile.stableDedupKey(), profile);
                    }
                }
                updatedSubscriptions.add(
                    new XraySubscription(
                        subscription.id,
                        subscription.title,
                        subscription.url,
                        subscription.formatHint,
                        subscription.refreshIntervalHours,
                        subscription.autoUpdate,
                        now
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
                            profiles.put(existingProfile.stableDedupKey(), existingProfile);
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
        int defaultRefreshIntervalHours
    ) {
        if (subscription == null || !subscription.autoUpdate || TextUtils.isEmpty(subscription.url)) {
            return false;
        }
        int refreshHours =
            subscription.refreshIntervalHours > 0 ? subscription.refreshIntervalHours : defaultRefreshIntervalHours;
        if (subscription.lastUpdatedAt <= 0L) {
            return true;
        }
        return now - subscription.lastUpdatedAt >= refreshHours * MILLIS_PER_HOUR;
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
                profiles.put(existingProfile.stableDedupKey(), existingProfile);
            }
        }
    }

    public static Map<String, List<XrayProfile>> groupProfilesBySubscription(List<XrayProfile> profiles) {
        LinkedHashMap<String, List<XrayProfile>> grouped = new LinkedHashMap<>();
        if (profiles == null) {
            return grouped;
        }
        for (XrayProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            String key = TextUtils.isEmpty(profile.subscriptionTitle) ? "Без подписки" : profile.subscriptionTitle;
            List<XrayProfile> group = grouped.computeIfAbsent(key, k -> new ArrayList<>());
            group.add(profile);
        }
        return grouped;
    }

    private static String fetch(Context context, String urlString) throws Exception {
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
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
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
}
