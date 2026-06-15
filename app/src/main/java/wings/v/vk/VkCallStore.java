package wings.v.vk;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor" })
public final class VkCallStore {

    public static final String KEY_AUTO_CREATE = "pref_vk_calls_auto_create";
    public static final String KEY_ACCOUNT = "pref_vk_calls_account";
    public static final String KEY_LAST_LINK = "pref_vk_calls_last_link";
    private static final String KEY_LAST_CALL_ID = "pref_vk_calls_last_call_id";

    private VkCallStore() {}

    public static boolean isAutoCreateEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_CREATE, VkIdManager.isConfigured());
    }

    public static String getLastLink(Context context) {
        return prefs(context).getString(KEY_LAST_LINK, "");
    }

    public static void saveCall(Context context, String callId, String joinLink) {
        prefs(context).edit().putString(KEY_LAST_CALL_ID, callId).putString(KEY_LAST_LINK, joinLink).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }
}
