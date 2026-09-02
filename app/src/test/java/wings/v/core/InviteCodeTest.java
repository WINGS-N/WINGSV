package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Сканер должен узнавать приглашение и не хватать всё подряд */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class InviteCodeTest {

    @Test
    public void takesTheCodeOutOfARegistrationLink() {
        assertEquals("A1B2C3D4E5F60789", InviteCode.parse("https://v.wingsnet.org/register?invite=A1B2C3D4E5F60789"));
        // Старые коды строчными тоже принимаются, но приводятся к верхнему регистру
        assertEquals("DEADBEEF12345678", InviteCode.parse("https://v.wingsnet.org/register?invite=deadbeef12345678"));
    }

    @Test
    public void takesTheCodeOutOfOurScheme() {
        assertEquals("A1B2C3D4E5F60789", InviteCode.parse("wingsv://invite/A1B2C3D4E5F60789"));
    }

    @Test
    public void refusesEverythingElse() {
        // Голый HEX без префикса - не приглашение: такого добра в любой ссылке хватает
        assertNull(InviteCode.parse("A1B2C3D4E5F60789"));
        assertNull(InviteCode.parse("https://v.wingsnet.org/register"));
        assertNull(InviteCode.parse("wingsv://invite/не-хекс"));
        assertNull(InviteCode.parse("vless://uuid@1.2.3.4:443"));
        assertNull(InviteCode.parse(""));
        assertNull(InviteCode.parse(null));
    }

    @Test
    public void buildsTheLinkAnyCameraUnderstands() {
        assertEquals(
            "https://v.wingsnet.org/register?invite=A1B2C3D4E5F60789",
            InviteCode.link("https://v.wingsnet.org", "a1b2c3d4e5f60789")
        );
    }
}
