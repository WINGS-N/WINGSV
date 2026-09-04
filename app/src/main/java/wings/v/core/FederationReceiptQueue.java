package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Очередь неотправленных расписок.
 *
 * <p>Окно закрывается по счётчикам, а не по факту отправки: как только дельта
 * снята, метка сдвинута, и второй раз тот же трафик не подпишет никто. Значит
 * расписку, которую не приняла сеть, надо держать у себя и досылать, иначе нода
 * получает overclaim за наш обосранный интернет и теряет выплату ни за хуй.
 *
 * <p>Повтор безопасен: башка отбивает дубли по nonce и отвечает успехом, а не
 * ошибкой.
 */
public final class FederationReceiptQueue {

    /** Сколько расписок держим. Дальше выкидываем самые старые */
    private static final int MAX_QUEUED = 1024;

    /** Старше этого башка не принимает, держать такое незачем */
    private static final long MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L;

    /** Один заход за раз: сборщики двух транспортов зовут отправку вперемешку */
    private static final AtomicBoolean SENDING = new AtomicBoolean(false);

    private FederationReceiptQueue() {}

    /** Кладёт расписку в очередь */
    public static void add(@NonNull Context context, @NonNull JSONObject receipt) {
        JSONArray queue = read(context);
        queue.put(receipt);
        write(context, trim(queue));
    }

    /**
     * Отправляет всё, что накопилось, и выкидывает принятое.
     *
     * @return сколько расписок башка забрала
     */
    public static int flush(@NonNull Context context) throws Exception {
        if (!SENDING.compareAndSet(false, true)) {
            return 0;
        }
        try {
            JSONArray queue = trim(read(context));
            if (queue.length() == 0) {
                write(context, queue);
                return 0;
            }
            int accepted = FederationAccount.sendReceipts(context, queue);
            // Отвеченное принимаем целиком: дубли башка отбивает сама, и
            // держать их до второго пришествия смысла нет
            forget(context, queue);
            return accepted;
        } finally {
            SENDING.set(false);
        }
    }

    /** Сколько лежит неотправленного */
    public static int size(@NonNull Context context) {
        return read(context).length();
    }

    /** Забывает очередь: после выхода из аккаунта расписки нахуй ничьи */
    public static void reset(@NonNull Context context) {
        AppPrefs.prefs(context).edit().remove(AppPrefs.KEY_FEDERATION_RECEIPT_QUEUE).apply();
    }

    /** Снимает из очереди то, что уехало, по nonce */
    private static void forget(Context context, JSONArray sent) {
        Set<String> gone = new HashSet<>();
        for (int i = 0; i < sent.length(); i++) {
            JSONObject item = sent.optJSONObject(i);
            if (item != null) {
                gone.add(item.optString("nonce", ""));
            }
        }
        JSONArray keep = new JSONArray();
        JSONArray queue = read(context);
        for (int i = 0; i < queue.length(); i++) {
            JSONObject item = queue.optJSONObject(i);
            if (item != null && !gone.contains(item.optString("nonce", ""))) {
                keep.put(item);
            }
        }
        write(context, keep);
    }

    /** Выкидывает протухшее и лишнее с головы, где лежит самое старое */
    private static JSONArray trim(JSONArray queue) {
        long oldest = (System.currentTimeMillis() - MAX_AGE_MS) / 1000L;
        JSONArray fresh = new JSONArray();
        for (int i = 0; i < queue.length(); i++) {
            JSONObject item = queue.optJSONObject(i);
            if (item != null && item.optLong("window_end_unix", 0L) >= oldest) {
                fresh.put(item);
            }
        }
        if (fresh.length() <= MAX_QUEUED) {
            return fresh;
        }
        JSONArray cut = new JSONArray();
        for (int i = fresh.length() - MAX_QUEUED; i < fresh.length(); i++) {
            cut.put(fresh.opt(i));
        }
        return cut;
    }

    private static JSONArray read(Context context) {
        String raw = AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_RECEIPT_QUEUE, "");
        if (TextUtils.isEmpty(raw)) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (Exception error) {
            // Разъебалось - начинаем с пустой, чинить тут нечего
            return new JSONArray();
        }
    }

    private static void write(Context context, JSONArray queue) {
        AppPrefs.prefs(context).edit().putString(AppPrefs.KEY_FEDERATION_RECEIPT_QUEUE, queue.toString()).apply();
    }
}
