package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shared JSON-list / active-id / favorites / traffic-stats plumbing for the
 * per-backend profile stores (WireGuardProfileStore, AmneziaProfileStore,
 * VkTurnProfileStore). Keeps the three typed stores thin while avoiding
 * duplicating the same SharedPreferences and JSON boilerplate three times.
 */
@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.UseConcurrentHashMap",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.OnlyOneReturn",
        "PMD.LongVariable",
    }
)
final class ProfileStoreSupport {

    private ProfileStoreSupport() {}

    static SharedPreferences prefs(Context context) {
        return AppPrefs.defaultSharedPreferences(context);
    }

    static JSONArray readArray(Context context, String key) {
        try {
            String raw = prefs(context).getString(key, "[]");
            return new JSONArray(TextUtils.isEmpty(raw) ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    static String getActiveProfileId(Context context, String key) {
        return trim(prefs(context).getString(key, ""));
    }

    static void setActiveProfileId(Context context, String key, String profileId) {
        prefs(context).edit().putString(key, trim(profileId)).commit();
    }

    static Set<String> getFavoriteProfileIds(Context context, String key) {
        Set<String> stored = prefs(context).getStringSet(key, null);
        if (stored == null || stored.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(stored);
    }

    static boolean isFavorite(Context context, String key, String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return false;
        }
        return getFavoriteProfileIds(context, key).contains(profileId);
    }

    static void setFavorite(Context context, String key, String profileId, boolean favorite) {
        if (TextUtils.isEmpty(profileId)) {
            return;
        }
        Set<String> ids = getFavoriteProfileIds(context, key);
        boolean changed = favorite ? ids.add(profileId) : ids.remove(profileId);
        if (!changed) {
            return;
        }
        prefs(context).edit().putStringSet(key, new LinkedHashSet<>(ids)).commit();
    }

    static boolean toggleFavorite(Context context, String key, String profileId) {
        boolean next = !isFavorite(context, key, profileId);
        setFavorite(context, key, profileId, next);
        return next;
    }

    static Map<String, XrayStore.ProfileTrafficStats> getTrafficStatsMap(Context context, String key) {
        LinkedHashMap<String, XrayStore.ProfileTrafficStats> result = new LinkedHashMap<>();
        JSONObject object = parseObject(prefs(context).getString(key, "{}"));
        if (object == null) {
            return result;
        }
        JSONArray names = object.names();
        if (names == null) {
            return result;
        }
        for (int index = 0; index < names.length(); index++) {
            String profileId = trim(names.optString(index));
            if (TextUtils.isEmpty(profileId)) {
                continue;
            }
            JSONObject entry = object.optJSONObject(profileId);
            if (entry == null) {
                continue;
            }
            result.put(
                profileId,
                new XrayStore.ProfileTrafficStats(
                    Math.max(0L, entry.optLong("rx", 0L)),
                    Math.max(0L, entry.optLong("tx", 0L))
                )
            );
        }
        return result;
    }

    static void addTrafficDelta(Context context, String key, String profileId, long rxDelta, long txDelta) {
        String normalizedProfileId = trim(profileId);
        long safeRxDelta = Math.max(0L, rxDelta);
        long safeTxDelta = Math.max(0L, txDelta);
        if (TextUtils.isEmpty(normalizedProfileId) || (safeRxDelta == 0L && safeTxDelta == 0L)) {
            return;
        }
        Map<String, XrayStore.ProfileTrafficStats> current = getTrafficStatsMap(context, key);
        XrayStore.ProfileTrafficStats previous = current.get(normalizedProfileId);
        long nextRx = safeRxDelta + (previous != null ? previous.rxBytes : 0L);
        long nextTx = safeTxDelta + (previous != null ? previous.txBytes : 0L);
        current.put(normalizedProfileId, new XrayStore.ProfileTrafficStats(nextRx, nextTx));
        writeTrafficStats(context, key, current);
    }

    static void resetTrafficStats(Context context, String key, Collection<String> profileIds) {
        if (profileIds == null || profileIds.isEmpty()) {
            return;
        }
        Map<String, XrayStore.ProfileTrafficStats> current = getTrafficStatsMap(context, key);
        boolean changed = false;
        for (String profileId : profileIds) {
            String normalizedProfileId = trim(profileId);
            if (TextUtils.isEmpty(normalizedProfileId)) {
                continue;
            }
            changed |= current.remove(normalizedProfileId) != null;
        }
        if (changed) {
            writeTrafficStats(context, key, current);
        }
    }

    static void writeTrafficStats(Context context, String key, Map<String, XrayStore.ProfileTrafficStats> stats) {
        JSONObject object = new JSONObject();
        if (stats != null) {
            for (Map.Entry<String, XrayStore.ProfileTrafficStats> entry : stats.entrySet()) {
                String profileId = trim(entry.getKey());
                if (TextUtils.isEmpty(profileId) || entry.getValue() == null) {
                    continue;
                }
                try {
                    JSONObject item = new JSONObject();
                    item.put("rx", Math.max(0L, entry.getValue().rxBytes));
                    item.put("tx", Math.max(0L, entry.getValue().txBytes));
                    object.put(profileId, item);
                } catch (Exception ignored) {}
            }
        }
        prefs(context).edit().putString(key, object.toString()).apply();
    }

    static void pruneTrafficStats(Context context, String key, Collection<String> allowedProfileIds) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        if (allowedProfileIds != null) {
            for (String profileId : allowedProfileIds) {
                String normalizedProfileId = trim(profileId);
                if (!TextUtils.isEmpty(normalizedProfileId)) {
                    allowed.add(normalizedProfileId);
                }
            }
        }
        Map<String, XrayStore.ProfileTrafficStats> current = getTrafficStatsMap(context, key);
        if (current.isEmpty()) {
            return;
        }
        current.keySet().retainAll(allowed);
        writeTrafficStats(context, key, current);
    }

    private static JSONObject parseObject(String rawValue) {
        try {
            return new JSONObject(TextUtils.isEmpty(rawValue) ? "{}" : rawValue);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
