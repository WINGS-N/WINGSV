package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import java.security.SecureRandom;
import java.util.Locale;
import org.json.JSONObject;
import wings.v.xray.XrayStatsClient;

/**
 * Расписки о полученном трафике: сколько байт устройство реально протащило.
 *
 * <p>Цифры снимаются с самого Xray, а не с интерфейса туннеля. Интерфейсный
 * счётчик тащит за собой локальный DNS, keepalive и оверхед туннеля, и стороны
 * потом считают разное и не сходятся нихуя.
 *
 * <p>Подписывает устройство своим ключом, у ноды его нет и не будет. Иначе нода
 * высрет расписку за клиента и получит деньги за трафик, которого не было.
 */
public final class FederationReceipts {

    /** Как часто снимаем показания. Чаще незачем, дельты и так копятся сами */
    public static final long WINDOW_MS = 5 * 60 * 1000L;

    /** Меньше этого за окно - не расписка, а шум, нехуй гонять его наверх */
    private static final long MIN_BYTES = 64 * 1024L;

    private FederationReceipts() {}

    /** Показания на конец прошлого окна */
    private static final class Mark {

        long uplink;
        long downlink;
        long atMs;
    }

    /**
     * Снимает окно и отдаёт готовую подписанную расписку, либо null, когда
     * отдавать нечего.
     *
     * @param nodeId кто вёз трафик, по мнению приложения
     */
    public static JSONObject collect(
        @NonNull Context context,
        @NonNull XrayStatsClient stats,
        @NonNull String nodeId,
        @NonNull String transport
    ) {
        return collect(context, stats.readUplinkBytes(), stats.readDownlinkBytes(), nodeId, transport);
    }

    /**
     * Тот же сбор, но с уже снятыми счётчиками.
     *
     * <p>У VK TURN своего Xray нет, и полезную нагрузку считает сам релей. Брать
     * её с интерфейса нельзя: туда попадает локальный DNS, keepalive и оверхед
     * туннеля, и стороны потом не сойдутся никогда.
     */
    public static JSONObject collect(
        @NonNull Context context,
        long uplinkTotal,
        long downlinkTotal,
        @NonNull String nodeId,
        @NonNull String transport
    ) {
        String clientId = AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_SUBJECT_ID, "");
        if (TextUtils.isEmpty(clientId) || TextUtils.isEmpty(nodeId)) {
            return null;
        }
        // Окно не прошло - выходим сразу, не дёргая ядро: сэмплер бежит по
        // несколько раз в секунду, и лезть к нему за счётчиками так часто незачем
        Mark mark = readMark(context);
        long since = System.currentTimeMillis() - mark.atMs;
        if (mark.atMs > 0 && since < WINDOW_MS) {
            return null;
        }
        long uplink = uplinkTotal;
        long downlink = downlinkTotal;
        // Счётчика, которого ещё нет, ядро отдаёт ошибкой, а не нулём. Принять
        // это за обнуление - значит выписать расписку на весь трафик заново
        if (uplink < 0 || downlink < 0) {
            return null;
        }

        long now = System.currentTimeMillis();
        Mark previous = mark;
        long upDelta = uplink - previous.uplink;
        long downDelta = downlink - previous.downlink;
        // Ядро дёрнули, счётчики поехали с нуля. Дельта тут не значит нихуя,
        // поэтому пере-базируемся и ждём следующего окна
        if (upDelta < 0 || downDelta < 0) {
            writeMark(context, uplink, downlink, now);
            return null;
        }
        long start = previous.atMs > 0 ? previous.atMs : now;
        writeMark(context, uplink, downlink, now);
        if (upDelta + downDelta < MIN_BYTES) {
            return null;
        }

        String nonce = newNonce();
        long startUnix = start / 1000L;
        long endUnix = now / 1000L;
        String signature = FederationKey.sign(
            context,
            clientId,
            nodeId,
            transport,
            nonce,
            startUnix,
            endUnix,
            upDelta,
            downDelta
        );
        if (signature == null) {
            return null;
        }
        try {
            JSONObject receipt = new JSONObject();
            receipt.put("client_id", clientId);
            receipt.put("node_id", nodeId);
            receipt.put("transport", transport);
            receipt.put("window_start_unix", startUnix);
            receipt.put("window_end_unix", endUnix);
            receipt.put("payload_up_bytes", upDelta);
            receipt.put("payload_down_bytes", downDelta);
            receipt.put("nonce", nonce);
            receipt.put("signature", signature);
            return receipt;
        } catch (Exception error) {
            return null;
        }
    }

    /** Забывает показания: после выхода из аккаунта они нахуй ничьи */
    public static void reset(@NonNull Context context) {
        AppPrefs.prefs(context).edit().remove(AppPrefs.KEY_FEDERATION_RECEIPT_MARK).apply();
    }

    private static Mark readMark(Context context) {
        Mark mark = new Mark();
        String raw = AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_RECEIPT_MARK, "");
        if (TextUtils.isEmpty(raw)) {
            return mark;
        }
        try {
            JSONObject json = new JSONObject(raw);
            mark.uplink = json.optLong("up", 0L);
            mark.downlink = json.optLong("down", 0L);
            mark.atMs = json.optLong("at", 0L);
        } catch (Exception ignored) {
            // Разъебалось - начнём окно заново, одно потерянное окно не беда
        }
        return mark;
    }

    private static void writeMark(Context context, long uplink, long downlink, long atMs) {
        try {
            JSONObject json = new JSONObject();
            json.put("up", uplink);
            json.put("down", downlink);
            json.put("at", atMs);
            AppPrefs.prefs(context).edit().putString(AppPrefs.KEY_FEDERATION_RECEIPT_MARK, json.toString()).apply();
        } catch (Exception ignored) {
            // Не записалось - следующее окно само пере-базируется, хуй с ним
        }
    }

    /** Без него нода предъявит одну расписку за десяток окон подряд и не
     * поперхнётся */
    private static String newNonce() {
        byte[] raw = new byte[12];
        new SecureRandom().nextBytes(raw);
        StringBuilder out = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            out.append(String.format(Locale.US, "%02x", value));
        }
        return out.toString();
    }
}
