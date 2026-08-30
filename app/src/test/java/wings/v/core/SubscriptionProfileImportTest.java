package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import wings.v.proto.WingsvProto;

/**
 * A subscription that carries profile lists is what the panel and the federation
 * head both issue. The flat single-profile path cannot represent a provisioned
 * VK TURN profile at all, so a body in that shape used to come back empty and the
 * free user got nothing. Robolectric with Application.class keeps
 * WingsApplication.onCreate (and MMKV native) out of it.
 *
 * Only the profile-list path is covered here. The flat single-profile path reads
 * MMKV-backed prefs and dies with UnsatisfiedLinkError on a host JVM, so there is
 * nothing to be gained by adding a case for it - the new branch deliberately
 * touches no prefs, which is why it is testable at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class SubscriptionProfileImportTest {

    private static VkTurnProfile turnProfile(String id, String transportId, boolean provisioned) {
        return new VkTurnProfile(
            id, id.toUpperCase(java.util.Locale.ROOT), VkTurnProfile.TRANSPORT_KIND_WG, transportId,
            "1.1.1.1:443", 2, 1, true, false, false,
            "", "", "", "", "",
            "", false, "preferred", "srtp-aes-gcm", "", true,
            "127.0.0.1:9000", "", "", "sub-1", "Subscription",
            provisioned, provisioned ? "client-42" : "", provisioned ? "dG9rZW4=" : ""
        );
    }

    private static String subscriptionBody(WingsvProto.Config config) {
        return WingsImportParser.encodeConfig(config);
    }

    private static WingsvProto.Config.Builder baseConfig() {
        return WingsvProto.Config.newBuilder()
            .setVer(1)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_VK_TURN)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_VK);
    }

    // The whole point: a managed profile has to survive the trip through a
    // subscription body, provisioning fields and all
    @Test
    public void provisionedProfileSurvivesASubscription() throws Exception {
        WingsvProto.Config config = baseConfig()
            .setTurn(
                WingsvProto.Turn.newBuilder()
                    .addProfiles(WingsImportParser.toProtoTurnProfile(turnProfile("free-1", "", true)))
            )
            .build();

        List<WingsImportParser.ImportedBackendProfile> got =
            WingsImportParser.extractBackendProfilesFromSubscriptionBody(
                RuntimeEnvironment.getApplication(), subscriptionBody(config)
            );

        assertEquals(1, got.size());
        WingsImportParser.ImportedBackendProfile entry = got.get(0);
        assertEquals(WingsImportParser.ImportedBackendProfile.Kind.VK_TURN, entry.kind);
        assertNotNull(entry.vkTurnProfile);
        assertTrue("the profile lost its provisioned flag", entry.vkTurnProfile.wgProvisioned);
        assertEquals("client-42", entry.vkTurnProfile.provisionClientId);
        assertEquals("dG9rZW4=", entry.vkTurnProfile.provisionToken);
    }

    // A VK TURN profile references its transport by id rather than embedding it,
    // so the referenced one has to travel with it
    @Test
    public void aProfileKeepsTheTransportItReferences() throws Exception {
        WingsvProto.Config config = baseConfig()
            .setTurn(
                WingsvProto.Turn.newBuilder()
                    .addProfiles(WingsImportParser.toProtoTurnProfile(turnProfile("paid-1", "wg-1", false)))
            )
            .setWg(
                WingsvProto.WireGuard.newBuilder()
                    .addProfiles(WingsvProto.WireGuardProfile.newBuilder().setId("wg-1").setTitle("Transport"))
            )
            .build();

        List<WingsImportParser.ImportedBackendProfile> got =
            WingsImportParser.extractBackendProfilesFromSubscriptionBody(
                RuntimeEnvironment.getApplication(), subscriptionBody(config)
            );

        assertEquals(1, got.size());
        assertEquals(WingsImportParser.ImportedBackendProfile.Kind.VK_TURN, got.get(0).kind);
        assertNotNull("the referenced transport did not travel with the profile", got.get(0).wireGuardProfile);
        assertEquals("wg-1", got.get(0).wireGuardProfile.id);
    }

    // A transport nothing references is a profile in its own right
    @Test
    public void anUnreferencedTransportComesThroughOnItsOwn() throws Exception {
        WingsvProto.Config config = baseConfig()
            .setTurn(
                WingsvProto.Turn.newBuilder()
                    .addProfiles(WingsImportParser.toProtoTurnProfile(turnProfile("paid-1", "wg-1", false)))
            )
            .setWg(
                WingsvProto.WireGuard.newBuilder()
                    .addProfiles(WingsvProto.WireGuardProfile.newBuilder().setId("wg-1"))
                    .addProfiles(WingsvProto.WireGuardProfile.newBuilder().setId("wg-standalone"))
            )
            .build();

        List<WingsImportParser.ImportedBackendProfile> got =
            WingsImportParser.extractBackendProfilesFromSubscriptionBody(
                RuntimeEnvironment.getApplication(), subscriptionBody(config)
            );

        assertEquals(2, got.size());
        boolean sawStandalone = false;
        for (WingsImportParser.ImportedBackendProfile entry : got) {
            if (entry.kind == WingsImportParser.ImportedBackendProfile.Kind.WIREGUARD) {
                sawStandalone = "wg-standalone".equals(entry.wireGuardProfile.id);
            }
        }
        assertTrue("the unreferenced transport was swallowed", sawStandalone);
    }

    // A non-provisioned profile whose transport is missing cannot connect, so
    // handing it to the user would be handing them a broken entry
    @Test
    public void aProfileWithAMissingTransportIsDropped() throws Exception {
        WingsvProto.Config config = baseConfig()
            .setTurn(
                WingsvProto.Turn.newBuilder()
                    .addProfiles(WingsImportParser.toProtoTurnProfile(turnProfile("paid-1", "wg-missing", false)))
            )
            .build();

        List<WingsImportParser.ImportedBackendProfile> got =
            WingsImportParser.extractBackendProfilesFromSubscriptionBody(
                RuntimeEnvironment.getApplication(), subscriptionBody(config)
            );

        assertTrue("a profile with no transport was handed over anyway", got.isEmpty());
    }
}
