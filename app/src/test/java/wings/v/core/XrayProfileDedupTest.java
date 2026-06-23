package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class XrayProfileDedupTest {

    // Two fetches of the same parallel.whsrv.ru server differ only in the
    // rotating Reality sid and spx; the dedup key must be identical so the
    // subscription update reuses the existing profile id (and keeps it in
    // favorites / as the active profile).
    @Test
    public void ignoresRotatingSidAndSpx() {
        String stored =
            "vless://a552d4aa-2c4b-4366-9e99-385bfc5adea0@parallel.whsrv.ru:443" +
            "?encryption=mlkem768x25519plus.native.0rtt.KEYMATERIAL&flow=xtls-rprx-vision" +
            "&fp=firefox&pbk=PBK&security=reality&sid=c171539a7e84&sni=music.yandex.ru" +
            "&spx=%2Fzght1g9lP9gvf5N&type=tcp#vless-1-n-S22U";
        String fresh =
            "vless://a552d4aa-2c4b-4366-9e99-385bfc5adea0@parallel.whsrv.ru:443" +
            "?encryption=mlkem768x25519plus.native.0rtt.KEYMATERIAL&flow=xtls-rprx-vision" +
            "&fp=firefox&pbk=PBK&security=reality&sid=f1ecf10784fa1918&sni=music.yandex.ru" +
            "&spx=%2FyVAkb5QEla3OCUV&type=tcp#vless-1-n-S22U";
        assertEquals(XrayProfile.normalizeLinkForDedup(stored), XrayProfile.normalizeLinkForDedup(fresh));
    }

    // A different credential (uuid) is a different server entry and must NOT
    // collapse onto the same key.
    @Test
    public void distinguishesDifferentUuid() {
        String a = "vless://uuid-aaaa@parallel.whsrv.ru:443?security=reality&sid=11&sni=a#x";
        String b = "vless://uuid-bbbb@parallel.whsrv.ru:443?security=reality&sid=11&sni=a#x";
        assertNotEquals(XrayProfile.normalizeLinkForDedup(a), XrayProfile.normalizeLinkForDedup(b));
    }

    // Identity-bearing params (sni, pbk, port) still differentiate.
    @Test
    public void keepsIdentityParams() {
        String a = "vless://u@host:443?security=reality&sni=a&pbk=P1&sid=1#x";
        String b = "vless://u@host:443?security=reality&sni=b&pbk=P2&sid=2#x";
        assertNotEquals(XrayProfile.normalizeLinkForDedup(a), XrayProfile.normalizeLinkForDedup(b));
    }

    @Test
    public void handlesLinkWithoutQuery() {
        assertEquals(
            XrayProfile.normalizeLinkForDedup("VLESS://u@host:443#Tag"),
            XrayProfile.normalizeLinkForDedup("vless://u@host:443#tag")
        );
    }
}
