package wings.v.xray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Профиль приезжает из ссылки или подписки и может нести то, что ядро уже не
 * принимает: такой конфиг не грузится целиком. Robolectric нужен из-за
 * TextUtils и JSONObject, Application.class уводит от нативного MMKV.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class XrayOutboundSanitizeTest {

    @Test
    public void proxySettingsBecomeADialerProxy() throws Exception {
        JSONObject outbound = new JSONObject(
            "{\"protocol\":\"vless\",\"settings\":{},\"proxySettings\":{\"tag\":\"byedpi-front\"," +
            "\"transportLayer\":true},\"streamSettings\":{\"network\":\"tcp\"}}"
        );

        XrayConfigFactory.sanitizeOutbound(outbound, null);

        assertFalse("ядро отказывает конфигу с proxySettings", outbound.has("proxySettings"));
        JSONObject sockopt = outbound.getJSONObject("streamSettings").getJSONObject("sockopt");
        assertEquals("byedpi-front", sockopt.getString("dialerProxy"));
    }

    @Test
    public void anExistingDialerProxyIsKept() throws Exception {
        JSONObject outbound = new JSONObject(
            "{\"protocol\":\"vless\",\"settings\":{},\"proxySettings\":{\"tag\":\"byedpi-front\"}," +
            "\"streamSettings\":{\"network\":\"tcp\",\"sockopt\":{\"dialerProxy\":\"other\"}}}"
        );

        XrayConfigFactory.sanitizeOutbound(outbound, null);

        JSONObject sockopt = outbound.getJSONObject("streamSettings").getJSONObject("sockopt");
        assertEquals("other", sockopt.getString("dialerProxy"));
    }

    @Test
    public void allowInsecureIsDropped() throws Exception {
        JSONObject outbound = new JSONObject(
            "{\"protocol\":\"vless\",\"settings\":{},\"streamSettings\":{\"network\":\"tcp\"," +
            "\"security\":\"tls\",\"tlsSettings\":{\"serverName\":\"example.com\",\"allowInsecure\":true}}}"
        );

        XrayConfigFactory.sanitizeOutbound(outbound, null);

        JSONObject tlsSettings = outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings");
        assertFalse("ядро отказывает конфигу с allowInsecure", tlsSettings.has("allowInsecure"));
        assertEquals("example.com", tlsSettings.getString("serverName"));
    }
}
