package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import wings.v.proto.WingsvProto;

/**
 * Пул звонков едет внутри выданного профиля, а хранится у нас одним общим
 * списком. Без сбора из подписки человек получает профиль VK TURN, по которому
 * некуда звонить.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class SubscriptionVkLinksTest {

    @Test
    public void poolTravelsWithTheSubscription() throws Exception {
        WingsvProto.Config config = WingsvProto.Config.newBuilder()
            .setVer(1)
            .setBackend(WingsvProto.BackendType.BACKEND_TYPE_VK_TURN)
            .setType(WingsvProto.ConfigType.CONFIG_TYPE_VK)
            .setTurn(
                WingsvProto.Turn.newBuilder()
                    .addProfiles(
                        WingsvProto.TurnProfile.newBuilder()
                            .setId("free-1")
                            .setConfig(
                                WingsvProto.Turn.newBuilder()
                                    .addLinks("https://vk.com/call/join/one")
                                    .addLinks("https://vk.com/call/join/two")
                            )
                    )
            )
            .build();

        List<String> got = WingsImportParser.extractVkLinksFromSubscriptionBody(
            WingsImportParser.encodeConfig(config)
        );

        assertEquals(2, got.size());
        assertTrue("ссылка потерялась", got.contains("https://vk.com/call/join/one"));
    }
}
