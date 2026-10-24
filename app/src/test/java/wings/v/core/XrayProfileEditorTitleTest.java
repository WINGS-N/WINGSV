package wings.v.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Правка JSON профиля не должна трогать его имя. Нужен Robolectric: кодек
 * работает через TextUtils и JSONObject, а Application.class уводит от
 * WingsApplication.onCreate с нативным MMKV.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class XrayProfileEditorTitleTest {

    private static final String OUTBOUND_JSON =
        "{\"tag\":\"proxy\",\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"example.com\"," +
        "\"port\":443,\"users\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"encryption\":\"none\"}]}]}}";

    private static XrayProfile profile(String title) {
        return new XrayProfile(
            "profile-id",
            title,
            OUTBOUND_JSON,
            "",
            "",
            "example.com",
            443
        );
    }

    @Test
    public void jsonEditKeepsTheNameTheUserGave() throws Exception {
        XrayProfile edited = XrayProfileEditorCodec.parseJsonProfile(profile("Германия 1"), OUTBOUND_JSON);

        assertEquals("Германия 1", edited.title);
        assertEquals("example.com", edited.address);
        assertEquals(443, edited.port);
    }

    @Test
    public void aProfileWithoutANameTakesTheOutboundTag() throws Exception {
        XrayProfile edited = XrayProfileEditorCodec.parseJsonProfile(profile(""), OUTBOUND_JSON);

        assertEquals("proxy", edited.title);
    }

    @Test
    public void aProfileWithoutANameOrTagFallsBackToTheServer() throws Exception {
        String noTag =
            "{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"example.com\",\"port\":443," +
            "\"users\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"encryption\":\"none\"}]}]}}";

        XrayProfile edited = XrayProfileEditorCodec.parseJsonProfile(profile(""), noTag);

        assertEquals("example.com:443", edited.title);
    }
}
