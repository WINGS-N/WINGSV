package wings.v.vk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// org.json is a stub on the host JVM, so these run under Robolectric. A bare
// Application keeps WingsApplication.onCreate - and its MMKV native lib - out of it.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class VkCallsApiTest {

    @Test
    public void parsesTheJoinLink() throws IOException {
        String payload = "{\"response\":{\"call_id\":\"c1\",\"join_link\":\"https://vk.com/call/join/abc\"}}";
        assertEquals("https://vk.com/call/join/abc", VkCallsApi.parseJoinLink(payload));
    }

    // The error VK returns is the only clue an operator gets, so the code has to
    // survive into the message: it is what separates a throttle from a bad token
    // from an anti-scraping stub.
    @Test
    public void surfacesTheVkErrorCode() {
        String payload = "{\"error\":{\"error_code\":9,\"error_msg\":\"Flood control\"}}";
        try {
            VkCallsApi.parseJoinLink(payload);
            fail("an error payload must not parse as a link");
        } catch (IOException error) {
            String message = error.getMessage();
            assertTrue(message, message.contains("Flood control"));
            assertTrue(message, message.contains("9"));
        }
    }

    @Test
    public void rejectsAResponseWithoutALink() {
        try {
            VkCallsApi.parseJoinLink("{\"response\":{\"call_id\":\"c1\"}}");
            fail("a response with no join_link must not parse");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("join_link"));
        }
    }

    @Test
    public void rejectsNonJsonPayload() {
        try {
            VkCallsApi.parseJoinLink("<html>nope</html>");
            fail("a non-JSON payload must not parse");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not JSON"));
        }
    }
}
