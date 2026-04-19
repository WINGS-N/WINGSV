package wings.v.core;

import android.text.TextUtils;
import wings.v.proto.WingsvProto;

public enum BackendType {
    VK_TURN_WIREGUARD("vk_turn_wireguard"),
    WIREGUARD("wireguard"),
    AMNEZIAWG("amneziawg"),
    AMNEZIAWG_PLAIN("amneziawg_plain"),
    XRAY("xray");

    public final String prefValue;

    BackendType(String prefValue) {
        this.prefValue = prefValue;
    }

    public static BackendType fromPrefValue(String rawValue) {
        String normalized = trim(rawValue);
        if (TextUtils.equals(WIREGUARD.prefValue, normalized)) {
            return WIREGUARD;
        }
        if (TextUtils.equals(AMNEZIAWG.prefValue, normalized)) {
            return AMNEZIAWG;
        }
        if (TextUtils.equals(AMNEZIAWG_PLAIN.prefValue, normalized)) {
            return AMNEZIAWG_PLAIN;
        }
        if (TextUtils.equals(XRAY.prefValue, normalized)) {
            return XRAY;
        }
        return VK_TURN_WIREGUARD;
    }

    public static BackendType fromProto(WingsvProto.BackendType backendType) {
        if (backendType == WingsvProto.BackendType.BACKEND_TYPE_WIREGUARD) {
            return WIREGUARD;
        }
        if (backendType == WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG) {
            return AMNEZIAWG;
        }
        if (backendType == WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG_PLAIN) {
            return AMNEZIAWG_PLAIN;
        }
        if (backendType == WingsvProto.BackendType.BACKEND_TYPE_XRAY) {
            return XRAY;
        }
        return VK_TURN_WIREGUARD;
    }

    public WingsvProto.BackendType toProto() {
        if (this == WIREGUARD) {
            return WingsvProto.BackendType.BACKEND_TYPE_WIREGUARD;
        }
        if (this == AMNEZIAWG) {
            return WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG;
        }
        if (this == AMNEZIAWG_PLAIN) {
            return WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG_PLAIN;
        }
        if (this == XRAY) {
            return WingsvProto.BackendType.BACKEND_TYPE_XRAY;
        }
        return WingsvProto.BackendType.BACKEND_TYPE_VK_TURN_WIREGUARD;
    }

    public boolean isVkTurnLike() {
        return this != XRAY;
    }

    public boolean usesXrayCore() {
        return this == XRAY;
    }

    public boolean usesTurnProxy() {
        return this == VK_TURN_WIREGUARD || this == AMNEZIAWG;
    }

    public boolean usesWireGuardSettings() {
        return this == VK_TURN_WIREGUARD || this == WIREGUARD;
    }

    public boolean usesAmneziaSettings() {
        return this == AMNEZIAWG || this == AMNEZIAWG_PLAIN;
    }

    public boolean supportsKernelWireGuard() {
        return this == VK_TURN_WIREGUARD || this == WIREGUARD;
    }

    public boolean isPlainBackend() {
        return this == WIREGUARD || this == AMNEZIAWG_PLAIN;
    }

    public BackendType toTurnVariant() {
        if (this == WIREGUARD) {
            return VK_TURN_WIREGUARD;
        }
        if (this == AMNEZIAWG_PLAIN) {
            return AMNEZIAWG;
        }
        return this;
    }

    public BackendType toPlainVariant() {
        if (this == VK_TURN_WIREGUARD) {
            return WIREGUARD;
        }
        if (this == AMNEZIAWG) {
            return AMNEZIAWG_PLAIN;
        }
        return this;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
