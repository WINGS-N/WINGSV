package wings.v.core;

import android.text.TextUtils;
import wings.v.proto.WingsvProto;

public enum BackendType {
    VK_TURN_WIREGUARD("vk_turn_wireguard"),
    WIREGUARD("wireguard"),
    AMNEZIAWG("amneziawg"),
    AMNEZIAWG_PLAIN("amneziawg_plain"),
    XRAY("xray"),
    WB_STREAM("wb_stream"),
    WB_STREAM_AMNEZIAWG("wb_stream_amneziawg");

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
        if (TextUtils.equals(WB_STREAM_AMNEZIAWG.prefValue, normalized)) {
            return WB_STREAM_AMNEZIAWG;
        }
        if (TextUtils.equals(WB_STREAM.prefValue, normalized)) {
            return WB_STREAM;
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
        if (backendType == WingsvProto.BackendType.BACKEND_TYPE_WB_STREAM) {
            return WB_STREAM;
        }
        return VK_TURN_WIREGUARD;
    }

    /**
     * Расширенная версия {@link #fromProto(WingsvProto.BackendType)}, учитывающая
     * подбэкенд внутри WB Stream/VK TURN-обёрток. Для backend=WB_STREAM с
     * tunnel_mode=AMNEZIAWG возвращает {@link #WB_STREAM_AMNEZIAWG}; остальные
     * случаи делегирует базовому {@link #fromProto}.
     */
    public static BackendType fromProto(WingsvProto.BackendType backendType, WingsvProto.TunnelMode wbStreamMode) {
        if (
            backendType == WingsvProto.BackendType.BACKEND_TYPE_WB_STREAM &&
            wbStreamMode == WingsvProto.TunnelMode.TUNNEL_MODE_AMNEZIAWG
        ) {
            return WB_STREAM_AMNEZIAWG;
        }
        return fromProto(backendType);
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
        if (this == WB_STREAM || this == WB_STREAM_AMNEZIAWG) {
            return WingsvProto.BackendType.BACKEND_TYPE_WB_STREAM;
        }
        return WingsvProto.BackendType.BACKEND_TYPE_VK_TURN_WIREGUARD;
    }

    /** TunnelMode для WbStream/Turn-обёрток. Возвращает UNSPECIFIED для backend'ов без подтипа. */
    public WingsvProto.TunnelMode toWbStreamTunnelModeProto() {
        if (this == WB_STREAM_AMNEZIAWG) {
            return WingsvProto.TunnelMode.TUNNEL_MODE_AMNEZIAWG;
        }
        if (this == WB_STREAM) {
            return WingsvProto.TunnelMode.TUNNEL_MODE_WIREGUARD;
        }
        return WingsvProto.TunnelMode.TUNNEL_MODE_UNSPECIFIED;
    }

    public WingsvProto.TunnelMode toVkTurnTunnelModeProto() {
        if (this == AMNEZIAWG) {
            return WingsvProto.TunnelMode.TUNNEL_MODE_AMNEZIAWG;
        }
        if (this == VK_TURN_WIREGUARD) {
            return WingsvProto.TunnelMode.TUNNEL_MODE_WIREGUARD;
        }
        return WingsvProto.TunnelMode.TUNNEL_MODE_UNSPECIFIED;
    }

    public boolean isVkTurnLike() {
        return this != XRAY && this != WB_STREAM && this != WB_STREAM_AMNEZIAWG;
    }

    public boolean usesXrayCore() {
        return this == XRAY;
    }

    // True for backends that run over the userspace xray-core WireGuard path when
    // root mode is off (gVisor TUN -> synthetic WG outbound). Xray DNS/routing/
    // sniffing settings apply there too, even though it is not a plain Xray backend.
    public boolean usesUserspaceXrayWireGuard() {
        return this == VK_TURN_WIREGUARD || this == WIREGUARD;
    }

    public boolean usesTurnProxy() {
        return this == VK_TURN_WIREGUARD || this == AMNEZIAWG || this == WB_STREAM || this == WB_STREAM_AMNEZIAWG;
    }

    public boolean usesWireGuardSettings() {
        return this == VK_TURN_WIREGUARD || this == WIREGUARD || this == WB_STREAM;
    }

    public boolean usesAmneziaSettings() {
        return this == AMNEZIAWG || this == AMNEZIAWG_PLAIN || this == WB_STREAM_AMNEZIAWG;
    }

    public boolean supportsKernelWireGuard() {
        return this == VK_TURN_WIREGUARD || this == WIREGUARD || this == WB_STREAM;
    }

    public boolean isPlainBackend() {
        return this == WIREGUARD || this == AMNEZIAWG_PLAIN;
    }

    /** True для всех вариантов «WB Stream + любой туннель». */
    public boolean isWbStreamBackend() {
        return this == WB_STREAM || this == WB_STREAM_AMNEZIAWG;
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

    /**
     * Top-level группировка для UI dropdown «Активный backend». Возвращает один из
     * 5 ярлыков: {@code vk_turn}, {@code wb_stream}, {@code wireguard},
     * {@code amneziawg}, {@code xray}. AMNEZIAWG (= AWG-over-VK-TURN) попадает в
     * {@code vk_turn} (подтип отдельным prefом), WB_STREAM_AMNEZIAWG — в
     * {@code wb_stream}.
     */
    public String topLevelGroup() {
        if (this == VK_TURN_WIREGUARD || this == AMNEZIAWG) {
            return "vk_turn";
        }
        if (this == WB_STREAM || this == WB_STREAM_AMNEZIAWG) {
            return "wb_stream";
        }
        if (this == WIREGUARD) {
            return "wireguard";
        }
        if (this == AMNEZIAWG_PLAIN) {
            return "amneziawg";
        }
        return "xray";
    }

    /**
     * Преобразует пару (top-level группа, sub-backend) обратно в внутренний enum.
     * subBackend учитывается только для top-level "vk_turn" и "wb_stream".
     */
    public static BackendType fromTopLevelAndSub(String topLevel, TunnelMode subBackend) {
        String top = trim(topLevel);
        TunnelMode sub = subBackend == null ? TunnelMode.WIREGUARD : subBackend;
        switch (top) {
            case "vk_turn":
                return sub == TunnelMode.AMNEZIAWG ? AMNEZIAWG : VK_TURN_WIREGUARD;
            case "wb_stream":
                return sub == TunnelMode.AMNEZIAWG ? WB_STREAM_AMNEZIAWG : WB_STREAM;
            case "wireguard":
                return WIREGUARD;
            case "amneziawg":
                return AMNEZIAWG_PLAIN;
            case "xray":
                return XRAY;
            default:
                return VK_TURN_WIREGUARD;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
