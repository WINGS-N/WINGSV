package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class SharingTrafficStatsStore {

    private static final String PREFS = "sharing_traffic_stats";
    private static final String KEY_CLIENTS = "clients";
    private static final int WINDOW_DAYS = 7;

    private final SharedPreferences prefs;
    private final Map<String, ClientHistory> history = new LinkedHashMap<>();
    private final Map<String, long[]> sessionBaselines = new LinkedHashMap<>();
    private final Object lock = new Object();

    public SharingTrafficStatsStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public void applyCounters(@NonNull List<CounterSnapshot> snapshots) {
        synchronized (lock) {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            for (CounterSnapshot snapshot : snapshots) {
                String key = macKey(snapshot.mac);
                long[] baseline = sessionBaselines.get(key);
                if (baseline == null) {
                    baseline = new long[] { snapshot.sentBytes, snapshot.receivedBytes };
                    sessionBaselines.put(key, baseline);
                    continue;
                }
                long sentDelta = Math.max(0L, snapshot.sentBytes - baseline[0]);
                long recvDelta = Math.max(0L, snapshot.receivedBytes - baseline[1]);
                baseline[0] = snapshot.sentBytes;
                baseline[1] = snapshot.receivedBytes;
                if (sentDelta == 0L && recvDelta == 0L) continue;
                ClientHistory entry = history.get(key);
                if (entry == null) {
                    entry = new ClientHistory(snapshot.mac, snapshot.downstream);
                    history.put(key, entry);
                }
                entry.lastSeenMillis = System.currentTimeMillis();
                entry.lastDownstream = snapshot.downstream;
                entry.appendToday(today, sentDelta, recvDelta);
            }
            save();
        }
    }

    public void resetSession() {
        synchronized (lock) {
            sessionBaselines.clear();
        }
    }

    @NonNull
    public WeeklyTraffic getAggregateWeekly() {
        synchronized (lock) {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            long[][] totals = new long[WINDOW_DAYS][2];
            for (ClientHistory entry : history.values()) {
                entry.aggregateInto(totals, today);
            }
            return buildWeekly(totals, today);
        }
    }

    @Nullable
    public WeeklyTraffic getClientWeekly(@NonNull byte[] mac) {
        synchronized (lock) {
            ClientHistory entry = history.get(macKey(mac));
            if (entry == null) return null;
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            long[][] totals = new long[WINDOW_DAYS][2];
            entry.aggregateInto(totals, today);
            return buildWeekly(totals, today);
        }
    }

    @NonNull
    public List<ClientSummary> getClientSummaries() {
        synchronized (lock) {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            List<ClientSummary> result = new ArrayList<>(history.size());
            for (ClientHistory entry : history.values()) {
                long[] totals = entry.totals();
                long[][] weekly = new long[WINDOW_DAYS][2];
                entry.aggregateInto(weekly, today);
                long weekSent = 0L;
                long weekRecv = 0L;
                for (long[] day : weekly) {
                    weekSent += day[0];
                    weekRecv += day[1];
                }
                result.add(
                    new ClientSummary(
                        entry.mac.clone(),
                        entry.lastDownstream,
                        entry.lastSeenMillis,
                        totals[0],
                        totals[1],
                        weekSent,
                        weekRecv
                    )
                );
            }
            Collections.sort(result, (a, b) -> Long.compare(b.lastSeenMillis, a.lastSeenMillis));
            return result;
        }
    }

    private void load() {
        String json = prefs.getString(KEY_CLIENTS, null);
        if (json == null) return;
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject obj = array.getJSONObject(index);
                byte[] mac = decodeMac(obj.getString("mac"));
                if (mac == null) continue;
                ClientHistory entry = new ClientHistory(mac, obj.optString("downstream", ""));
                entry.lastSeenMillis = obj.optLong("lastSeen", 0L);
                entry.lifetimeSent = obj.optLong("lifetimeSent", 0L);
                entry.lifetimeReceived = obj.optLong("lifetimeReceived", 0L);
                JSONArray days = obj.optJSONArray("days");
                if (days != null) {
                    for (int dayIdx = 0; dayIdx < days.length(); dayIdx++) {
                        JSONObject dayObj = days.getJSONObject(dayIdx);
                        entry.daily.put(
                            LocalDate.parse(dayObj.getString("date")),
                            new long[] { dayObj.optLong("sent", 0L), dayObj.optLong("recv", 0L) }
                        );
                    }
                }
                history.put(macKey(mac), entry);
            }
        } catch (JSONException ignored) {}
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (ClientHistory entry : history.values()) {
                JSONObject obj = new JSONObject();
                obj.put("mac", encodeMac(entry.mac));
                obj.put("downstream", entry.lastDownstream);
                obj.put("lastSeen", entry.lastSeenMillis);
                obj.put("lifetimeSent", entry.lifetimeSent);
                obj.put("lifetimeReceived", entry.lifetimeReceived);
                JSONArray days = new JSONArray();
                for (Map.Entry<LocalDate, long[]> dayEntry : entry.daily.entrySet()) {
                    JSONObject dayObj = new JSONObject();
                    dayObj.put("date", dayEntry.getKey().toString());
                    dayObj.put("sent", dayEntry.getValue()[0]);
                    dayObj.put("recv", dayEntry.getValue()[1]);
                    days.put(dayObj);
                }
                obj.put("days", days);
                array.put(obj);
            }
            prefs.edit().putString(KEY_CLIENTS, array.toString()).apply();
        } catch (JSONException ignored) {}
    }

    @NonNull
    private static WeeklyTraffic buildWeekly(@NonNull long[][] totals, @NonNull LocalDate today) {
        List<DailyTraffic> points = new ArrayList<>(WINDOW_DAYS);
        long maxSent = 0L;
        long maxRecv = 0L;
        long totalSent = 0L;
        long totalRecv = 0L;
        for (int index = 0; index < WINDOW_DAYS; index++) {
            LocalDate day = today.minusDays(WINDOW_DAYS - 1 - index);
            long sent = totals[index][0];
            long recv = totals[index][1];
            points.add(new DailyTraffic(day, sent, recv));
            maxSent = Math.max(maxSent, sent);
            maxRecv = Math.max(maxRecv, recv);
            totalSent += sent;
            totalRecv += recv;
        }
        return new WeeklyTraffic(points, totalSent, totalRecv, maxSent, maxRecv);
    }

    @NonNull
    private static String macKey(@NonNull byte[] mac) {
        StringBuilder sb = new StringBuilder(mac.length * 2);
        for (byte value : mac) {
            sb.append(String.format(Locale.ROOT, "%02x", value));
        }
        return sb.toString();
    }

    @NonNull
    private static String encodeMac(@NonNull byte[] mac) {
        return macKey(mac);
    }

    @Nullable
    private static byte[] decodeMac(@NonNull String hex) {
        if (hex.length() == 0 || hex.length() % 2 != 0) return null;
        byte[] result = new byte[hex.length() / 2];
        for (int index = 0; index < result.length; index++) {
            try {
                result[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return result;
    }

    private static final class ClientHistory {

        @NonNull
        final byte[] mac;

        @NonNull
        String lastDownstream;

        long lastSeenMillis;
        long lifetimeSent;
        long lifetimeReceived;
        final LinkedHashMap<LocalDate, long[]> daily = new LinkedHashMap<>();

        ClientHistory(@NonNull byte[] mac, @NonNull String downstream) {
            this.mac = mac;
            this.lastDownstream = downstream;
        }

        void appendToday(@NonNull LocalDate today, long sentDelta, long recvDelta) {
            long[] entry = daily.get(today);
            if (entry == null) {
                entry = new long[2];
                daily.put(today, entry);
            }
            entry[0] += sentDelta;
            entry[1] += recvDelta;
            lifetimeSent += sentDelta;
            lifetimeReceived += recvDelta;
            trim(today);
        }

        void trim(@NonNull LocalDate today) {
            LocalDate cutoff = today.minusDays(WINDOW_DAYS - 1);
            daily.entrySet().removeIf(entry -> entry.getKey().isBefore(cutoff));
        }

        void aggregateInto(@NonNull long[][] totals, @NonNull LocalDate today) {
            trim(today);
            for (Map.Entry<LocalDate, long[]> dayEntry : daily.entrySet()) {
                int slot = WINDOW_DAYS - 1 - (int) java.time.temporal.ChronoUnit.DAYS.between(dayEntry.getKey(), today);
                if (slot < 0 || slot >= WINDOW_DAYS) continue;
                totals[slot][0] += dayEntry.getValue()[0];
                totals[slot][1] += dayEntry.getValue()[1];
            }
        }

        @NonNull
        long[] totals() {
            return new long[] { lifetimeSent, lifetimeReceived };
        }
    }

    public static final class CounterSnapshot {

        @NonNull
        public final byte[] mac;

        @NonNull
        public final String downstream;

        public final long sentBytes;
        public final long receivedBytes;

        public CounterSnapshot(@NonNull byte[] mac, @NonNull String downstream, long sentBytes, long receivedBytes) {
            this.mac = mac;
            this.downstream = downstream;
            this.sentBytes = sentBytes;
            this.receivedBytes = receivedBytes;
        }
    }

    public static final class DailyTraffic {

        @NonNull
        public final LocalDate day;

        public final long sentBytes;
        public final long receivedBytes;

        public DailyTraffic(@NonNull LocalDate day, long sentBytes, long receivedBytes) {
            this.day = day;
            this.sentBytes = sentBytes;
            this.receivedBytes = receivedBytes;
        }

        @NonNull
        public String getWeekLabel() {
            return day.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, new Locale("ru"));
        }
    }

    public static final class WeeklyTraffic {

        @NonNull
        public final List<DailyTraffic> points;

        public final long totalSentBytes;
        public final long totalReceivedBytes;
        public final long maxDailySentBytes;
        public final long maxDailyReceivedBytes;

        public WeeklyTraffic(
            @NonNull List<DailyTraffic> points,
            long totalSentBytes,
            long totalReceivedBytes,
            long maxDailySentBytes,
            long maxDailyReceivedBytes
        ) {
            this.points = points;
            this.totalSentBytes = totalSentBytes;
            this.totalReceivedBytes = totalReceivedBytes;
            this.maxDailySentBytes = maxDailySentBytes;
            this.maxDailyReceivedBytes = maxDailyReceivedBytes;
        }

        public long getMaxDailyBytes() {
            return Math.max(maxDailySentBytes, maxDailyReceivedBytes);
        }
    }

    public static final class ClientSummary {

        @NonNull
        public final byte[] mac;

        @NonNull
        public final String lastDownstream;

        public final long lastSeenMillis;
        public final long lifetimeSentBytes;
        public final long lifetimeReceivedBytes;
        public final long weeklySentBytes;
        public final long weeklyReceivedBytes;

        public ClientSummary(
            @NonNull byte[] mac,
            @NonNull String lastDownstream,
            long lastSeenMillis,
            long lifetimeSentBytes,
            long lifetimeReceivedBytes,
            long weeklySentBytes,
            long weeklyReceivedBytes
        ) {
            this.mac = mac;
            this.lastDownstream = lastDownstream;
            this.lastSeenMillis = lastSeenMillis;
            this.lifetimeSentBytes = lifetimeSentBytes;
            this.lifetimeReceivedBytes = lifetimeReceivedBytes;
            this.weeklySentBytes = weeklySentBytes;
            this.weeklyReceivedBytes = weeklyReceivedBytes;
        }

        @NonNull
        public String formatMac() {
            StringBuilder sb = new StringBuilder(mac.length * 3);
            for (byte value : mac) {
                if (sb.length() > 0) sb.append(':');
                sb.append(String.format(Locale.ROOT, "%02x", value));
            }
            return sb.toString();
        }
    }
}
