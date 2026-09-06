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
            provisioned, provisioned ? "client-42" : "", provisioned ? "746f6b656e" : ""
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
        // Токен хранится одним видом - hex-строкой, и круг через подписку его не
        // перекодирует ни во что другое
        assertEquals("746f6b656e", entry.vkTurnProfile.provisionToken);
    }

    // Ссылка, выданная до перехода на hex, несёт сырой дайджест: его переводим
    // сами, иначе модель хранила бы два вида и гадала при каждой отправке
    @Test
    public void aRawTokenFromAnOlderLinkBecomesHex() throws Exception {
        WingsvProto.Config config = baseConfig()
            .setTurn(
                WingsvProto.Turn.newBuilder()
                    .addProfiles(
                        WingsImportParser.toProtoTurnProfile(turnProfile("free-2", "", true))
                            .toBuilder()
                            .setProvisionToken(com.google.protobuf.ByteString.copyFrom(new byte[] {(byte) 0xde, (byte) 0xad}))
                            .build()
                    )
            )
            .build();

        List<WingsImportParser.ImportedBackendProfile> got =
            WingsImportParser.extractBackendProfilesFromSubscriptionBody(
                RuntimeEnvironment.getApplication(), subscriptionBody(config)
            );

        assertEquals(1, got.size());
        assertEquals("dead", got.get(0).vkTurnProfile.provisionToken);
    }

    // Тело подписки федерации - наш кадр, а не список vless-ссылок. Обычный
    // разбор в нём ничего не находит, и без своего пути список серверов пуст
    @Test
    public void xrayProfilesComeOutOfAWingsvSubscriptionBody() throws Exception {
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(1)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_XRAY)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_XRAY)
            .setXray(
                WingsvProto.Xray.newBuilder()
                    .addProfiles(
                        WingsvProto.VlessProfile.newBuilder()
                            .setId("fed-01")
                            .setTitle("Germany #1 / TCP")
                            .setRawLink("vless://uuid@1.2.3.4:443?type=tcp&security=reality#Germany")
                    )
                    .addProfiles(
                        WingsvProto.VlessProfile.newBuilder()
                            .setId("fed-02")
                            .setTitle("Germany #1 / XHTTP")
                            .setRawLink("vless://uuid@1.2.3.4:8444?type=xhttp&security=reality#Germany")
                    )
            )
            .build();

        List<XrayProfile> got = WingsImportParser.extractXrayProfilesFromSubscriptionBody(
            subscriptionBody(config), "sub-1", "Federation"
        );

        assertEquals(2, got.size());
        assertEquals("Germany #1 / TCP", got.get(0).title);
        assertEquals("sub-1", got.get(0).subscriptionId);
        assertEquals("Federation", got.get(0).subscriptionTitle);
        assertTrue("ссылка потерялась", got.get(1).rawLink.contains("type=xhttp"));
    }

    // Подписка федерации несёт Xray и VK TURN одним телом, и приложение обязано
    // разобрать оба списка
    @Test
    public void oneBodyCarriesBothProtocols() throws Exception {
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(1)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_XRAY)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_XRAY)
            .setXray(
                WingsvProto.Xray.newBuilder()
                    .addProfiles(
                        WingsvProto.VlessProfile.newBuilder()
                            .setId("fed-01")
                            .setTitle("Germany #1 / TCP")
                            .setRawLink("vless://uuid@1.2.3.4:443?type=tcp#Germany")
                    )
            )
            .setTurn(
                WingsvProto.Turn.newBuilder()
                    .addProfiles(WingsImportParser.toProtoTurnProfile(turnProfile("free-1", "", true)))
            )
            .build();
        String body = subscriptionBody(config);

        List<XrayProfile> xray = WingsImportParser.extractXrayProfilesFromSubscriptionBody(body, "sub-1", "Federation");
        List<WingsImportParser.ImportedBackendProfile> backend =
            WingsImportParser.extractBackendProfilesFromSubscriptionBody(RuntimeEnvironment.getApplication(), body);

        assertEquals("vless из подписки потерялся", 1, xray.size());
        assertEquals("VK TURN из той же подписки потерялся", 1, backend.size());
        assertEquals(WingsImportParser.ImportedBackendProfile.Kind.VK_TURN, backend.get(0).kind);
        assertTrue(backend.get(0).vkTurnProfile.wgProvisioned);
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
