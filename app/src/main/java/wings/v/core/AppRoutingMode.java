package wings.v.core;

import android.text.TextUtils;
import androidx.annotation.Nullable;

public enum AppRoutingMode {
    OFF("off"),
    BYPASS("bypass"),
    WHITELIST("whitelist");

    public final String prefValue;

    AppRoutingMode(String prefValue) {
        this.prefValue = prefValue;
    }

    public static AppRoutingMode fromPrefValue(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return BYPASS;
        }
        for (AppRoutingMode mode : values()) {
            if (mode.prefValue.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return BYPASS;
    }
}
