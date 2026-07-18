package wings.v.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// Use a bare Application so Robolectric does not run WingsApplication.onCreate,
// which loads the MMKV native lib (absent on the host JVM).
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class XrayVpnDnsTest {

    @Test
    public void splitsTheWireGuardDnsLineOnCommas() {
        assertEquals(Arrays.asList("9.9.9.9", "149.112.112.112"), XrayVpnService.splitDnsList("9.9.9.9, 149.112.112.112"));
    }

    @Test
    public void splitsOnSemicolonsAndBareWhitespace() {
        assertEquals(Arrays.asList("9.9.9.9", "1.0.0.1"), XrayVpnService.splitDnsList("9.9.9.9;1.0.0.1"));
        assertEquals(Arrays.asList("9.9.9.9", "1.0.0.1"), XrayVpnService.splitDnsList("9.9.9.9  1.0.0.1"));
    }

    @Test
    public void treatsBlankLinesAsNoServers() {
        assertTrue(XrayVpnService.splitDnsList(null).isEmpty());
        assertTrue(XrayVpnService.splitDnsList("   ").isEmpty());
    }

    @Test
    public void keepsPlainResolverAddresses() {
        assertEquals("9.9.9.9", XrayVpnService.normalizeDnsServerForVpn("9.9.9.9"));
    }

    @Test
    public void stripsThePortAndTheIpv6Brackets() {
        assertEquals("9.9.9.9", XrayVpnService.normalizeDnsServerForVpn("9.9.9.9:53"));
        assertEquals("2620:fe::fe", XrayVpnService.normalizeDnsServerForVpn("[2620:fe::fe]"));
    }

    /** A DoH or DoT entry cannot be handed to VpnService, so it must not be advertised. */
    @Test
    public void rejectsUrlStyleResolvers() {
        assertEquals("", XrayVpnService.normalizeDnsServerForVpn("https://common.dot.dns.yandex.net/dns-query"));
        assertEquals("", XrayVpnService.normalizeDnsServerForVpn("tls://dns.google"));
        assertEquals("", XrayVpnService.normalizeDnsServerForVpn("quic://dns.adguard.com"));
        assertEquals("", XrayVpnService.normalizeDnsServerForVpn("h3://dns.google"));
    }

    /** The IPv6 filter in addWireGuardDnsServers keys off a colon surviving normalization. */
    @Test
    public void marksIpv6ResolversAsSuchAfterNormalization() {
        List<String> parsed = XrayVpnService.splitDnsList("9.9.9.9, 2620:fe::fe");
        assertEquals(2, parsed.size());
        assertTrue(XrayVpnService.normalizeDnsServerForVpn(parsed.get(0)).indexOf(':') < 0);
        assertTrue(XrayVpnService.normalizeDnsServerForVpn(parsed.get(1)).indexOf(':') >= 0);
    }
}
