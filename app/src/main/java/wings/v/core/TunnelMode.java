package wings.v.core;

import android.text.TextUtils;
import wings.v.proto.WingsvProto;

/**
 * Под-режим туннеля внутри backend'а, который может пускать поверх своего
 * userspace-прокси разные имплементации (VK TURN / WB Stream). Для top-level
 * backend'ов (plain WireGuard / AmneziaWG / Xray) поле игнорируется.
 *
 * <p>Хранится в SharedPreferences как строка ("wireguard" / "amneziawg") и в
 * proto как {@link WingsvProto.TunnelMode}. {@link #UNSPECIFIED} нигде в UI не
 * используется — это только защита от пустого input'а; на чтении трактуется
 * как WIREGUARD (поведение до введения поля).
 */
public enum TunnelMode {
    WIREGUARD("wireguard"),
    AMNEZIAWG("amneziawg");

    public final String prefValue;

    TunnelMode(String prefValue) {
        this.prefValue = prefValue;
    }

    public static final TunnelMode UNSPECIFIED = WIREGUARD;

    public static TunnelMode fromPrefValue(String rawValue) {
        String normalized = trim(rawValue);
        if (TextUtils.equals(AMNEZIAWG.prefValue, normalized)) {
            return AMNEZIAWG;
        }
        return WIREGUARD;
    }

    public static TunnelMode fromProto(WingsvProto.TunnelMode mode) {
        if (mode == WingsvProto.TunnelMode.TUNNEL_MODE_AMNEZIAWG) {
            return AMNEZIAWG;
        }
        return WIREGUARD;
    }

    public WingsvProto.TunnelMode toProto() {
        if (this == AMNEZIAWG) {
            return WingsvProto.TunnelMode.TUNNEL_MODE_AMNEZIAWG;
        }
        return WingsvProto.TunnelMode.TUNNEL_MODE_WIREGUARD;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
