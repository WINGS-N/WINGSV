package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// Use a bare Application so Robolectric does not run WingsApplication.onCreate,
// which loads the MMKV native lib (absent on the host JVM).
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class TetherTypeTest {

    @Test
    public void fromCommandNameIsCaseInsensitive() {
        assertEquals(TetherType.WIFI, TetherType.fromCommandName("WiFi"));
        assertEquals(TetherType.USB, TetherType.fromCommandName("usb"));
    }

    @Test
    public void fromCommandNameRejectsUnknownAndNull() {
        assertThrows(IllegalArgumentException.class, () -> TetherType.fromCommandName("zigbee"));
        assertThrows(IllegalArgumentException.class, () -> TetherType.fromCommandName(null));
    }

    @Test
    public void detectsTypeFromInterfaceName() {
        assertEquals(TetherType.WIFI, TetherType.detectFromInterfaceName("wlan0"));
        assertEquals(TetherType.WIFI, TetherType.detectFromInterfaceName("swlan0"));
        assertEquals(TetherType.USB, TetherType.detectFromInterfaceName("rndis0"));
        assertEquals(TetherType.USB, TetherType.detectFromInterfaceName("usb0"));
        assertEquals(TetherType.BLUETOOTH, TetherType.detectFromInterfaceName("bt-pan"));
        assertEquals(TetherType.ETHERNET, TetherType.detectFromInterfaceName("eth0"));
        assertNull(TetherType.detectFromInterfaceName("rmnet0"));
    }

    @Test
    public void readsEnabledTypesFromIntentArrayList() {
        Intent intent = new Intent();
        intent.putStringArrayListExtra("tetherArray", new ArrayList<>(Arrays.asList("wlan0", "usb0", "lo")));
        Set<TetherType> types = TetherType.readEnabledTypes(intent);
        assertEquals(EnumSet.of(TetherType.WIFI, TetherType.USB), types);
    }

    @Test
    public void readsNoTypesFromNullIntent() {
        assertTrue(TetherType.readEnabledTypes(null).isEmpty());
    }
}
