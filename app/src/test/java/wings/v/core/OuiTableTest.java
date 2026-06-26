package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class OuiTableTest {

    @Test
    public void resolvesKnownSamsungOui() {
        assertEquals("Samsung", OuiTable.lookup("00:07:AB"));
    }

    @Test
    public void lookupIsCaseInsensitive() {
        assertEquals("Samsung", OuiTable.lookup("00:07:ab"));
    }

    @Test
    public void returnsNullForNullPrefix() {
        assertNull(OuiTable.lookup(null));
    }

    @Test
    public void returnsNullForUnknownOui() {
        assertNull(OuiTable.lookup("FF:FF:FF"));
    }
}
