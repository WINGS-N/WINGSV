package wings.v.core;

import android.text.TextUtils;

public enum ProxyRuntimeMode {
    VPN("vpn"),
    PROXY("proxy");

    public final String prefValue;

    ProxyRuntimeMode(String prefValue) {
        this.prefValue = prefValue;
    }

    public static ProxyRuntimeMode fromPrefValue(String rawValue) {
        String normalized = rawValue == null ? "" : rawValue.trim();
        if (TextUtils.equals(PROXY.prefValue, normalized)) {
            return PROXY;
        }
        return VPN;
    }

    public boolean isProxyOnly() {
        return this == PROXY;
    }
}
