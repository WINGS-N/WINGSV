package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
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
        /** Кто мы для федерации. Им подписываются расписки */
        public String subjectId = "";
        /** Трафик по строкам списка, как его посчитала башка */
        public final java.util.List<ServerUsage> servers = new java.util.ArrayList<>();
    }

    /** Сколько прошло через одну строку доступа: сервер плюс транспорт */
    public static final class ServerUsage {

        public String name = "";
        public String transport = "";
        public long upBytes;
        public long downBytes;
    }

    /** Ответ входа */
    public static final class Session {

        public final String token;
        public final String username;
        public final String accountId;
        public final long avatarVersion;
        /** Как участника зовут в федерации, этим именем он подписывает расписки */
        public final String subjectId;

        Session(String token, String username, String accountId, long avatarVersion, String subjectId) {
            this.token = token;
            this.username = username;
            this.accountId = accountId;
            this.avatarVersion = avatarVersion;
            this.subjectId = subjectId;
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
            .putString(AppPrefs.KEY_FEDERATION_SUBJECT_ID, session.subjectId)
            .putLong(AppPrefs.KEY_FEDERATION_AVATAR_VERSION, session.avatarVersion)
            .apply();
    }

    public static void clear(@NonNull Context context) {
        AppPrefs.prefs(context)
            .edit()
            .remove(AppPrefs.KEY_FEDERATION_TOKEN)
            .remove(AppPrefs.KEY_FEDERATION_USERNAME)
            .remove(AppPrefs.KEY_FEDERATION_ACCOUNT_ID)
            .remove(AppPrefs.KEY_FEDERATION_SUBJECT_ID)
            .remove(AppPrefs.KEY_FEDERATION_KEY_SENT)
            .remove(AppPrefs.KEY_FEDERATION_AVATAR_VERSION)
            .remove(AppPrefs.KEY_FEDERATION_PANEL_ACCESS)
            .remove(AppPrefs.KEY_FEDERATION_ACCESS_CACHE)
            .remove(AppPrefs.KEY_FEDERATION_DONOR_CACHE)
            .remove(AppPrefs.KEY_FEDERATION_INVITES_CACHE)
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
            return new Session(response.optString("token"), fallbackName, "", 0L, "");
        }
        return new Session(
            response.optString("token"),
            account.optString("username", fallbackName),
            String.valueOf(account.optLong("id")),
            account.optLong("avatar_version"),
            account.optString("federation_id", "")
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
        cacheSection(context, AppPrefs.KEY_FEDERATION_ACCESS_CACHE, response);
    }

    /** Последний удачный ответ секции: экран не должен открываться пустым */
    private static void cacheSection(@NonNull Context context, @NonNull String key, @NonNull JSONObject response) {
        AppPrefs.prefs(context).edit().putString(key, response.toString()).apply();
    }

    @Nullable
    private static JSONObject cachedSection(@NonNull Context context, @NonNull String key) {
        String raw = AppPrefs.prefs(context).getString(key, "");
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Exception broken) {
            return null;
        }
    }

    /** Держит серверный трафик там, где его найдёт список профилей */
    private static void rememberServerUsage(@NonNull Context context, @Nullable JSONArray servers) {
        if (servers == null) {
            return;
        }
        AppPrefs.prefs(context).edit().putString(AppPrefs.KEY_FEDERATION_SERVER_USAGE, servers.toString()).apply();
    }

    /**
     * Трафик по строкам доступа: ключ - имя сервера и транспорт через дробь,
     * ровно как строка выглядит у человека
     */
    @NonNull
    public static java.util.Map<String, long[]> serverUsage(@NonNull Context context) {
        java.util.Map<String, long[]> out = new java.util.HashMap<>();
        String raw = AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_SERVER_USAGE, "");
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        try {
            JSONArray items = new JSONArray(raw);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String key = usageKey(item.optString("name", ""), item.optString("transport", ""));
                out.put(key, new long[] { item.optLong("down_bytes"), item.optLong("up_bytes") });
            }
        } catch (Exception broken) {
            return out;
        }
        return out;
    }

    /** Ключ строки: имя сервера плюс транспорт, оба в нижнем регистре */
    @NonNull
    public static String usageKey(@NonNull String name, @NonNull String transport) {
        return (
            name.trim().toLowerCase(java.util.Locale.ROOT) + "|" + transport.trim().toLowerCase(java.util.Locale.ROOT)
        );
    }

    /** Что показывали в прошлый раз в разделе серверов */
    @Nullable
    public static Donor cachedDonor(@NonNull Context context) {
        JSONObject raw = cachedSection(context, AppPrefs.KEY_FEDERATION_DONOR_CACHE);
        return raw == null ? null : donorOf(raw);
    }

    /** Что показывали в прошлый раз в приглашениях */
    @Nullable
    public static Invites cachedInvites(@NonNull Context context) {
        JSONObject raw = cachedSection(context, AppPrefs.KEY_FEDERATION_INVITES_CACHE);
        return raw == null ? null : invitesOf(raw);
    }

    private static Access accessOf(@NonNull JSONObject response) {
        Access access = new Access();
        access.subjectId = response.optString("federation_id", "");
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
        JSONArray servers = response.optJSONArray("servers");
        for (int i = 0; servers != null && i < servers.length(); i++) {
            JSONObject item = servers.optJSONObject(i);
            if (item == null) {
                continue;
            }
            ServerUsage usage = new ServerUsage();
            usage.name = item.optString("name", "");
            usage.transport = item.optString("transport", "");
            usage.upBytes = item.optLong("up_bytes");
            usage.downBytes = item.optLong("down_bytes");
            access.servers.add(usage);
        }
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
        rememberSubject(context, access.subjectId);
        rememberServerUsage(context, response.optJSONArray("servers"));
        return access;
    }

    /**
     * Запоминает наш идентификатор в федерации.
     *
     * <p>Пишется на каждом заходе, а не только при входе: без него расписки не
     * собираются вообще, а вошедший до появления поля иначе сидел бы пустым до
     * перелогина и молча копил обвинения за неподписанный трафик.
     */
    private static void rememberSubject(@NonNull Context context, @Nullable String subjectId) {
        if (TextUtils.isEmpty(subjectId)) {
            return;
        }
        android.content.SharedPreferences prefs = AppPrefs.prefs(context);
        if (subjectId.equals(prefs.getString(AppPrefs.KEY_FEDERATION_SUBJECT_ID, ""))) {
            return;
        }
        prefs.edit().putString(AppPrefs.KEY_FEDERATION_SUBJECT_ID, subjectId).apply();
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
        HttpURLConnection connection = open(context, "/api/app/avatar", token, Route.DIRECT);
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
        HttpURLConnection connection = open(context, "/api/app/avatar", token, Route.DIRECT);
        connection.setRequestMethod("DELETE");
        readResponse(connection);
        rememberAvatarVersion(context, 0L);
    }

    /** Свой сервер, отданный в федерацию */
    public static final class DonorNode {

        public String nodeId = "";
        public String hostname = "";
        public String state = "";
        public boolean online;
        public String xrayVersion = "";
        public String vktpVersion = "";
        public long declaredBudgetBytes;
        public long usedBytes;
        public long probeBytes;
        public int sessions;
        public double upRateBps;
        public double downRateBps;
    }

    /** Донорская сводка: что отдано в федерацию и сколько с этого прошло */
    public static final class Donor {

        public boolean enabled;
        public int nodes;
        public int nodesOnline;
        public int sessions;
        public long declaredBudgetBytes;
        public long usedBytes;
        public long probeBytes;
        public double upRateBps;
        public double downRateBps;
        public String error = "";
        public final List<DonorNode> list = new ArrayList<>();
    }

    /** Донорская сводка со своими серверами */
    public static Donor donor(@NonNull Context context) throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            throw new IllegalStateException("нет сессии");
        }
        JSONObject response = get(context, "/api/app/federation/summary", token);
        cacheSection(context, AppPrefs.KEY_FEDERATION_DONOR_CACHE, response);
        return donorOf(response);
    }

    private static Donor donorOf(@NonNull JSONObject response) {
        Donor out = new Donor();
        out.enabled = response.optBoolean("enabled");
        out.nodes = response.optInt("nodes");
        out.nodesOnline = response.optInt("nodes_online");
        out.sessions = response.optInt("sessions");
        out.declaredBudgetBytes = response.optLong("declared_budget_bytes");
        out.usedBytes = response.optLong("used_bytes");
        out.probeBytes = response.optLong("probe_bytes");
        out.upRateBps = response.optDouble("up_rate_bps", 0);
        out.downRateBps = response.optDouble("down_rate_bps", 0);
        out.error = response.optString("error", "");
        JSONArray items = response.optJSONArray("node_list");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            DonorNode node = new DonorNode();
            node.nodeId = item.optString("node_id", "");
            node.hostname = item.optString("hostname", "");
            node.state = item.optString("state", "");
            node.online = item.optBoolean("online");
            node.xrayVersion = item.optString("xray_version", "");
            node.vktpVersion = item.optString("vktp_version", "");
            node.declaredBudgetBytes = item.optLong("declared_budget_bytes");
            node.usedBytes = item.optLong("used_bytes");
            node.probeBytes = item.optLong("probe_bytes");
            node.sessions = item.optInt("sessions");
            node.upRateBps = item.optDouble("up_rate_bps", 0);
            node.downRateBps = item.optDouble("down_rate_bps", 0);
            out.list.add(node);
        }
        return out;
    }

    /** Меняет месячный потолок отданного сервера */
    public static long setNodeBudget(@NonNull Context context, @NonNull String nodeId, long bytes) throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            throw new IllegalStateException("нет сессии");
        }
        JSONObject body = new JSONObject();
        body.put("declared_budget_bytes", bytes);
        JSONObject response = put(context, "/api/app/federation/nodes/" + nodeId + "/budget", body.toString(), token);
        return response.optLong("declared_budget_bytes", bytes);
    }

    /**
     * Команда подключения нового сервера: её выполняют на самой машине.
     *
     * <p>Потолок называется здесь же: править его после того, как сервер уже
     * зашёл, значит сперва отдать больше, чем собирался
     */
    public static String enrollCommand(@NonNull Context context, long budgetGb) throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            throw new IllegalStateException("нет сессии");
        }
        JSONObject body = new JSONObject();
        body.put("uses", 1);
        body.put("budget_gb", Math.max(0L, budgetGb));
        JSONObject response = post(context, "/api/app/federation/enroll", body.toString(), token);
        return response.optString("command", "");
    }

    /** Одно приглашение так, как его отдаёт панель */
    public static final class Invite {

        public String token = "";
        public String link = "";
        public int useCount;
        public int maxUses;
        public boolean spent;
    }

    /** Свои коды и право их выписывать */
    public static final class Invites {

        public final List<Invite> list = new ArrayList<>();
        public boolean mayInvite;
        public String reason = "";
    }

    /** Забирает свои приглашения */
    public static Invites invites(@NonNull Context context) throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            throw new IllegalStateException("нет сессии");
        }
        JSONObject response = get(context, "/api/app/invites", token);
        cacheSection(context, AppPrefs.KEY_FEDERATION_INVITES_CACHE, response);
        return invitesOf(response);
    }

    private static Invites invitesOf(@NonNull JSONObject response) {
        Invites out = new Invites();
        out.mayInvite = response.optBoolean("may_invite");
        out.reason = response.optString("reason", "");
        JSONArray items = response.optJSONArray("invites");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Invite invite = new Invite();
            invite.token = item.optString("token", "");
            invite.link = item.optString("link", "");
            invite.useCount = item.optInt("use_count");
            invite.maxUses = item.optInt("max_uses");
            invite.spent = item.optBoolean("spent");
            out.list.add(invite);
        }
        return out;
    }

    /** Выписывает новый код: в приложении он всегда бессрочный и без потолка */
    public static Invite createInvite(@NonNull Context context) throws Exception {
        String token = token(context);
        if (TextUtils.isEmpty(token)) {
            throw new IllegalStateException("нет сессии");
        }
        JSONObject response = post(context, "/api/app/invites", "{}", token);
        Invite invite = new Invite();
        invite.token = response.optString("token", "");
        invite.link = response.optString("link", "");
        invite.useCount = response.optInt("use_count");
        invite.maxUses = response.optInt("max_uses");
        return invite;
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

    /**
     * Отдаёт голове публичную половину ключа. Раз в жизнь устройства, пока не
     * сменится: без неё расписки проверить нечем и они все идут нахуй как
     * неподписанные
     */
    public static void ensureReceiptKey(@NonNull Context context) throws Exception {
        if (AppPrefs.prefs(context).getBoolean(AppPrefs.KEY_FEDERATION_KEY_SENT, false)) {
            return;
        }
        JSONObject body = new JSONObject();
        body.put("public_key", FederationKey.publicKey(context));
        post(context, "/api/app/federation/key", body.toString(), token(context));
        AppPrefs.prefs(context).edit().putBoolean(AppPrefs.KEY_FEDERATION_KEY_SENT, true).apply();
    }

    /** Несёт подписанные расписки. Возвращает, сколько голова приняла */
    public static int sendReceipts(@NonNull Context context, @NonNull JSONArray receipts) throws Exception {
        if (receipts.length() == 0) {
            return 0;
        }
        ensureReceiptKey(context);
        JSONObject body = new JSONObject();
        body.put("receipts", receipts);
        // Свой адрес, замеренный мимо туннеля. Сервер сверит его с тем, что
        // видит нода: расходятся - значит поверх нашего туннеля крутится ещё
        // один или профилем пользуется не хозяин
        String selfAddress = SelfAddress.resolve(context);
        if (!TextUtils.isEmpty(selfAddress)) {
            body.put("client_ip", selfAddress);
        }
        JSONObject response = post(context, "/api/app/federation/receipts", body.toString(), token(context));
        return response.optInt("accepted", 0);
    }

    private static JSONObject post(Context context, String path, String body, @Nullable String token) throws Exception {
        return send(context, "POST", path, body, token);
    }

    private static JSONObject put(Context context, String path, String body, @Nullable String token) throws Exception {
        return send(context, "PUT", path, body, token);
    }

    private static JSONObject get(Context context, String path, @Nullable String token) throws Exception {
        return send(context, "GET", path, null, token);
    }

    /**
     * Ходит в панель сначала мимо туннеля, а если сеть не пустила - через него.
     *
     * <p>Мимо туннеля путь основной: иначе нода, через которую человек сидит,
     * может душить расписки о собственном трафике. Но у провайдера панель бывает
     * закрыта, и тогда единственный живой путь как раз туннельный. Пробуем оба,
     * потому что молча потерянная расписка это трафик, который не подпишет уже
     * никто.
     *
     * <p>Второй заход только на сетевую ошибку: ответ сервера, каким бы он ни
     * был, означает что путь рабочий и повторять нехуй.
     */
    private static JSONObject send(
        Context context,
        String method,
        String path,
        @Nullable String body,
        @Nullable String token
    ) throws Exception {
        try {
            return exchange(open(context, path, token, Route.DIRECT), method, body);
        } catch (IOException direct) {
            try {
                return exchange(open(context, path, token, Route.TUNNEL), method, body);
            } catch (IOException tunnel) {
                // Последний путь: в белом списке приложение само вне туннеля, и
                // войти внутрь можно только через служебный socks нашего ядра
                return exchange(open(context, path, token, Route.CONTROL_PROXY), method, body);
            }
        }
    }

    /** Каким путём стучимся в панель */
    private enum Route {
        /** Мимо туннеля, по физической сети */
        DIRECT,
        /** Как получится: активный туннель заворачивает нас сам */
        TUNNEL,
        /** Через служебный socks ядра, то есть сквозь ноду */
        CONTROL_PROXY,
    }

    private static JSONObject exchange(HttpURLConnection connection, String method, @Nullable String body)
        throws Exception {
        connection.setRequestMethod(method);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return readResponse(connection);
    }

    private static HttpURLConnection open(Context context, String path, @Nullable String token, Route route)
        throws Exception {
        URL url = new URL(panelUrl(context) + path);
        HttpURLConnection connection = openFor(context, url, route);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        if (!TextUtils.isEmpty(token)) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private static HttpURLConnection openFor(Context context, URL url, Route route) throws Exception {
        if (route == Route.DIRECT) {
            return DirectNetworkConnection.openHttpConnection(context, url);
        }
        if (route == Route.TUNNEL) {
            return (HttpURLConnection) url.openConnection();
        }
        int port = controlProxyPort(context);
        String secret = AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_CONTROL_SECRET, "");
        if (port <= 0 || TextUtils.isEmpty(secret)) {
            throw new IOException("служебный socks не поднят");
        }
        armControlProxyAuth(port, secret);
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port));
        return (HttpURLConnection) url.openConnection(proxy);
    }

    private static int controlProxyPort(Context context) {
        try {
            return Integer.parseInt(AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_CONTROL_PORT, "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Отдаёт пароль от служебного socks и только ему.
     *
     * <p>Authenticator в Java один на процесс, поэтому проверяем, кто спрашивает:
     * пароль уходит лишь на запрос ОТ ПРОКСИ, с петли и с нашего порта. Любому
     * другому запросу отвечаем пустотой, чтобы не отдать секрет чужому серверу,
     * который просто попросил авторизацию.
     */
    private static void armControlProxyAuth(int port, String secret) {
        synchronized (CONTROL_AUTH_LOCK) {
            if (controlAuthArmed) {
                return;
            }
            controlAuthArmed = true;
            Authenticator.setDefault(
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        boolean ours =
                            getRequestorType() == RequestorType.PROXY &&
                            port == getRequestingPort() &&
                            "127.0.0.1".equals(String.valueOf(getRequestingHost()));
                        if (!ours) {
                            return null;
                        }
                        return new PasswordAuthentication("wings", secret.toCharArray());
                    }
                }
            );
        }
    }

    private static final Object CONTROL_AUTH_LOCK = new Object();

    private static boolean controlAuthArmed;

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
            // Не всякий ответ - JSON: на неизвестный адрес прилетает голый текст
            // вроде "404 page not found", и разбор его роняет невнятной ошибкой
            JSONObject json;
            try {
                json = TextUtils.isEmpty(raw) ? new JSONObject() : new JSONObject(raw);
            } catch (Exception notJson) {
                if (code >= 200 && code < 300) {
                    throw notJson;
                }
                throw new IllegalStateException("HTTP " + code + ": " + raw.trim(), notJson);
            }
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
