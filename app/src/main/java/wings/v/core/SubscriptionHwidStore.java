package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import wings.v.R;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.ConfusingTernary",
        "PMD.DataClass",
    }
)
public final class SubscriptionHwidStore {

    private SubscriptionHwidStore() {}

    public static SettingsModel getSettings(@Nullable Context context) {
        SettingsModel settings = new SettingsModel();
        if (context == null) {
            return settings;
        }
        SharedPreferences preferences = prefs(context);
        settings.enabled = preferences.getBoolean(AppPrefs.KEY_SUBSCRIPTION_HWID_ENABLED, true);
        settings.manualValues = preferences.getBoolean(AppPrefs.KEY_SUBSCRIPTION_HWID_MANUAL_ENABLED, false);
        settings.hwid = trim(preferences.getString(AppPrefs.KEY_SUBSCRIPTION_HWID_VALUE, ""));
        settings.deviceOs = trim(preferences.getString(AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_OS, ""));
        settings.verOs = trim(preferences.getString(AppPrefs.KEY_SUBSCRIPTION_HWID_VER_OS, ""));
        settings.deviceModel = trim(preferences.getString(AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_MODEL, ""));
        return settings;
    }

    @NonNull
    public static Payload getAutomaticPayload(@Nullable Context context) {
        Payload payload = new Payload();
        if (context == null) {
            return payload;
        }
        String androidId = "";
        try {
            androidId = trim(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
        } catch (Exception ignored) {}
        payload.hwid = !TextUtils.isEmpty(androidId) ? androidId : trim(Build.FINGERPRINT);
        payload.deviceOs = "Android";
        payload.verOs = !TextUtils.isEmpty(trim(Build.VERSION.RELEASE))
            ? trim(Build.VERSION.RELEASE)
            : String.valueOf(Build.VERSION.SDK_INT);
        payload.deviceModel = !TextUtils.isEmpty(trim(Build.MODEL)) ? trim(Build.MODEL) : trim(Build.DEVICE);
        return payload;
    }

    @Nullable
    public static Payload getEffectivePayload(@Nullable Context context) {
        if (context == null) {
            return null;
        }
        SettingsModel settings = getSettings(context);
        if (!settings.enabled) {
            return null;
        }
        Payload automatic = getAutomaticPayload(context);
        Payload effective = new Payload();
        effective.hwid = resolveValue(settings.manualValues, settings.hwid, automatic.hwid);
        effective.deviceOs = resolveValue(settings.manualValues, settings.deviceOs, automatic.deviceOs);
        effective.verOs = resolveValue(settings.manualValues, settings.verOs, automatic.verOs);
        effective.deviceModel = resolveValue(settings.manualValues, settings.deviceModel, automatic.deviceModel);
        return effective;
    }

    @NonNull
    public static String getSubscriptionsRowSummary(@Nullable Context context) {
        if (context == null) {
            return "";
        }
        SettingsModel settings = getSettings(context);
        if (!settings.enabled) {
            return context.getString(R.string.subscription_hwid_row_summary_off);
        }
        return context.getString(
            settings.manualValues
                ? R.string.subscription_hwid_row_summary_manual
                : R.string.subscription_hwid_row_summary_auto
        );
    }

    @NonNull
    public static String getDisplayedValue(@Nullable Context context, @NonNull String preferenceKey) {
        SettingsModel settings = getSettings(context);
        Payload automatic = getAutomaticPayload(context);
        if (AppPrefs.KEY_SUBSCRIPTION_HWID_VALUE.equals(preferenceKey)) {
            return resolveValue(settings.manualValues, settings.hwid, automatic.hwid);
        }
        if (AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_OS.equals(preferenceKey)) {
            return resolveValue(settings.manualValues, settings.deviceOs, automatic.deviceOs);
        }
        if (AppPrefs.KEY_SUBSCRIPTION_HWID_VER_OS.equals(preferenceKey)) {
            return resolveValue(settings.manualValues, settings.verOs, automatic.verOs);
        }
        if (AppPrefs.KEY_SUBSCRIPTION_HWID_DEVICE_MODEL.equals(preferenceKey)) {
            return resolveValue(settings.manualValues, settings.deviceModel, automatic.deviceModel);
        }
        return "";
    }

    private static String resolveValue(boolean manualValues, String manualValue, String autoValue) {
        if (manualValues && !TextUtils.isEmpty(trim(manualValue))) {
            return trim(manualValue);
        }
        return trim(autoValue);
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    @NonNull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    public static final class SettingsModel {

        public boolean enabled;
        public boolean manualValues;
        public String hwid = "";
        public String deviceOs = "";
        public String verOs = "";
        public String deviceModel = "";
    }

    public static final class Payload {

        public String hwid = "";
        public String deviceOs = "";
        public String verOs = "";
        public String deviceModel = "";
    }
}
