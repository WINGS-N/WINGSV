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
        // Same server and same private key, differing only in case/whitespace, still
        // dedup to one profile.
        WireGuardProfile sameIdentity = new WireGuardProfile(
            "b", "B", "priv ", "10.0.0.2/32", "1.1.1.1", 1280, "pubkey", "", "0.0.0.0/0", "host.example:51820 ", "", ""
        );
        assertEquals(a.stableDedupKey(), sameIdentity.stableDedupKey());
        WireGuardProfile otherServer = new WireGuardProfile(
            "c", "C", "priv", "10.0.0.1/32", "1.1.1.1", 1280, "OtherKey", "", "0.0.0.0/0", "Host.Example:51820", "", ""
        );
        assertNotEquals(a.stableDedupKey(), otherServer.stableDedupKey());
    }

    // A different client private key on the SAME server is a distinct identity: it
    // must NOT dedup, or importing a wingsv:// link to an already-known server would
    // reuse the stored profile and drop the imported private key (issue #73).
    @Test
    public void wireGuardDedupKeyDistinguishesPrivateKey() {
        WireGuardProfile a = new WireGuardProfile(
            "a", "A", "privA", "10.0.0.1/32", "1.1.1.1", 1280, "PubKey", "", "0.0.0.0/0", "host.example:51820", "", ""
        );
        WireGuardProfile sameServerOtherKey = new WireGuardProfile(
            "b", "B", "privB", "10.0.0.1/32", "1.1.1.1", 1280, "PubKey", "", "0.0.0.0/0", "host.example:51820", "", ""
        );
        assertNotEquals(a.stableDedupKey(), sameServerOtherKey.stableDedupKey());
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

    // The AWG-transport variant of the bundle: turn.profiles plus the transports as
    // awg.profiles. The profiles-only awg message must merge, not flat-apply.
    @Test
    public void vkTurnBundleMergesAwgTransportsWithoutFlatAmneziaApply() throws Exception {
        WingsvProto.Turn turn = WingsvProto.Turn.newBuilder()
            .setActiveProfileId("id-1")
            .addProfiles(WingsImportParser.toProtoTurnProfile(vkTurn("id-1", VkTurnProfile.TRANSPORT_KIND_AWG, "awg-1")))
            .addProfiles(WingsImportParser.toProtoTurnProfile(vkTurn("id-2", VkTurnProfile.TRANSPORT_KIND_AWG, "awg-2")))
            .build();
        WingsvProto.AmneziaWG awg = WingsvProto.AmneziaWG.newBuilder()
            .addProfiles(WingsvProto.AmneziaProfile.newBuilder().setId("awg-1").setAwgQuickConfig("[Interface]").build())
            .addProfiles(WingsvProto.AmneziaProfile.newBuilder().setId("awg-2").setAwgQuickConfig("[Interface]").build())
            .build();
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(1)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_VK_TURN)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_VK_TURN_PROFILE)
            .setTurn(turn)
            .setAwg(awg)
            .build();

        WingsImportParser.ImportedConfig imported = WingsImportParser.parseProtoConfig(config);

        assertEquals(2, imported.turnProfiles.size());
        assertTrue(imported.hasAwgProfiles);
        assertEquals(2, imported.awgProfiles.size());
        // The profiles-only awg message must not set flat AmneziaWG settings.
        assertFalse(imported.hasAmneziaSettings);
    }

    // A single VK TURN profile link (no profiles list) must STILL flat-apply its
    // embedded transport - the merge guard keys off profilesCount, so it must not
    // over-skip the count == 0 case.
    @Test
    public void singleVkTurnProfileLinkStillFlatAppliesWireGuard() throws Exception {
        WingsvProto.Interface iface = WingsvProto.Interface.newBuilder()
            .setPrivateKey(com.google.protobuf.ByteString.copyFrom(new byte[32]))
            .build();
        WingsvProto.WireGuard wg = WingsvProto.WireGuard.newBuilder().setIface(iface).build();
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(1)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_VK_TURN)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_VK_TURN_PROFILE)
            .setTurn(WingsvProto.Turn.newBuilder().build())
            .setWg(wg)
            .build();

        WingsImportParser.ImportedConfig imported = WingsImportParser.parseProtoConfig(config);

        assertFalse(imported.hasTurnProfiles);
        assertFalse(imported.hasWgProfiles);
        // No profiles list => the wg message is flat-applied.
        assertTrue(imported.hasWireGuardSettings);
        assertNotEquals("", imported.wgPrivateKey);
    }

    // A plain-WireGuard multi-select bundle: wg.profiles only, merged not flat.
    @Test
    public void plainWireGuardBundleMergesProfilesWithoutFlatApply() throws Exception {
        WingsvProto.WireGuard wg = WingsvProto.WireGuard.newBuilder()
            .setActiveProfileId("wg-1")
            .addProfiles(WingsvProto.WireGuardProfile.newBuilder().setId("wg-1").build())
            .addProfiles(WingsvProto.WireGuardProfile.newBuilder().setId("wg-2").build())
            .build();
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(1)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_WIREGUARD)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_VK)
            .setWg(wg)
            .build();

        WingsImportParser.ImportedConfig imported = WingsImportParser.parseProtoConfig(config);

        assertTrue(imported.hasWgProfiles);
        assertEquals(2, imported.wgProfiles.size());
        assertFalse(imported.hasWireGuardSettings);
    }

    // A plain-AmneziaWG multi-select bundle: awg.profiles only, merged not flat.
    @Test
    public void plainAmneziaBundleMergesProfilesWithoutFlatApply() throws Exception {
        WingsvProto.AmneziaWG awg = WingsvProto.AmneziaWG.newBuilder()
            .setActiveProfileId("awg-1")
            .addProfiles(WingsvProto.AmneziaProfile.newBuilder().setId("awg-1").setAwgQuickConfig("[Interface]").build())
            .addProfiles(WingsvProto.AmneziaProfile.newBuilder().setId("awg-2").setAwgQuickConfig("[Interface]").build())
            .build();
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(1)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_AMNEZIAWG_TL)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_AMNEZIAWG)
            .setAwg(awg)
            .build();

        WingsImportParser.ImportedConfig imported = WingsImportParser.parseProtoConfig(config);

        assertTrue(imported.hasAwgProfiles);
        assertEquals(2, imported.awgProfiles.size());
        assertFalse(imported.hasAmneziaSettings);
    }

    // A full backup (CONFIG_TYPE_ALL) must STILL flat-apply the wg message even when
    // it also carries a profiles list - the merge guard has an allSettings escape so
    // restoring a backup keeps both the active transport and the profile list.
    @Test
    public void fullBackupFlatAppliesWireGuardAlongsideProfiles() throws Exception {
        WingsvProto.Interface iface = WingsvProto.Interface.newBuilder()
            .setPrivateKey(com.google.protobuf.ByteString.copyFrom(new byte[32]))
            .build();
        WingsvProto.WireGuard wg = WingsvProto.WireGuard.newBuilder()
            .setIface(iface)
            .addProfiles(WingsvProto.WireGuardProfile.newBuilder().setId("wg-1").build())
            .build();
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(1)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_WIREGUARD)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_ALL)
            .setWg(wg)
            .build();

        WingsImportParser.ImportedConfig imported = WingsImportParser.parseProtoConfig(config);

        assertTrue(imported.hasWgProfiles);
        assertEquals(1, imported.wgProfiles.size());
        // allSettings escape: the flat transport is restored too.
        assertTrue(imported.hasWireGuardSettings);
        assertNotEquals("", imported.wgPrivateKey);
    }

    // Why a bundle is required: pasted text yields only the FIRST wingsv:// link, so
    // newline-joined single-profile links would drop every profile after the first.
    @Test
    public void extractLinkReturnsOnlyTheFirstOfSeveral() {
        String blob = "wingsv://AAAA\nwingsv://BBBB\nwingsv://CCCC";
        assertEquals("wingsv://AAAA", WingsImportParser.extractLink(blob));
    }

    // The WireGuard-transport variant of the turn-profile proto round-trip (the
    // existing case covers AmneziaWG).
    @Test
    public void wireGuardTransportTurnProfileSurvivesProtoRoundTrip() throws Exception {
        VkTurnProfile original = vkTurn("id-wg", VkTurnProfile.TRANSPORT_KIND_WG, "wg-9");
        WingsvProto.TurnProfile proto = WingsImportParser.toProtoTurnProfile(original);
        assertEquals(VkTurnProfile.TRANSPORT_KIND_WG, proto.getTransportKind());
        assertEquals("wg-9", proto.getTransportProfileId());

        VkTurnProfile back = WingsImportParser.fromProtoTurnProfile(proto);
        assertEquals(VkTurnProfile.TRANSPORT_KIND_WG, back.transportKind);
        assertEquals("wg-9", back.transportProfileId);
        assertFalse(back.usesAmneziaTransport());
    }

    // The bypass captcha default rides inside the embedded Turn of a shared profile,
    // so it must survive the proto round-trip (a lost value would silently fall back
    // to the app default on the importing device).
    @Test
    public void captchaAutoSolverBypassSurvivesProtoRoundTrip() throws Exception {
        VkTurnProfile original = vkTurn("id-c", VkTurnProfile.TRANSPORT_KIND_WG, "wg-1");
        assertEquals("bypass", original.captchaAutoSolver);
        VkTurnProfile back = WingsImportParser.fromProtoTurnProfile(
            WingsImportParser.toProtoTurnProfile(original)
        );
        assertEquals("bypass", back.captchaAutoSolver);
    }

    private static VkTurnProfile vkTurn(String id, String transportKind, String transportProfileId) {
        return new VkTurnProfile(
            id, id, transportKind, transportProfileId, "1.1.1.1:443",
            2, 1, true, false, false,
            "bypass", "", "", "", "",
            "proxy", true, "", "", "", false,
            "", "", "", "", ""
        );
    }
}
