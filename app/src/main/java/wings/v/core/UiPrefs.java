package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.CommentDefaultAccessModifier",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.LongVariable",
    }
)
public final class UiPrefs {

    public static final String NAVBAR_HOME = "home";
    public static final String NAVBAR_PROFILES = "profiles";
    public static final String NAVBAR_APPS = "apps";
    public static final String NAVBAR_SHARING = "sharing";
    public static final String NAVBAR_SETTINGS = "settings";

    public static final List<String> NAVBAR_DEFAULT_ORDER = Collections.unmodifiableList(
        Arrays.asList(NAVBAR_HOME, NAVBAR_PROFILES, NAVBAR_APPS, NAVBAR_SHARING, NAVBAR_SETTINGS)
    );

    /** Always visible, cannot be hidden by the user. */
    public static final Set<String> NAVBAR_FORCED_VISIBLE = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(NAVBAR_HOME, NAVBAR_SETTINGS))
    );

    public static final String NOTIF_STATUS = "status";
    public static final String NOTIF_SPEED = "speed";
    public static final String NOTIF_TRAFFIC_TOTAL = "traffic_total";
    public static final String NOTIF_TRAFFIC_TX = "traffic_tx";
    public static final String NOTIF_TRAFFIC_RX = "traffic_rx";
    public static final String NOTIF_VK_TURN_STREAMS = "vk_turn_streams";
    public static final String NOTIF_DTLS_HEARTBEAT = "dtls_heartbeat";

    public static final List<String> NOTIF_DEFAULT_ORDER = Collections.unmodifiableList(
        Arrays.asList(
            NOTIF_STATUS,
            NOTIF_SPEED,
            NOTIF_TRAFFIC_TOTAL,
            NOTIF_TRAFFIC_TX,
            NOTIF_TRAFFIC_RX,
            NOTIF_VK_TURN_STREAMS,
            NOTIF_DTLS_HEARTBEAT
        )
    );

    public static final Set<String> NOTIF_DEFAULT_HIDDEN = Collections.unmodifiableSet(
        new LinkedHashSet<>(
            Arrays.asList(NOTIF_TRAFFIC_TX, NOTIF_TRAFFIC_RX, NOTIF_VK_TURN_STREAMS, NOTIF_DTLS_HEARTBEAT)
        )
    );

    private UiPrefs() {}

    public static List<String> getNavbarOrder(Context context) {
        return readOrderedList(prefs(context), AppPrefs.KEY_UI_NAVBAR_ORDER, NAVBAR_DEFAULT_ORDER);
    }

    public static void setNavbarOrder(Context context, List<String> order) {
        writeList(prefs(context), AppPrefs.KEY_UI_NAVBAR_ORDER, order);
    }

    public static Set<String> getNavbarHidden(Context context) {
        Set<String> stored = prefs(context).getStringSet(AppPrefs.KEY_UI_NAVBAR_HIDDEN, null);
        if (stored == null) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>(stored);
        result.removeAll(NAVBAR_FORCED_VISIBLE);
        return result;
    }

    public static void setNavbarHidden(Context context, Set<String> hidden) {
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        if (hidden != null) {
            for (String key : hidden) {
                if (!TextUtils.isEmpty(key) && !NAVBAR_FORCED_VISIBLE.contains(key)) {
                    sanitized.add(key);
                }
            }
        }
        prefs(context).edit().putStringSet(AppPrefs.KEY_UI_NAVBAR_HIDDEN, sanitized).commit();
    }

    public static boolean isNavbarItemHidden(Context context, String key) {
        if (TextUtils.isEmpty(key) || NAVBAR_FORCED_VISIBLE.contains(key)) {
            return false;
        }
        return getNavbarHidden(context).contains(key);
    }

    public static List<String> getNotificationOrder(Context context) {
        return readOrderedList(prefs(context), AppPrefs.KEY_UI_NOTIFICATION_ORDER, NOTIF_DEFAULT_ORDER);
    }

    public static void setNotificationOrder(Context context, List<String> order) {
        writeList(prefs(context), AppPrefs.KEY_UI_NOTIFICATION_ORDER, order);
    }

    public static Set<String> getNotificationHidden(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(AppPrefs.KEY_UI_NOTIFICATION_HIDDEN)) {
            return new LinkedHashSet<>(NOTIF_DEFAULT_HIDDEN);
        }
        Set<String> stored = prefs.getStringSet(AppPrefs.KEY_UI_NOTIFICATION_HIDDEN, null);
        return stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
    }

    public static void setNotificationHidden(Context context, Set<String> hidden) {
        LinkedHashSet<String> sanitized = hidden == null ? new LinkedHashSet<>() : new LinkedHashSet<>(hidden);
        prefs(context).edit().putStringSet(AppPrefs.KEY_UI_NOTIFICATION_HIDDEN, sanitized).commit();
    }

    public static boolean isNotificationItemVisible(Context context, String key) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        return !getNotificationHidden(context).contains(key);
    }

    private static SharedPreferences prefs(Context context) {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    private static List<String> readOrderedList(SharedPreferences prefs, String key, List<String> defaults) {
        String stored = prefs.getString(key, null);
        if (TextUtils.isEmpty(stored)) {
            return new ArrayList<>(defaults);
        }
        ArrayList<String> parsed = new ArrayList<>(defaults.size());
        for (String token : stored.split(",")) {
            String trimmed = token.trim();
            if (!TextUtils.isEmpty(trimmed) && defaults.contains(trimmed) && !parsed.contains(trimmed)) {
                parsed.add(trimmed);
            }
        }
        // Append any defaults that fell out of the stored list (e.g., new entry
        // added in a future build) so they remain visible/known.
        for (String defaultKey : defaults) {
            if (!parsed.contains(defaultKey)) {
                parsed.add(defaultKey);
            }
        }
        return parsed;
    }

    private static void writeList(SharedPreferences prefs, String key, List<String> order) {
        if (order == null || order.isEmpty()) {
            prefs.edit().remove(key).commit();
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (String token : order) {
            if (TextUtils.isEmpty(token)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(token.trim());
        }
        prefs.edit().putString(key, builder.toString()).commit();
    }
}
