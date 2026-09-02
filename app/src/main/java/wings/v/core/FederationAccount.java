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
        public long avatarVersion;
        public boolean panelAccess;
        public String role = "";
        public long usedBytes;
        public long uplinkBps;
        public long downlinkBps;
        public int nodesEntitled;
    }

    /** Ответ входа */
    public static final class Session {

        public final String token;
        public final String username;
        public final String accountId;
        public final long avatarVersion;

        Session(String token, String username, String accountId, long avatarVersion) {
            this.token = token;
            this.username = username;
            this.accountId = accountId;
            this.avatarVersion = avatarVersion;
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

    /** Стоит ли у аккаунта своя картинка, а не общая заглушка */
    public static boolean hasOwnAvatar(@NonNull Context context) {
        return AppPrefs.prefs(context).getLong(AppPrefs.KEY_FEDERATION_AVATAR_VERSION, 0L) > 0L;
    }

    /** Адрес аватара вошедшего. Пусто, когда никто не вошёл */
    public static String avatarUrl(@NonNull Context context) {
        String id = AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_ACCOUNT_ID, "");
        if (TextUtils.isEmpty(id)) {
            return "";
        }
        long version = AppPrefs.prefs(context).getLong(AppPrefs.KEY_FEDERATION_AVATAR_VERSION, 0L);
        // Нулевая версия - это аккаунт без своей картинки: панель отдаст по тому
        // же адресу заглушку с буквой имени, одинаковую во всех клиентах
        return panelUrl(context) + "/api/admin/avatars/" + id + ".png?v=" + version;
    }

    public static void store(@NonNull Context context, @NonNull Session session) {
        AppPrefs.prefs(context)
            .edit()
            .putString(AppPrefs.KEY_FEDERATION_TOKEN, session.token)
            .putString(AppPrefs.KEY_FEDERATION_USERNAME, session.username)
            .putString(AppPrefs.KEY_FEDERATION_ACCOUNT_ID, session.accountId)
            .putLong(AppPrefs.KEY_FEDERATION_AVATAR_VERSION, session.avatarVersion)
            .apply();
    }

    public static void clear(@NonNull Context context) {
        AppPrefs.prefs(context)
            .edit()
            .remove(AppPrefs.KEY_FEDERATION_TOKEN)
            .remove(AppPrefs.KEY_FEDERATION_USERNAME)
            .remove(AppPrefs.KEY_FEDERATION_ACCOUNT_ID)
            .remove(AppPrefs.KEY_FEDERATION_AVATAR_VERSION)
            .remove(AppPrefs.KEY_FEDERATION_PANEL_ACCESS)
            .remove(AppPrefs.KEY_FEDERATION_ACCESS_CACHE)
            .apply();
    }

    /** Панель попросила код второго фактора */
    public static final class SecondFactorRequired extends Exception {

        public SecondFactorRequired(String message) {
            super(message);
        }
    }

    /** Вход логином и паролём. Бросает с текстом от панели, когда она отказала */
    public static Session signIn(
        @NonNull Context context,
        @NonNull String login,
        @NonNull String password,
        @Nullable String code
    ) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", login);
        body.put("password", password);
        body.put("code", code == null ? "" : code);
        body.put("device_name", android.os.Build.MODEL);
        return sessionOf(post(context, "/api/app/login", body.toString(), null), login);
    }

    /** Обмен одноразового кода на токен. Так возвращается вход через Matrix */
    public static Session exchangeCode(@NonNull Context context, @NonNull String code) throws Exception {
        JSONObject body = new JSONObject();
        body.put("code", code);
        body.put("device_name", android.os.Build.MODEL);
        return sessionOf(post(context, "/api/app/session", body.toString(), null), "");
    }

    /** Что выдано этому аккаунту */
    private static Session sessionOf(JSONObject response, String fallbackName) {
        JSONObject account = response.optJSONObject("account");
        if (account == null) {
            return new Session(response.optString("token"), fallbackName, "", 0L);
        }
        return new Session(
            response.optString("token"),
            account.optString("username", fallbackName),
            String.valueOf(account.optLong("id")),
            account.optLong("avatar_version")
        );
    }

    /** Что показывали в прошлый раз: экран не должен открываться пустым */
    @Nullable
    public static Access cachedAccess(@NonNull Context context) {
        String raw = AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_ACCESS_CACHE, "");
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            return accessOf(new JSONObject(raw));
        } catch (Exception error) {
            return null;
        }
    }

    private static void cacheAccess(@NonNull Context context, @NonNull JSONObject response) {
        AppPrefs.prefs(context).edit().putString(AppPrefs.KEY_FEDERATION_ACCESS_CACHE, response.toString()).apply();
    }

    private static Access accessOf(@NonNull JSONObject response) {
        Access access = new Access();
        access.enabled = response.optBoolean("enabled");
        access.nodes = response.optInt("nodes");
        access.subscriptionUrl = response.optString("subscription_url", "");
        access.avatarVersion = response.optLong("avatar_version", 0L);
        access.panelAccess = response.optBoolean("panel_access");
        access.role = response.optString("role", "");
        access.usedBytes = response.optLong("used_bytes", 0L);
        access.uplinkBps = response.optLong("uplink_bps", 0L);
        access.downlinkBps = response.optLong("downlink_bps", 0L);
        access.nodesEntitled = response.optInt("nodes_entitled");
        JSONObject trust = response.optJSONObject("trust");
        if (trust != null) {
            access.trustConfidence = trust.optInt("confidence");
            access.trustBand = trust.optString("band", "");
        }
        return access;
    }

    @Nullable
    public static Access access(@NonNull Context context) throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            return null;
        }
        JSONObject response = get(context, "/api/app/access", token);
        cacheAccess(context, response);
        Access access = accessOf(response);
        rememberPanel(context, access.panelAccess);
        return access;
    }

    /**
     * Запоминает версию аватара, какой её назвала панель.
     *
     * <p>Ноль - это тоже ответ: аватар убрали, возможно с другого устройства, и
     * локальная версия обязана обнулиться, иначе приложение продолжит просить
     * картинку, которой больше нет.
     */
    public static void rememberAvatarVersion(@NonNull Context context, long version) {
        AppPrefs.prefs(context).edit().putLong(AppPrefs.KEY_FEDERATION_AVATAR_VERSION, Math.max(0L, version)).apply();
    }

    /** Меняет аватар аккаунта. Панель сама поднимает его версию */
    public static void uploadAvatar(@NonNull Context context, @NonNull byte[] png, @NonNull String mime)
        throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            throw new IllegalStateException("нет сессии");
        }
        String boundary = "wings" + System.nanoTime();
        HttpURLConnection connection = open(context, "/api/app/avatar", token);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        String head =
            "--" +
            boundary +
            "\r\n" +
            "Content-Disposition: form-data; name=\"avatar\"; filename=\"avatar.png\"\r\n" +
            "Content-Type: " +
            mime +
            "\r\n\r\n";
        try (OutputStream output = connection.getOutputStream()) {
            output.write(head.getBytes(StandardCharsets.UTF_8));
            output.write(png);
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        JSONObject response = readResponse(connection);
        rememberAvatarVersion(context, response.optLong("avatar_version", 0L));
    }

    /** Открыта ли этому аккаунту админ-панель */
    public static boolean hasPanel(@NonNull Context context) {
        return AppPrefs.prefs(context).getBoolean(AppPrefs.KEY_FEDERATION_PANEL_ACCESS, false);
    }

    static void rememberPanel(@NonNull Context context, boolean allowed) {
        AppPrefs.prefs(context).edit().putBoolean(AppPrefs.KEY_FEDERATION_PANEL_ACCESS, allowed).apply();
    }

    /** Убирает аватар: аккаунт возвращается к панельной заглушке */
    public static void removeAvatar(@NonNull Context context) throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            throw new IllegalStateException("нет сессии");
        }
        HttpURLConnection connection = open(context, "/api/app/avatar", token);
        connection.setRequestMethod("DELETE");
        readResponse(connection);
        rememberAvatarVersion(context, 0L);
    }

    /** Встаёт в дерево по коду приглашения */
    public static void redeemInvite(@NonNull Context context, @NonNull String code) throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            throw new IllegalStateException("нет сессии");
        }
        JSONObject body = new JSONObject();
        body.put("token", code);
        post(context, "/api/app/invites/redeem", body.toString(), token);
    }

    /** Смена пароля своего аккаунта */
    public static void changePassword(@NonNull Context context, @NonNull String current, @NonNull String next)
        throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            throw new IllegalStateException("нет сессии");
        }
        JSONObject body = new JSONObject();
        body.put("old_password", current);
        body.put("new_password", next);
        post(context, "/api/app/password", body.toString(), token);
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
                if (json.optBoolean("totp_required")) {
                    throw new SecondFactorRequired(json.optString("message", "нужен код"));
                }
                throw new IllegalStateException(json.optString("message", "HTTP " + code));
            }
            return json;
        } finally {
            connection.disconnect();
        }
    }
}
