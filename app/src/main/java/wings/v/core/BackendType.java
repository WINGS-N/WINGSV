package wings.v.core;

import android.text.TextUtils;

import wings.v.proto.WingsvProto;

public enum BackendType {
    VK_TURN_WIREGUARD("vk_turn_wireguard"),
    AMNEZIAWG("amneziawg"),
    XRAY("xray");

    public final String prefValue;

    BackendType(String prefValue) {
        this.prefValue = prefValue;
    }

    public static BackendType fromPrefValue(String rawValue) {
        if (TextUtils.equals(AMNEZIAWG.prefValue, trim(rawValue))) {
            return AMNEZIAWG;
        }
        if (TextUtils.equals(XRAY.prefValue, trim(rawValue))) {
            return XRAY;
        }
        return VK_TURN_WIREGUARD;
    }

    public static BackendType fromProto(WingsvProto.BackendType backendType) {
        if (backendType == WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG) {
            return AMNEZIAWG;
        }
        if (backendType == WingsvProto.BackendType.BACKEND_TYPE_XRAY) {
            return XRAY;
        }
        return VK_TURN_WIREGUARD;
    }

    public WingsvProto.BackendType toProto() {
        if (this == AMNEZIAWG) {
            return WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG;
        }
        if (this == XRAY) {
            return WingsvProto.BackendType.BACKEND_TYPE_XRAY;
        }
        return WingsvProto.BackendType.BACKEND_TYPE_VK_TURN_WIREGUARD;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
