package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Имя сервера у продавцов почти всегда с пробелами, а живёт оно после решётки.
 * Разбор, режущий строку по любому пробелу, оставлял от ссылки огрызок: имя
 * обрезалось до первого слова, а с ним уезжали и параметры, если решётка стояла
 * не в конце. Ловим это на теле ровно такой формы, какое отдают продавцы.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class SubscriptionLinkNameTest {

    private static final String LINK =
        "vless://8ac136d8-1837-559a-8763-baf06d0e7d9b@cdn.example.xyz:443"
        + "?type=ws&security=tls&sni=cdn.example.xyz&path=%2Fstream%2F99f8&encryption=none"
        + "#Durev USA 66 [Белые списки]";

    @Test
    public void nameWithSpacesSurvives() {
        final List<String> links = XraySubscriptionParser.parseLinks(LINK);
        assertEquals(1, links.size());
        assertEquals(LINK, links.get(0));
    }

    @Test
    public void severalLinesStayApart() {
        final String body = LINK + "\n" + LINK.replace("66", "67") + "\n";
        final List<String> links = XraySubscriptionParser.parseLinks(body);
        assertEquals(2, links.size());
        for (String link : links) {
            assertTrue("ссылку обрезало: " + link, link.endsWith("]"));
        }
    }

    @Test
    public void linkWithoutNameIsUntouched() {
        final String bare = "vless://uuid@1.2.3.4:443?type=tcp";
        assertEquals(bare, XraySubscriptionParser.parseLinks(bare).get(0));
    }
}
