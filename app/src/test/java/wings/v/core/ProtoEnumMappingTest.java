package wings.v.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import wings.v.proto.WingsvProto;

/**
 * Pure enum proto-mapping round-trips. No Android runtime needed (the mapped
 * methods are plain enum switches), so these run on plain JUnit.
 */
public class ProtoEnumMappingTest {

    @Test
    public void backendTypeEmitsCanonicalMultiProfileValues() {
        // VK TURN (WG and AWG variants) collapse onto the new BACKEND_TYPE_VK_TURN;
        // the WG/AWG choice travels in Turn.tunnel_mode.
        assertEquals(WingsvProto.BackendType.BACKEND_TYPE_VK_TURN, BackendType.VK_TURN_WIREGUARD.toProto());
        assertEquals(WingsvProto.BackendType.BACKEND_TYPE_VK_TURN, BackendType.AMNEZIAWG.toProto());
        assertEquals(WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG_TL, BackendType.AMNEZIAWG_PLAIN.toProto());
        assertEquals(WingsvProto.BackendType.BACKEND_TYPE_WIREGUARD, BackendType.WIREGUARD.toProto());
        assertEquals(WingsvProto.BackendType.BACKEND_TYPE_XRAY, BackendType.XRAY.toProto());
        assertEquals(WingsvProto.BackendType.BACKEND_TYPE_WB_STREAM, BackendType.WB_STREAM.toProto());
        assertEquals(WingsvProto.BackendType.BACKEND_TYPE_WB_STREAM, BackendType.WB_STREAM_AMNEZIAWG.toProto());
    }

    @Test
    public void backendTypeFromProtoHandlesNewAndLegacyAliases() {
        // New canonical.
        assertEquals(BackendType.VK_TURN_WIREGUARD, BackendType.fromProto(WingsvProto.BackendType.BACKEND_TYPE_VK_TURN));
        assertEquals(
            BackendType.AMNEZIAWG_PLAIN,
            BackendType.fromProto(WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG_TL)
        );
        // Deprecated legacy aliases still parse.
        assertEquals(
            BackendType.VK_TURN_WIREGUARD,
            BackendType.fromProto(WingsvProto.BackendType.BACKEND_TYPE_VK_TURN_WIREGUARD)
        );
        assertEquals(BackendType.AMNEZIAWG, BackendType.fromProto(WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG));
        assertEquals(
            BackendType.AMNEZIAWG_PLAIN,
            BackendType.fromProto(WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG_PLAIN)
        );
        // Others + default.
        assertEquals(BackendType.WIREGUARD, BackendType.fromProto(WingsvProto.BackendType.BACKEND_TYPE_WIREGUARD));
        assertEquals(BackendType.XRAY, BackendType.fromProto(WingsvProto.BackendType.BACKEND_TYPE_XRAY));
        assertEquals(BackendType.WB_STREAM, BackendType.fromProto(WingsvProto.BackendType.BACKEND_TYPE_WB_STREAM));
        assertEquals(
            BackendType.VK_TURN_WIREGUARD,
            BackendType.fromProto(WingsvProto.BackendType.BACKEND_TYPE_UNSPECIFIED)
        );
    }

    @Test
    public void tunnelModeRoundTrips() {
        assertEquals(WingsvProto.TunnelMode.TUNNEL_MODE_AMNEZIAWG, TunnelMode.AMNEZIAWG.toProto());
        assertEquals(WingsvProto.TunnelMode.TUNNEL_MODE_WIREGUARD, TunnelMode.WIREGUARD.toProto());
        assertEquals(TunnelMode.AMNEZIAWG, TunnelMode.fromProto(WingsvProto.TunnelMode.TUNNEL_MODE_AMNEZIAWG));
        assertEquals(TunnelMode.WIREGUARD, TunnelMode.fromProto(WingsvProto.TunnelMode.TUNNEL_MODE_WIREGUARD));
        // Unspecified is treated as WireGuard (pre-field default).
        assertEquals(TunnelMode.WIREGUARD, TunnelMode.fromProto(WingsvProto.TunnelMode.TUNNEL_MODE_UNSPECIFIED));
    }
}
