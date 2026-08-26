package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Routing is a storage contract, so the mapping is pinned by tests. */
public class MmkvPrefsAreasTest {

    @Test
    public void routesEachSubsystemToItsOwnArea() {
        assertEquals(MmkvPrefsAreas.AREA_XRAY, MmkvPrefsAreas.areaFor("pref_xray_routing_domain_strategy"));
        assertEquals(MmkvPrefsAreas.AREA_VK, MmkvPrefsAreas.areaFor("pref_vk_links_json"));
        assertEquals(MmkvPrefsAreas.AREA_VK, MmkvPrefsAreas.areaFor("pref_captcha_solver_version"));
        assertEquals(MmkvPrefsAreas.AREA_SHARING, MmkvPrefsAreas.areaFor("pref_sharing_masquerade_mode"));
        assertEquals(MmkvPrefsAreas.AREA_GUARDIAN, MmkvPrefsAreas.areaFor("pref_guardian_client_token_b64"));
        assertEquals(MmkvPrefsAreas.AREA_TUNNEL, MmkvPrefsAreas.areaFor("pref_wg_private_key"));
        assertEquals(MmkvPrefsAreas.AREA_TUNNEL, MmkvPrefsAreas.areaFor("pref_awg_jc"));
        assertEquals(MmkvPrefsAreas.AREA_TUNNEL, MmkvPrefsAreas.areaFor("pref_backend_type"));
        assertEquals(MmkvPrefsAreas.AREA_ROOT, MmkvPrefsAreas.areaFor("pref_root_mode_enabled"));
        assertEquals(MmkvPrefsAreas.AREA_WBSTREAM, MmkvPrefsAreas.areaFor("pref_wb_stream_room_id"));
        assertEquals(MmkvPrefsAreas.AREA_SUBSCRIPTION, MmkvPrefsAreas.areaFor("pref_subscription_list_json"));
    }

    @Test
    public void unknownAndUnprefixedKeysFallIntoTheAppArea() {
        assertEquals(MmkvPrefsAreas.AREA_APP, MmkvPrefsAreas.areaFor("pref_theme_mode"));
        assertEquals(MmkvPrefsAreas.AREA_APP, MmkvPrefsAreas.areaFor("service"));
        assertEquals(MmkvPrefsAreas.AREA_APP, MmkvPrefsAreas.areaFor("client_traffic_usage_"));
        assertEquals(MmkvPrefsAreas.AREA_APP, MmkvPrefsAreas.areaFor("something_nobody_declared"));
    }

    @Test
    public void routingIgnoresCaseAndTheKeyPrefix() {
        assertEquals(MmkvPrefsAreas.areaFor("pref_xray_foo"), MmkvPrefsAreas.areaFor("xray_foo"));
        assertEquals(MmkvPrefsAreas.areaFor("pref_xray_foo"), MmkvPrefsAreas.areaFor("PREF_XRAY_FOO"));
    }

    @Test
    public void areaIdsAreDistinctAndNonEmpty() {
        String[] areas = MmkvPrefsAreas.allAreas();
        Set<String> unique = new HashSet<>();
        for (String area : areas) {
            assertNotEquals("", area);
            unique.add(area);
        }
        assertEquals(areas.length, unique.size());
    }

    @Test
    public void everyAreaIsReachableFromSomeKey() {
        Set<String> declared = new HashSet<>();
        for (String area : MmkvPrefsAreas.allAreas()) {
            declared.add(area);
        }
        String[] samples = {
            "pref_xray_a",
            "pref_vk_a",
            "pref_sharing_a",
            "pref_guardian_a",
            "pref_wg_a",
            "pref_root_a",
            "pref_wb_a",
            "pref_subscription_a",
            "pref_whatever_a",
        };
        Set<String> reached = new HashSet<>();
        for (String key : samples) {
            reached.add(MmkvPrefsAreas.areaFor(key));
        }
        assertEquals(declared, reached);
    }
}
