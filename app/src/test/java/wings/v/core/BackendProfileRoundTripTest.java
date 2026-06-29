package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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
}
