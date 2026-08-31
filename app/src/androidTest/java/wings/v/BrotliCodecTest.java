package wings.v;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import wings.v.core.BrotliCodec;

/** Нативный кодек проверяется только на устройстве: на хосте библиотеки нет */
@RunWith(AndroidJUnit4.class)
public class BrotliCodecTest {

    @Test
    public void libraryLoads() {
        assertTrue("нативная библиотека не поднялась", BrotliCodec.isAvailable());
    }

    @Test
    public void roundTripKeepsTheBytes() {
        byte[] input = ("vless://772b971c-dbf4-4f62-9cd9-026ba63bc292@45.137.70.68:443?encryption=none" +
            "&flow=xtls-rprx-vision&security=reality&sni=image.semiconductor.samsung.com#WINGS-free-01")
            .getBytes(StandardCharsets.UTF_8);

        byte[] packed = BrotliCodec.compress(input);
        assertNotNull("сжатие не удалось", packed);
        assertTrue("сжатое не короче исходного", packed.length < input.length);

        byte[] restored = BrotliCodec.decompress(packed);
        assertArrayEquals(input, restored);
    }

    @Test
    public void brokenInputDoesNotCrash() {
        assertEquals(null, BrotliCodec.decompress(new byte[] { 0x01, 0x02, 0x03 }));
    }
}
