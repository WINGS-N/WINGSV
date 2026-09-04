package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Последний путь для расписок: отдать их самой ноде.
 *
 * <p>Работает там, где не работает ничто другое: в белом списке приложение вне
 * туннеля, панель у провайдера закрыта, и единственный, до кого клиент точно
 * дотягивается, это нода, через которую он и сидит. Дальше нода довозит расписки
 * башке своей же сессией.
 *
 * <p>Отдавать их ноде не страшно: подписаны они нашим ключом, которого у неё нет,
 * а придержать их не в её интересах - молчание превращается в претензию к ней
 * самой.
 */
public final class FederationNodeDoor {

    /** Имя двери. Реального DNS нет, его разбирает ядро на самой ноде */
    private static final String XRAY_HOST = "receipts.wingsv.internal";

    /** Порт двери. Тот же и на петле ноды, и на её адресе внутри туннеля */
    private static final int DOOR_PORT = 8909;

    private static final int CONNECT_TIMEOUT_MS = 8000;

    private static final int READ_TIMEOUT_MS = 12000;

    private FederationNodeDoor() {}

    /**
     * Везёт расписки ноде.
     *
     * @return сколько она забрала, ноль если не вышло
     */
    public static int deliver(@NonNull Context context, @NonNull JSONArray receipts) {
        if (receipts.length() == 0) {
            return 0;
        }
        String body = new JSONObject(java.util.Collections.singletonMap("receipts", receipts)).toString();
        int viaXray = post(xrayUrl(), controlProxy(context), body);
        if (viaXray > 0) {
            return viaXray;
        }
        String gateway = tunnelGateway(context);
        if (TextUtils.isEmpty(gateway)) {
            return 0;
        }
        return post(gatewayUrl(gateway), null, body);
    }

    /**
     * Адрес ноды внутри туннеля VK TURN.
     *
     * <p>Клиенту выдают /32, поэтому подсеть из его адреса не вывести, и шлюз
     * берётся по уговору релея: первый адрес пула. Промах тут ничего не ломает -
     * дверь просто не ответит, и расписки останутся в очереди до следующего раза.
     */
    private static String tunnelGateway(Context context) {
        VkTurnProfile profile = VkTurnProfileStore.getActiveProfile(context);
        if (profile == null || TextUtils.isEmpty(profile.transportProfileId)) {
            return null;
        }
        AmneziaProfile transport = AmneziaProfileStore.getProfileById(context, profile.transportProfileId);
        if (transport == null || TextUtils.isEmpty(transport.quickConfig)) {
            return null;
        }
        for (String line : transport.quickConfig.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase(java.util.Locale.US).startsWith("address")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String first = trimmed.substring(equals + 1).split(",")[0].trim();
            String host = first.split("/")[0].trim();
            int lastDot = host.lastIndexOf('.');
            if (lastDot > 0 && host.indexOf(':') < 0) {
                return host.substring(0, lastDot) + ".1";
            }
        }
        return null;
    }

    private static int post(String url, Proxy proxy, String body) {
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            connection =
                proxy == null
                    ? (HttpURLConnection) target.openConnection()
                    : (HttpURLConnection) target.openConnection(proxy);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() / 100 != 2) {
                return 0;
            }
            return 1;
        } catch (Exception error) {
            return 0;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String xrayUrl() {
        return "http://" + XRAY_HOST + ":" + DOOR_PORT + "/receipts";
    }

    private static String gatewayUrl(String gateway) {
        return "http://" + gateway + ":" + DOOR_PORT + "/receipts";
    }

    /** Служебный socks ядра: через него запрос и попадает внутрь туннеля */
    private static Proxy controlProxy(Context context) {
        try {
            int port = Integer.parseInt(AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_CONTROL_PORT, "0"));
            if (port <= 0) {
                return null;
            }
            return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
