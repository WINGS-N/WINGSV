package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings({ "PMD.AvoidCatchingGenericException", "PMD.DataClass", "PMD.LawOfDemeter", "PMD.OnlyOneReturn" })
public final class XposedAttackStatsStore {

    public static final String PREFS_NAME = "xposed_attack_stats";

    private static final String KEY_DAILY_COUNTS = "daily_counts";
    private static final String KEY_HISTORY = "history";
    private static final int HISTORY_TTL_DAYS = 7;
    private static final int MAX_EVENTS = 5000;
    private static final long PRUNE_INTERVAL_MS = 60_000L;
    private static final long WRITE_DEBOUNCE_MS = 500L;
    private static final long DEDUP_WINDOW_MS = 2_000L;
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Object LOCK = new Object();

    private static boolean loaded;
    private static final List<AttackEvent> HISTORY = new ArrayList<>();
    private static final Map<String, Integer> DAILY_COUNTS = new HashMap<>();
    private static final Map<String, Integer> RECENT_EVENT_INDEX = new HashMap<>();
    private static long lastPruneTimestampMs;

    private static final AtomicBoolean WRITE_PENDING = new AtomicBoolean(false);

    @Nullable
    private static Handler writeHandler;

    private XposedAttackStatsStore() {}

    public static void recordEvent(@NonNull Context context, @NonNull AttackEvent event) {
        SharedPreferences preferences = prefs(context);
        synchronized (LOCK) {
            ensureLoadedLocked(preferences);
            maybePruneLocked(event.timestampMs, false);
            String dedupKey = event.packageName + " " + event.vector;
            Integer cachedIndex = RECENT_EVENT_INDEX.get(dedupKey);
            if (cachedIndex != null && cachedIndex >= 0 && cachedIndex < HISTORY.size()) {
                AttackEvent last = HISTORY.get(cachedIndex);
                if (
                    event.timestampMs - last.timestampMs <= DEDUP_WINDOW_MS &&
                    last.packageName.equals(event.packageName) &&
                    last.vector.equals(event.vector)
                ) {
                    AttackEvent merged = new AttackEvent(
                        event.timestampMs,
                        event.packageName,
                        event.vector,
                        event.source,
                        event.callerMethod,
                        event.detail
                    );
                    HISTORY.set(cachedIndex, merged);
                    scheduleWriteLocked(context);
                    return;
                }
            }
            HISTORY.add(event);
            RECENT_EVENT_INDEX.put(dedupKey, HISTORY.size() - 1);
            bumpDailyCountLocked(event.timestampMs);
            if (HISTORY.size() > MAX_EVENTS) {
                trimHistoryLocked();
            }
            scheduleWriteLocked(context);
        }
    }

    @NonNull
    public static WeeklySummary getWeeklySummary(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        synchronized (LOCK) {
            ensureLoadedLocked(preferences);
            maybePruneLocked(System.currentTimeMillis(), false);
            List<DailyPoint> points = new ArrayList<>(7);
            LocalDate today = LocalDate.now();
            int total = 0;
            int todayCount = 0;
            for (int index = 6; index >= 0; index--) {
                LocalDate day = today.minusDays(index);
                String key = day.format(DAY_FORMAT);
                Integer count = DAILY_COUNTS.get(key);
                int value = count == null ? 0 : count;
                if (index == 0) {
                    todayCount = value;
                }
                total += value;
                points.add(new DailyPoint(day, value));
            }
            return new WeeklySummary(points, total, todayCount);
        }
    }

    @NonNull
    public static List<AttackEvent> getRecentEvents(@NonNull Context context, int limit) {
        SharedPreferences preferences = prefs(context);
        synchronized (LOCK) {
            ensureLoadedLocked(preferences);
            maybePruneLocked(System.currentTimeMillis(), false);
            int count = Math.min(limit, HISTORY.size());
            List<AttackEvent> result = new ArrayList<>(count);
            for (int index = HISTORY.size() - 1; index >= 0 && result.size() < limit; index--) {
                result.add(HISTORY.get(index));
            }
            return result;
        }
    }

    @NonNull
    public static List<AppAttackSummary> getAppSummaries(@NonNull Context context) {
        return getAppSummaries(context, "");
    }

    @NonNull
    public static List<AppAttackSummary> getAppSummaries(@NonNull Context context, @Nullable String vectorFilter) {
        SharedPreferences preferences = prefs(context);
        synchronized (LOCK) {
            ensureLoadedLocked(preferences);
            maybePruneLocked(System.currentTimeMillis(), false);
            String normalizedFilter = normalizeVector(vectorFilter);
            Map<String, MutableAppSummary> grouped = new LinkedHashMap<>();
            for (int index = HISTORY.size() - 1; index >= 0; index--) {
                AttackEvent event = HISTORY.get(index);
                if (TextUtils.isEmpty(event.packageName)) continue;
                if (!TextUtils.isEmpty(normalizedFilter) && !TextUtils.equals(normalizedFilter, event.vector)) continue;
                MutableAppSummary summary = grouped.get(event.packageName);
                if (summary == null) {
                    summary = new MutableAppSummary(event.packageName);
                    grouped.put(event.packageName, summary);
                }
                summary.count++;
                if (event.timestampMs > summary.lastTimestampMs) {
                    summary.lastTimestampMs = event.timestampMs;
                    summary.lastVector = event.vector;
                }
            }
            List<AppAttackSummary> result = new ArrayList<>(grouped.size());
            for (MutableAppSummary summary : grouped.values()) {
                result.add(
                    new AppAttackSummary(
                        summary.packageName,
                        summary.count,
                        summary.lastTimestampMs,
                        summary.lastVector
                    )
                );
            }
            result.sort(Comparator.comparingLong((AppAttackSummary item) -> item.lastTimestampMs).reversed());
            return result;
        }
    }

    @NonNull
    public static List<String> getKnownVectors(@NonNull Context context) {
        return getKnownVectors(context, "");
    }

    @NonNull
    public static List<String> getKnownVectors(@NonNull Context context, @Nullable String packageName) {
        SharedPreferences preferences = prefs(context);
        synchronized (LOCK) {
            ensureLoadedLocked(preferences);
            maybePruneLocked(System.currentTimeMillis(), false);
            LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
            String normalized = packageName == null ? "" : packageName.trim();
            for (int index = HISTORY.size() - 1; index >= 0; index--) {
                AttackEvent event = HISTORY.get(index);
                if (TextUtils.isEmpty(event.vector)) continue;
                if (!TextUtils.isEmpty(normalized) && !TextUtils.equals(normalized, event.packageName)) continue;
                ordered.put(event.vector, Boolean.TRUE);
            }
            return new ArrayList<>(ordered.keySet());
        }
    }

    @NonNull
    public static List<AttackEvent> getEventsForPackage(@NonNull Context context, @Nullable String packageName) {
        return getEventsForPackage(context, packageName, "");
    }

    @NonNull
    public static List<AttackEvent> getEventsForPackage(
        @NonNull Context context,
        @Nullable String packageName,
        @Nullable String vectorFilter
    ) {
        if (TextUtils.isEmpty(packageName)) return Collections.emptyList();
        SharedPreferences preferences = prefs(context);
        synchronized (LOCK) {
            ensureLoadedLocked(preferences);
            maybePruneLocked(System.currentTimeMillis(), false);
            List<AttackEvent> result = new ArrayList<>();
            String normalizedFilter = normalizeVector(vectorFilter);
            for (int index = HISTORY.size() - 1; index >= 0; index--) {
                AttackEvent event = HISTORY.get(index);
                if (!TextUtils.equals(packageName, event.packageName)) continue;
                if (!TextUtils.isEmpty(normalizedFilter) && !TextUtils.equals(normalizedFilter, event.vector)) continue;
                result.add(event);
            }
            return result;
        }
    }

    private static void ensureLoadedLocked(@NonNull SharedPreferences preferences) {
        if (loaded) return;
        loaded = true;
        JSONObject storedDaily = parseObject(preferences.getString(KEY_DAILY_COUNTS, "{}"));
        JSONArray storedHistory = parseArray(preferences.getString(KEY_HISTORY, "[]"));
        JSONArray names = storedDaily.names();
        if (names != null) {
            for (int index = 0; index < names.length(); index++) {
                String key = names.optString(index);
                if (!TextUtils.isEmpty(key)) {
                    DAILY_COUNTS.put(key, storedDaily.optInt(key, 0));
                }
            }
        }
        for (int index = 0; index < storedHistory.length(); index++) {
            AttackEvent event = AttackEvent.fromJson(storedHistory.optJSONObject(index));
            if (event != null) {
                HISTORY.add(event);
                RECENT_EVENT_INDEX.put(event.packageName + " " + event.vector, HISTORY.size() - 1);
            }
        }
        maybePruneLocked(System.currentTimeMillis(), true);
    }

    private static void bumpDailyCountLocked(long timestampMs) {
        String key = toDayKey(timestampMs);
        Integer current = DAILY_COUNTS.get(key);
        DAILY_COUNTS.put(key, (current == null ? 0 : current) + 1);
    }

    private static boolean maybePruneLocked(long nowMs, boolean force) {
        if (!force && nowMs - lastPruneTimestampMs < PRUNE_INTERVAL_MS) {
            return false;
        }
        lastPruneTimestampMs = nowMs;
        long cutoffMs = Instant.ofEpochMilli(nowMs).minus(HISTORY_TTL_DAYS, ChronoUnit.DAYS).toEpochMilli();
        boolean changed = false;
        int write = 0;
        for (int read = 0; read < HISTORY.size(); read++) {
            AttackEvent event = HISTORY.get(read);
            if (event.timestampMs < cutoffMs) {
                changed = true;
                continue;
            }
            if (write != read) {
                HISTORY.set(write, event);
            }
            write++;
        }
        if (changed) {
            while (HISTORY.size() > write) {
                HISTORY.remove(HISTORY.size() - 1);
            }
            rebuildRecentIndexLocked();
        }
        LocalDate cutoffDay = LocalDate.now().minusDays(HISTORY_TTL_DAYS - 1L);
        List<String> toRemove = new ArrayList<>();
        for (String key : DAILY_COUNTS.keySet()) {
            try {
                if (LocalDate.parse(key, DAY_FORMAT).isBefore(cutoffDay)) {
                    toRemove.add(key);
                }
            } catch (Throwable ignored) {
                toRemove.add(key);
            }
        }
        if (!toRemove.isEmpty()) {
            for (String key : toRemove) DAILY_COUNTS.remove(key);
            changed = true;
        }
        return changed;
    }

    private static void trimHistoryLocked() {
        int excess = HISTORY.size() - MAX_EVENTS;
        if (excess <= 0) return;
        HISTORY.subList(0, excess).clear();
        rebuildRecentIndexLocked();
    }

    private static void rebuildRecentIndexLocked() {
        RECENT_EVENT_INDEX.clear();
        for (int index = 0; index < HISTORY.size(); index++) {
            AttackEvent event = HISTORY.get(index);
            RECENT_EVENT_INDEX.put(event.packageName + " " + event.vector, index);
        }
    }

    private static void scheduleWriteLocked(@NonNull Context context) {
        if (writeHandler == null) {
            HandlerThread thread = new HandlerThread("WingsVXposedStatsWriter");
            thread.setDaemon(true);
            thread.start();
            writeHandler = new Handler(thread.getLooper());
        }
        if (WRITE_PENDING.compareAndSet(false, true)) {
            final Context appContext = context.getApplicationContext();
            writeHandler.postDelayed(() -> flushToDisk(appContext), WRITE_DEBOUNCE_MS);
        }
    }

    private static void flushToDisk(@NonNull Context context) {
        WRITE_PENDING.set(false);
        SharedPreferences preferences = prefs(context);
        JSONObject dailySnapshot;
        JSONArray HISTORYSnapshot;
        synchronized (LOCK) {
            dailySnapshot = new JSONObject();
            for (Map.Entry<String, Integer> entry : DAILY_COUNTS.entrySet()) {
                putQuietly(dailySnapshot, entry.getKey(), entry.getValue());
            }
            HISTORYSnapshot = new JSONArray();
            for (AttackEvent event : HISTORY) {
                HISTORYSnapshot.put(event.toJson());
            }
        }
        preferences
            .edit()
            .putString(KEY_DAILY_COUNTS, dailySnapshot.toString())
            .putString(KEY_HISTORY, HISTORYSnapshot.toString())
            .apply();
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static JSONObject parseObject(@Nullable String raw) {
        try {
            return TextUtils.isEmpty(raw) ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private static JSONArray parseArray(@Nullable String raw) {
        try {
            return TextUtils.isEmpty(raw) ? new JSONArray() : new JSONArray(raw);
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    @NonNull
    public static String toDayKey(long timestampMs) {
        return Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate().format(DAY_FORMAT);
    }

    private static void putQuietly(@NonNull JSONObject object, @NonNull String key, @Nullable Object value) {
        try {
            object.put(key, value);
        } catch (Throwable ignored) {}
    }

    @NonNull
    private static String normalizeVector(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    public static final class AttackEvent {

        public final long timestampMs;

        @NonNull
        public final String packageName;

        @NonNull
        public final String vector;

        @NonNull
        public final String source;

        @NonNull
        public final String callerMethod;

        @NonNull
        public final String detail;

        public AttackEvent(
            long timestampMs,
            @Nullable String packageName,
            @Nullable String vector,
            @Nullable String source,
            @Nullable String callerMethod,
            @Nullable String detail
        ) {
            this.timestampMs = timestampMs;
            this.packageName = normalize(packageName);
            this.vector = normalize(vector);
            this.source = normalizeSource(source, this.vector);
            this.callerMethod = normalize(callerMethod);
            this.detail = normalize(detail);
        }

        @NonNull
        JSONObject toJson() {
            JSONObject object = new JSONObject();
            putQuietly(object, "timestamp", timestampMs);
            putQuietly(object, "packageName", packageName);
            putQuietly(object, "vector", vector);
            putQuietly(object, "source", source);
            putQuietly(object, "callerMethod", callerMethod);
            putQuietly(object, "detail", detail);
            return object;
        }

        @Nullable
        static AttackEvent fromJson(@Nullable JSONObject object) {
            if (object == null) return null;
            return new AttackEvent(
                object.optLong("timestamp", 0L),
                object.optString("packageName", ""),
                object.optString("vector", ""),
                object.optString("source", ""),
                object.optString("callerMethod", ""),
                object.optString("detail", "")
            );
        }

        @NonNull
        private static String normalize(@Nullable String value) {
            return value == null ? "" : value.trim();
        }

        @NonNull
        private static String normalizeSource(@Nullable String value, @NonNull String vector) {
            String normalized = normalize(value);
            if (!TextUtils.isEmpty(normalized)) return normalized;
            return vector.startsWith("native_") ? "native" : "java";
        }
    }

    public static final class DailyPoint {

        @NonNull
        public final LocalDate day;

        public final int count;

        DailyPoint(@NonNull LocalDate day, int count) {
            this.day = day;
            this.count = count;
        }

        @NonNull
        public String getWeekLabel() {
            return day.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, new Locale("ru"));
        }
    }

    public static final class WeeklySummary {

        @NonNull
        public final List<DailyPoint> points;

        public final int totalCount;
        public final int todayCount;

        public WeeklySummary(@NonNull List<DailyPoint> points, int totalCount, int todayCount) {
            this.points = points;
            this.totalCount = totalCount;
            this.todayCount = todayCount;
        }

        public int getMaxCount() {
            int max = 0;
            for (DailyPoint point : points) {
                max = Math.max(max, point.count);
            }
            return max;
        }
    }

    public static final class AppAttackSummary {

        @NonNull
        public final String packageName;

        public final int count;
        public final long lastTimestampMs;

        @NonNull
        public final String lastVector;

        AppAttackSummary(@NonNull String packageName, int count, long lastTimestampMs, @NonNull String lastVector) {
            this.packageName = packageName;
            this.count = count;
            this.lastTimestampMs = lastTimestampMs;
            this.lastVector = lastVector;
        }
    }

    private static final class MutableAppSummary {

        @NonNull
        final String packageName;

        int count;
        long lastTimestampMs;

        @NonNull
        String lastVector = "";

        MutableAppSummary(@NonNull String packageName) {
            this.packageName = packageName;
        }
    }
}
