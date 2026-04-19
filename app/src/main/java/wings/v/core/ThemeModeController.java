package wings.v.core;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDelegate;
import wings.v.R;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor" })
public final class ThemeModeController {

    private ThemeModeController() {}

    public static void apply(Context context) {
        if (context == null) {
            return;
        }
        applyPreferenceValue(AppPrefs.getThemeMode(context));
    }

    public static void applyPreferenceValue(@Nullable String value) {
        int targetNightMode = resolveNightMode(value);
        if (AppCompatDelegate.getDefaultNightMode() == targetNightMode) {
            return;
        }
        AppCompatDelegate.setDefaultNightMode(targetNightMode);
    }

    public static int resolveNightMode(@Nullable String value) {
        if (AppPrefs.THEME_MODE_DARK.equals(value)) {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
        if (AppPrefs.THEME_MODE_LIGHT.equals(value)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    @StringRes
    public static int resolveLabelRes(@Nullable String value) {
        if (AppPrefs.THEME_MODE_DARK.equals(value)) {
            return R.string.theme_mode_dark;
        }
        if (AppPrefs.THEME_MODE_LIGHT.equals(value)) {
            return R.string.theme_mode_light;
        }
        return R.string.theme_mode_system;
    }
}
