package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Свой адрес, каким его видит внешний мир.
 *
 * <p>Замеряется МИМО туннеля нарочно. Через туннель ответом будет адрес ноды, а
 * весь смысл в том, чтобы сказать серверу, откуда мы на самом деле: он сверит
 * это с тем, что видит нода, и расхождение выдаст второй VPN поверх нашего или
 * профиль, которым пользуется не хозяин.
 *
 * <p>Устройство сдаёт себя добровольно, и это честная плата за бесплатный
 * доступ: без такой сверки перепродажу от обычной жизни не отличить.
 */
public final class SelfAddress {

    private static final int TIMEOUT_MS = 6_000;

    /** Отдаёт голый адрес и ничего больше, разбирать нечего */
    private static final String PROBE_URL = "https://api.ipify.org";

    /** Сколько держим замер: мобильный адрес живёт недолго */
    private static final long CACHE_MS = 5 * 60 * 1000L;

    private static volatile String cached = "";
    private static volatile long cachedAt;

    private SelfAddress() {}

    /** Последний замер или пустая строка, если его ещё не делали */
    @NonNull
    public static String cached() {
        return cached;
    }

    /**
     * Замеряет адрес, не чаще раза в несколько минут.
     *
     * <p>Пустая строка означает, что замерить не вышло: сеть отвалилась или
     * сервис не ответил. Это не повод ничего не отправлять, просто поле уедет
     * пустым и сверять сервер не станет.
     */
    @NonNull
    public static String resolve(@Nullable Context context) {
        if (context == null) {
            return "";
        }
        long now = System.currentTimeMillis();
        if (!TextUtils.isEmpty(cached) && now - cachedAt < CACHE_MS) {
            return cached;
        }
        HttpURLConnection connection = null;
        try {
            // Именно мимо туннеля: через него ответом будет адрес ноды, а нам
            // нужен свой
            connection = DirectNetworkConnection.openHttpConnection(context, new URL(PROBE_URL), false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return cached;
            }
            try (
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                )
            ) {
                String line = reader.readLine();
                String address = line == null ? "" : line.trim();
                if (!address.isEmpty() && address.length() <= 45) {
                    cached = address;
                    cachedAt = now;
                }
            }
        } catch (Exception ignored) {
            // Не замерили - уедем без адреса, сверка просто не состоится
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return cached;
    }

    /** Забывает замер: после выхода из аккаунта он ничей */
    public static void forget() {
        cached = "";
        cachedAt = 0L;
    }
}
