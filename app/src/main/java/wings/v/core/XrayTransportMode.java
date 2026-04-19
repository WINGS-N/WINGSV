package wings.v.core;

import android.text.TextUtils;

public enum XrayTransportMode {
    DIRECT("direct"),
    VK_TURN_TCP("vk_turn_tcp");

    public final String prefValue;

    XrayTransportMode(String prefValue) {
        this.prefValue = prefValue;
    }

    public static XrayTransportMode fromPrefValue(String rawValue) {
        String normalized = rawValue == null ? "" : rawValue.trim();
        if (TextUtils.equals(VK_TURN_TCP.prefValue, normalized)) {
            return VK_TURN_TCP;
        }
        return DIRECT;
    }

    public boolean usesTurnProxy() {
        return this == VK_TURN_TCP;
    }

    public boolean usesExternalTcpRelay() {
        return usesTurnProxy();
    }
}
