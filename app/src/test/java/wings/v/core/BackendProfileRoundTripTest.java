package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import wings.v.proto.WingsvProto;

/**
 * Profile dedup/JSON round-trips and the VK TURN proto round-trip. Needs
 * Robolectric because the profile constructors use TextUtils and JSONObject;
 * Application.class avoids WingsApplication.onCreate (MMKV native).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class BackendProfileRoundTripTest {

    @Test
    public void wireGuardDedupKeyIgnoresCaseAndWhitespace() {
        WireGuardProfile a = new WireGuardProfile(
            "a", "A", "priv", "10.0.0.1/32", "1.1.1.1", 1280, "PubKey", "", "0.0.0.0/0", "Host.Example:51820", "", ""
        );
        WireGuardProfile sameServer = new WireGuardProfile(
            "b", "B", "priv2", "10.0.0.2/32", "1.1.1.1", 1280, "pubkey", "", "0.0.0.0/0", "host.example:51820 ", "", ""
        );
        assertEquals(a.stableDedupKey(), sameServer.stableDedupKey());
        WireGuardProfile otherServer = new WireGuardProfile(
            "c", "C", "priv", "10.0.0.1/32", "1.1.1.1", 1280, "OtherKey", "", "0.0.0.0/0", "Host.Example:51820", "", ""
        );
        assertNotEquals(a.stableDedupKey(), otherServer.stableDedupKey());
    }

    @Test
    public void wireGuardJsonRoundTrip() throws Exception {
        WireGuardProfile profile = new WireGuardProfile(
            "id-9", "Home", "priv", "10.0.0.1/32", "1.1.1.1, 8.8.8.8", 1420, "PubKey", "PSK", "0.0.0.0/0, ::/0",
            "host:51820", "sub-1", "Sub"
        );
        WireGuardProfile back = WireGuardProfile.fromJson(profile.toJson());
        assertEquals(profile.id, back.id);
        assertEquals(profile.title, back.title);
        assertEquals(profile.publicKey, back.publicKey);
        assertEquals(profile.endpoint, back.endpoint);
        assertEquals(profile.subscriptionId, back.subscriptionId);
        assertEquals(profile.stableDedupKey(), back.stableDedupKey());
    }

    @Test
    public void vkTurnProfileSurvivesProtoRoundTrip() throws Exception {
        VkTurnProfile original = new VkTurnProfile(
            "id-1", "My VK", VkTurnProfile.TRANSPORT_KIND_AWG, "transport-9", "1.2.3.4:443",
            4, 2, true, false, true,
            "solver", "account", "", "doh", "1.1.1.1",
            "proxy", true, "required", "", "", false,
            "", "turnhost", "443", "sub-1", "Sub One"
        );
        WingsvProto.TurnProfile proto = WingsImportParser.toProtoTurnProfile(original);
        // The two settings that previously could not survive a panel round-trip.
        assertEquals("account", proto.getVkAuthMode());
        assertEquals("doh", proto.getDnsMode());

        VkTurnProfile back = WingsImportParser.fromProtoTurnProfile(proto);
        assertEquals(original.vkAuthMode, back.vkAuthMode);
        assertEquals(original.dnsMode, back.dnsMode);
        assertEquals(original.transportKind, back.transportKind);
        assertEquals(original.transportProfileId, back.transportProfileId);
        assertEquals(original.vkTurnEndpoint, back.vkTurnEndpoint);
        assertEquals(original.threads, back.threads);
        assertEquals(original.useUdp, back.useUdp);
        assertEquals(original.subscriptionId, back.subscriptionId);
        assertEquals(original.subscriptionTitle, back.subscriptionTitle);
    }

    // A multi-select VK TURN bundle carries turn.profiles plus the transports as
    // wg.profiles. On import the profiles must be merged, and the profiles-only wg
    // message must NOT be flat-applied (which would clobber the active transport).
    @Test
    public void vkTurnBundleMergesProfilesWithoutFlatWireGuardApply() throws Exception {
        VkTurnProfile one = new VkTurnProfile(
            "id-1", "One", VkTurnProfile.TRANSPORT_KIND_WG, "wg-1", "1.1.1.1:443",
            2, 1, true, false, false,
            "bypass", "", "", "", "",
            "proxy", true, "", "", "", false,
            "", "", "", "", ""
        );
        VkTurnProfile two = new VkTurnProfile(
            "id-2", "Two", VkTurnProfile.TRANSPORT_KIND_WG, "wg-2", "2.2.2.2:443",
            2, 1, true, false, false,
            "bypass", "", "", "", "",
            "proxy", true, "", "", "", false,
            "", "", "", "", ""
        );
        WingsvProto.Turn turn = WingsvProto.Turn.newBuilder()
            .setActiveProfileId("id-1")
            .addProfiles(WingsImportParser.toProtoTurnProfile(one))
            .addProfiles(WingsImportParser.toProtoTurnProfile(two))
            .build();
        WingsvProto.WireGuard wg = WingsvProto.WireGuard.newBuilder()
            .addProfiles(WingsvProto.WireGuardProfile.newBuilder().setId("wg-1").build())
            .addProfiles(WingsvProto.WireGuardProfile.newBuilder().setId("wg-2").build())
            .build();
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(1)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_VK_TURN)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_VK_TURN_PROFILE)
            .setTurn(turn)
            .setWg(wg)
            .build();

        WingsImportParser.ImportedConfig imported = WingsImportParser.parseProtoConfig(config);

        assertTrue(imported.hasTurnProfiles);
        assertEquals(2, imported.turnProfiles.size());
        assertTrue(imported.hasWgProfiles);
        assertEquals(2, imported.wgProfiles.size());
        // The profiles-only wg message must not trigger the flat WireGuard apply.
        assertFalse(imported.hasWireGuardSettings);
    }
}
