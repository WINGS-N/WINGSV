package wings.v.core;

import android.text.TextUtils;
import androidx.annotation.Nullable;

public enum AppRoutingMode {
    OFF("off"),
    // BYPASS excludes the selected apps at the VpnService layer
    // (addDisallowedApplication). XBYPASS keeps every app inside the tunnel and
    // diverts the selected ones to direct at the xray-core gVisor level (closes
    // the unknown-UID / SO_BINDTODEVICE leak). Both share the same app list.
    BYPASS("bypass"),
    XBYPASS("xbypass"),
    WHITELIST("whitelist");

    public final String prefValue;

    AppRoutingMode(String prefValue) {
        this.prefValue = prefValue;
    }

    // True for the bypass family (selected apps go direct); both share the bypass
    // app list and differ only in mechanism (framework disallow vs gVisor divert).
    public boolean isBypassFamily() {
        return this == BYPASS || this == XBYPASS;
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
