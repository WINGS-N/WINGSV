package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Аккаунт федерации: вход, профиль и выданный доступ.
 *
 * <p>Токен устройства живёт месяцами и отзывается поштучно, поэтому логин с
 * паролем спрашивается один раз, а дальше все запросы идут по нему.
 */
public final class FederationAccount {

    /** Адрес панели по умолчанию */
    public static final String DEFAULT_PANEL = "https://v.wingsnet.org";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private FederationAccount() {}

    /** Состояние доступа, как его отдаёт панель */
    public static final class Access {

        public boolean enabled;
        public int nodes;
        public String subscriptionUrl = "";
        public int trustConfidence;
        public String trustBand = "";
    }

    /** Ответ входа */
    public static final class Session {

        public final String token;
        public final String username;

        Session(String token, String username) {
            this.token = token;
            this.username = username;
        }
    }

    public static String panelUrl(@NonNull Context context) {
        String stored = AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_PANEL_URL, "");
        return TextUtils.isEmpty(stored) ? DEFAULT_PANEL : stored;
    }

    public static String token(@NonNull Context context) {
        return AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_TOKEN, "");
    }

    public static String username(@NonNull Context context) {
        return AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_USERNAME, "");
    }

    public static boolean isSignedIn(@NonNull Context context) {
        return !TextUtils.isEmpty(token(context));
    }

    public static void store(@NonNull Context context, @NonNull Session session) {
        AppPrefs.prefs(context)
            .edit()
            .putString(AppPrefs.KEY_FEDERATION_TOKEN, session.token)
            .putString(AppPrefs.KEY_FEDERATION_USERNAME, session.username)
            .apply();
    }

    public static void clear(@NonNull Context context) {
        AppPrefs.prefs(context)
            .edit()
            .remove(AppPrefs.KEY_FEDERATION_TOKEN)
            .remove(AppPrefs.KEY_FEDERATION_USERNAME)
            .apply();
    }

    /** Вход логином и паролём. Бросает с текстом от панели, когда она отказала */
    public static Session signIn(@NonNull Context context, @NonNull String login, @NonNull String password)
        throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", login);
        body.put("password", password);
        body.put("device_name", android.os.Build.MODEL);
        JSONObject response = post(context, "/api/app/login", body.toString(), null);
        return new Session(
            response.optString("token"),
            response.optJSONObject("account") == null
                ? login
                : response.optJSONObject("account").optString("username", login)
        );
    }

    /** Обмен одноразового кода на токен. Так возвращается вход через Matrix */
    public static Session exchangeCode(@NonNull Context context, @NonNull String code) throws Exception {
        JSONObject body = new JSONObject();
        body.put("code", code);
        body.put("device_name", android.os.Build.MODEL);
        JSONObject response = post(context, "/api/app/session", body.toString(), null);
        JSONObject account = response.optJSONObject("account");
        return new Session(response.optString("token"), account == null ? "" : account.optString("username", ""));
    }

    /** Что выдано этому аккаунту */
    @Nullable
    public static Access access(@NonNull Context context) throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            return null;
        }
        JSONObject response = get(context, "/api/app/access", token);
        Access access = new Access();
        access.enabled = response.optBoolean("enabled");
        access.nodes = response.optInt("nodes");
        access.subscriptionUrl = response.optString("subscription_url", "");
        JSONObject trust = response.optJSONObject("trust");
        if (trust != null) {
            access.trustConfidence = trust.optInt("confidence");
            access.trustBand = trust.optString("band", "");
        }
        return access;
    }

    /** Выход отзывает сессию именно этого устройства */
    public static void signOut(@NonNull Context context) {
        String token = token(context);
        clear(context);
        if (TextUtils.isEmpty(token)) {
            return;
        }
        try {
            post(context, "/api/app/logout", "{}", token);
        } catch (Exception ignored) {
            // Панель недоступна - локально мы уже вышли, сессия истечёт сама
        }
    }

    private static JSONObject post(Context context, String path, String body, @Nullable String token) throws Exception {
        HttpURLConnection connection = open(context, path, token);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(connection);
    }

    private static JSONObject get(Context context, String path, @Nullable String token) throws Exception {
        HttpURLConnection connection = open(context, path, token);
        connection.setRequestMethod("GET");
        return readResponse(connection);
    }

    private static HttpURLConnection open(Context context, String path, @Nullable String token) throws Exception {
        URL url = new URL(panelUrl(context) + path);
        HttpURLConnection connection = DirectNetworkConnection.openHttpConnection(context, url);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        if (!TextUtils.isEmpty(token)) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private static JSONObject readResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        try (
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            if (stream != null) {
                byte[] buffer = new byte[4096];
                int read = stream.read(buffer);
                while (read != -1) {
                    output.write(buffer, 0, read);
                    read = stream.read(buffer);
                }
            }
            String raw = output.toString(StandardCharsets.UTF_8.name());
            JSONObject json = TextUtils.isEmpty(raw) ? new JSONObject() : new JSONObject(raw);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(json.optString("message", "HTTP " + code));
            }
            return json;
        } finally {
            connection.disconnect();
        }
    }
}
