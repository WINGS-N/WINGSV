package wings.v.vk;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import wings.v.BuildConfig;
import wings.v.core.AppPrefs;

// Persistence + redirect parsing for the VK OAuth implicit flow used by the
// autolink generator. The WebView (VkOAuthActivity) hosts the page; this class
// owns prefs, state token, expiry math and URL building so the activity stays
// thin and we never duplicate validation logic between launch and consume.
@SuppressWarnings({ "PMD.CommentRequired", "PMD.AvoidUsingHardCodedIP", "PMD.LawOfDemeter" })
public final class VkOAuthAuth {

    public static final String OAUTH_REDIRECT_URI = "https://oauth.vk.com/blank.html";
    public static final String OAUTH_AUTHORIZE_URL = "https://oauth.vk.com/authorize";
    // calls (1<<28) | offline (1<<16) — minimum required to call calls.start and
    // keep the token valid past 24 hours. We do NOT request the legacy "all bits
    // set" mask the upstream PR used, that mask included messaging permissions
    // the autolink path does not need.
    private static final String OAUTH_SCOPE = String.valueOf((1 << 28) | (1 << 16));
    private static final String OAUTH_DISPLAY = "page";
    private static final String OAUTH_RESPONSE_TYPE = "token";
    private static final String OAUTH_REVOKE = "1";
    // 60-second safety margin so we never present a token to the API that
    // the backend will reject as expired during the in-flight request.
    private static final long EXPIRY_SAFETY_WINDOW_SECONDS = 60L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VkOAuthAuth() {}

    public static boolean isClientConfigured() {
        return BuildConfig.VK_OAUTH_CONFIGURED && !TextUtils.isEmpty(BuildConfig.VK_OAUTH_CLIENT_ID);
    }

    public static boolean isAuthorized(@NonNull Context context) {
        SharedPreferences prefs = prefs(context);
        return (
            !TextUtils.isEmpty(prefs.getString(AppPrefs.KEY_VK_OAUTH_ACCESS_TOKEN, "")) &&
            !isExpired(prefs.getLong(AppPrefs.KEY_VK_OAUTH_EXPIRES_AT_SECONDS, 0L))
        );
    }

    @Nullable
    public static String userId(@NonNull Context context) {
        String raw = prefs(context).getString(AppPrefs.KEY_VK_OAUTH_USER_ID, "");
        return TextUtils.isEmpty(raw) ? null : raw;
    }

    @NonNull
    public static String accessTokenOrThrow(@NonNull Context context) {
        SharedPreferences prefs = prefs(context);
        String token = prefs.getString(AppPrefs.KEY_VK_OAUTH_ACCESS_TOKEN, "");
        if (TextUtils.isEmpty(token)) {
            throw new IllegalStateException("VK not authorized");
        }
        if (isExpired(prefs.getLong(AppPrefs.KEY_VK_OAUTH_EXPIRES_AT_SECONDS, 0L))) {
            throw new IllegalStateException("VK token expired");
        }
        return token;
    }

    public static void clearAuthorization(@NonNull Context context) {
        prefs(context)
            .edit()
            .remove(AppPrefs.KEY_VK_OAUTH_ACCESS_TOKEN)
            .remove(AppPrefs.KEY_VK_OAUTH_EXPIRES_AT_SECONDS)
            .remove(AppPrefs.KEY_VK_OAUTH_USER_ID)
            .remove(AppPrefs.KEY_VK_OAUTH_PENDING_STATE)
            .apply();
    }

    @NonNull
    public static String beginAuthorization(@NonNull Context context) {
        if (!isClientConfigured()) {
            throw new IllegalStateException("VK_OAUTH_CLIENT_ID not configured at build time");
        }
        String state = randomHex();
        prefs(context).edit().putString(AppPrefs.KEY_VK_OAUTH_PENDING_STATE, state).apply();
        return Uri.parse(OAUTH_AUTHORIZE_URL)
            .buildUpon()
            .appendQueryParameter("client_id", BuildConfig.VK_OAUTH_CLIENT_ID)
            .appendQueryParameter("scope", OAUTH_SCOPE)
            .appendQueryParameter("redirect_uri", OAUTH_REDIRECT_URI)
            .appendQueryParameter("display", OAUTH_DISPLAY)
            .appendQueryParameter("response_type", OAUTH_RESPONSE_TYPE)
            .appendQueryParameter("revoke", OAUTH_REVOKE)
            .appendQueryParameter("state", state)
            .build()
            .toString();
    }

    public static boolean isRedirectUri(@NonNull String url) {
        return url.startsWith(OAUTH_REDIRECT_URI);
    }

    // Returns null on success, otherwise a human-readable error message.
    @Nullable
    public static String consumeRedirect(@NonNull Context context, @NonNull Uri uri) {
        Map<String, String> params = parseQueryAndFragment(uri);
        String expectedState = prefs(context).getString(AppPrefs.KEY_VK_OAUTH_PENDING_STATE, "");
        prefs(context).edit().remove(AppPrefs.KEY_VK_OAUTH_PENDING_STATE).apply();
        String error = params.get("error");
        if (!TextUtils.isEmpty(error)) {
            String description = params.get("error_description");
            return TextUtils.isEmpty(description) ? error : description;
        }
        String actualState = params.get("state");
        if (!TextUtils.isEmpty(expectedState) && !TextUtils.equals(expectedState, actualState)) {
            return "VK OAuth state mismatch";
        }
        String token = params.get("access_token");
        if (TextUtils.isEmpty(token)) {
            return "VK OAuth response missing access_token";
        }
        long expiresIn = parseLongSafe(params.get("expires_in"));
        long expiresAt = expiresIn <= 0L ? Long.MAX_VALUE : nowSeconds() + expiresIn;
        prefs(context)
            .edit()
            .putString(AppPrefs.KEY_VK_OAUTH_ACCESS_TOKEN, token)
            .putLong(AppPrefs.KEY_VK_OAUTH_EXPIRES_AT_SECONDS, expiresAt)
            .putString(AppPrefs.KEY_VK_OAUTH_USER_ID, params.containsKey("user_id") ? params.get("user_id") : "")
            .apply();
        return null;
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return AppPrefs.defaultSharedPreferences(context.getApplicationContext());
    }

    private static boolean isExpired(long expiresAtSeconds) {
        return expiresAtSeconds != Long.MAX_VALUE && expiresAtSeconds <= nowSeconds() + EXPIRY_SAFETY_WINDOW_SECONDS;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static long parseLongSafe(@Nullable String raw) {
        if (TextUtils.isEmpty(raw)) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String randomHex() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static Map<String, String> parseQueryAndFragment(@NonNull Uri uri) {
        Map<String, String> result = new LinkedHashMap<>();
        // VK returns the implicit-flow token in the fragment, not the query
        // string; the upstream OAuth spec recommends fragment only, but VK can
        // legitimately echo state into the query on error pages, so we merge.
        appendParams(uri.getEncodedQuery(), result);
        appendParams(uri.getEncodedFragment(), result);
        return result;
    }

    private static void appendParams(@Nullable String encoded, @NonNull Map<String, String> result) {
        if (TextUtils.isEmpty(encoded)) {
            return;
        }
        for (String pair : encoded.split("&")) {
            if (TextUtils.isEmpty(pair)) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            result.put(key, value);
        }
    }

    private static String decode(@NonNull String value) {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }
}
