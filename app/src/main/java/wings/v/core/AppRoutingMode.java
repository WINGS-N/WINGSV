package wings.v.core;

import android.text.TextUtils;
import androidx.annotation.Nullable;

public enum AppRoutingMode {
    OFF("off"),
    // BYPASS excludes the selected apps at the VpnService layer
    // (addDisallowedApplication). XBYPASS keeps every app inside the tunnel and
    // diverts the selected ones to direct at the xray-core gVisor level (closes
    // the unknown-UID / SO_BINDTODEVICE leak). Both share the bypass app list.
    BYPASS("bypass"),
    XBYPASS("xbypass"),
    // WHITELIST tunnels only the selected apps at the VpnService layer
    // (addAllowedApplication), the rest go direct. XWHITELIST is the inverse of
    // XBYPASS: it keeps every app inside the tunnel and diverts the NON-selected
    // ones to direct at the xray-core gVisor level (closes the same leak for the
    // non-tunneled apps). Both share the whitelist app list.
    WHITELIST("whitelist"),
    XWHITELIST("xwhitelist");

    public final String prefValue;

    AppRoutingMode(String prefValue) {
        this.prefValue = prefValue;
    }

    // True for the bypass family (selected apps go direct); both share the bypass
    // app list and differ only in mechanism (framework disallow vs gVisor divert).
    public boolean isBypassFamily() {
        return this == BYPASS || this == XBYPASS;
    }

    // True for the whitelist family (only selected apps are tunneled, the rest
    // go direct); both share the whitelist app list and differ only in mechanism
    // (framework addAllowedApplication vs gVisor divert of the non-selected).
    public boolean isWhitelistFamily() {
        return this == WHITELIST || this == XWHITELIST;
    }

    // True when the selected apps are diverted at the xray-core gVisor level
    // (every app stays inside the tunnel) rather than filtered by the VpnService
    // framework. These modes need the xray TUN inbound's uid filter.
    public boolean isGvisorDivert() {
        return this == XBYPASS || this == XWHITELIST;
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
