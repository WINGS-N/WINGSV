package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings({ "PMD.AvoidCatchingGenericException", "PMD.DataClass", "PMD.LawOfDemeter", "PMD.OnlyOneReturn" })
public final class XposedAttackStatsStore {

    public static final String PREFS_NAME = "xposed_attack_stats";

    private static final String KEY_DAILY_COUNTS = "daily_counts";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY_ITEMS = 1200;
    private static final int HISTORY_TTL_DAYS = 7;
    private static final int MAX_DAILY_POINTS = HISTORY_TTL_DAYS;
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Object LOCK = new Object();

    private XposedAttackStatsStore() {}

    public static void recordEvent(@NonNull Context context, @NonNull AttackEvent event) {
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            JSONObject dailyCounts = parseObject(preferences.getString(KEY_DAILY_COUNTS, "{}"));
            JSONArray history = parseArray(preferences.getString(KEY_HISTORY, "[]"));
            boolean changed = pruneExpiredLocked(dailyCounts, history);
            String dayKey = toDayKey(event.timestampMs);
            int currentCount = dailyCounts.optInt(dayKey, 0);
            putQuietly(dailyCounts, dayKey, currentCount + 1);
            trimDailyCounts(dailyCounts);
            history.put(event.toJson());
            trimHistory(history);
            changed = true;
            if (changed) {
                preferences
                    .edit()
                    .putString(KEY_DAILY_COUNTS, dailyCounts.toString())
                    .putString(KEY_HISTORY, history.toString())
                    .commit();
            }
        }
    }

    @NonNull
    public static WeeklySummary getWeeklySummary(@NonNull Context context) {
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            JSONObject dailyCounts = parseObject(preferences.getString(KEY_DAILY_COUNTS, "{}"));
            JSONArray history = parseArray(preferences.getString(KEY_HISTORY, "[]"));
            persistIfChanged(preferences, dailyCounts, history, pruneExpiredLocked(dailyCounts, history));
            List<DailyPoint> points = new ArrayList<>(7);
            LocalDate today = LocalDate.now();
            int total = 0;
            int todayCount = 0;
            for (int index = 6; index >= 0; index--) {
                LocalDate day = today.minusDays(index);
                String key = day.format(DAY_FORMAT);
                int count = dailyCounts.optInt(key, 0);
                if (index == 0) {
                    todayCount = count;
                }
                total += count;
                points.add(new DailyPoint(day, count));
            }
            return new WeeklySummary(points, total, todayCount);
        }
    }

    @NonNull
    public static List<AttackEvent> getRecentEvents(@NonNull Context context, int limit) {
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            JSONObject dailyCounts = parseObject(preferences.getString(KEY_DAILY_COUNTS, "{}"));
            JSONArray history = parseArray(preferences.getString(KEY_HISTORY, "[]"));
            persistIfChanged(preferences, dailyCounts, history, pruneExpiredLocked(dailyCounts, history));
            List<AttackEvent> result = new ArrayList<>(Math.min(limit, history.length()));
            for (int index = history.length() - 1; index >= 0 && result.size() < limit; index--) {
                AttackEvent event = AttackEvent.fromJson(history.optJSONObject(index));
                if (event != null) {
                    result.add(event);
                }
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
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            JSONObject dailyCounts = parseObject(preferences.getString(KEY_DAILY_COUNTS, "{}"));
            JSONArray history = parseArray(preferences.getString(KEY_HISTORY, "[]"));
            persistIfChanged(preferences, dailyCounts, history, pruneExpiredLocked(dailyCounts, history));
            Map<String, MutableAppSummary> grouped = new LinkedHashMap<>();
            String normalizedFilter = normalizeVector(vectorFilter);
            for (int index = history.length() - 1; index >= 0; index--) {
                AttackEvent event = AttackEvent.fromJson(history.optJSONObject(index));
                if (
                    event == null ||
                    TextUtils.isEmpty(event.packageName) ||
                    (!TextUtils.isEmpty(normalizedFilter) && !TextUtils.equals(normalizedFilter, event.vector))
                ) {
                    continue;
                }
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
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            JSONObject dailyCounts = parseObject(preferences.getString(KEY_DAILY_COUNTS, "{}"));
            JSONArray history = parseArray(preferences.getString(KEY_HISTORY, "[]"));
            persistIfChanged(preferences, dailyCounts, history, pruneExpiredLocked(dailyCounts, history));
            LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
            String normalizedPackageName = packageName == null ? "" : packageName.trim();
            for (int index = history.length() - 1; index >= 0; index--) {
                AttackEvent event = AttackEvent.fromJson(history.optJSONObject(index));
                if (
                    event == null ||
                    TextUtils.isEmpty(event.vector) ||
                    (!TextUtils.isEmpty(normalizedPackageName) &&
                        !TextUtils.equals(normalizedPackageName, event.packageName))
                ) {
                    continue;
                }
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
        if (TextUtils.isEmpty(packageName)) {
            return Collections.emptyList();
        }
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            JSONObject dailyCounts = parseObject(preferences.getString(KEY_DAILY_COUNTS, "{}"));
            JSONArray history = parseArray(preferences.getString(KEY_HISTORY, "[]"));
            persistIfChanged(preferences, dailyCounts, history, pruneExpiredLocked(dailyCounts, history));
            List<AttackEvent> result = new ArrayList<>();
            String normalizedFilter = normalizeVector(vectorFilter);
            for (int index = history.length() - 1; index >= 0; index--) {
                AttackEvent event = AttackEvent.fromJson(history.optJSONObject(index));
                if (
                    event != null &&
                    TextUtils.equals(packageName, event.packageName) &&
                    (TextUtils.isEmpty(normalizedFilter) || TextUtils.equals(normalizedFilter, event.vector))
                ) {
                    result.add(event);
                }
            }
            return result;
        }
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

    private static void trimHistory(@NonNull JSONArray history) {
        while (history.length() > MAX_HISTORY_ITEMS) {
            removeIndex(history, 0);
        }
    }

    private static void trimDailyCounts(@NonNull JSONObject dailyCounts) {
        List<String> keys = new ArrayList<>();
        JSONArray names = dailyCounts.names();
        if (names == null) {
            return;
        }
        for (int index = 0; index < names.length(); index++) {
            keys.add(names.optString(index));
        }
        keys.sort(Comparator.naturalOrder());
        while (keys.size() > MAX_DAILY_POINTS) {
            String key = keys.remove(0);
            dailyCounts.remove(key);
        }
    }

    private static boolean pruneExpiredLocked(@NonNull JSONObject dailyCounts, @NonNull JSONArray history) {
        boolean changed = false;
        long cutoffTimestampMs = Instant.now().minus(HISTORY_TTL_DAYS, ChronoUnit.DAYS).toEpochMilli();
        for (int index = history.length() - 1; index >= 0; index--) {
            JSONObject object = history.optJSONObject(index);
            if (object == null || object.optLong("timestamp", 0L) < cutoffTimestampMs) {
                removeIndex(history, index);
                changed = true;
            }
        }
        LocalDate cutoffDay = LocalDate.now().minusDays(HISTORY_TTL_DAYS - 1L);
        List<String> keysToRemove = new ArrayList<>();
        JSONArray names = dailyCounts.names();
        if (names != null) {
            for (int index = 0; index < names.length(); index++) {
                String key = names.optString(index);
                if (TextUtils.isEmpty(key)) {
                    continue;
                }
                try {
                    LocalDate day = LocalDate.parse(key, DAY_FORMAT);
                    if (day.isBefore(cutoffDay)) {
                        keysToRemove.add(key);
                    }
                } catch (Throwable ignored) {
                    keysToRemove.add(key);
                }
            }
        }
        for (String key : keysToRemove) {
            dailyCounts.remove(key);
            changed = true;
        }
        trimDailyCounts(dailyCounts);
        trimHistory(history);
        return changed;
    }

    private static void persistIfChanged(
        @NonNull SharedPreferences preferences,
        @NonNull JSONObject dailyCounts,
        @NonNull JSONArray history,
        boolean changed
    ) {
        if (!changed) {
            return;
        }
        preferences
            .edit()
            .putString(KEY_DAILY_COUNTS, dailyCounts.toString())
            .putString(KEY_HISTORY, history.toString())
            .commit();
    }

    private static void removeIndex(@NonNull JSONArray source, int removeIndex) {
        JSONArray rebuilt = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            if (index == removeIndex) {
                continue;
            }
            rebuilt.put(source.opt(index));
        }
        while (source.length() > 0) {
            source.remove(0);
        }
        for (int index = 0; index < rebuilt.length(); index++) {
            source.put(rebuilt.opt(index));
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
            if (object == null) {
                return null;
            }
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
            if (!TextUtils.isEmpty(normalized)) {
                return normalized;
            }
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
