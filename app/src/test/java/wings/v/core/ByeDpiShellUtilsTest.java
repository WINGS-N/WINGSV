package wings.v.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ByeDpiShellUtilsTest {

    @Test
    public void splitsPlainArgsOnWhitespace() {
        assertEquals(Arrays.asList("a", "b", "c"), ByeDpiShellUtils.shellSplit("a b  c"));
    }

    @Test
    public void keepsDoubleQuotedSpaces() {
        assertEquals(Arrays.asList("a", "b c", "d"), ByeDpiShellUtils.shellSplit("a \"b c\" d"));
    }

    @Test
    public void keepsSingleQuotedSpaces() {
        assertEquals(Arrays.asList("hello world"), ByeDpiShellUtils.shellSplit("'hello world'"));
    }

    @Test
    public void preservesEmptyQuotedToken() {
        assertEquals(Arrays.asList("a", "", "b"), ByeDpiShellUtils.shellSplit("a \"\" b"));
    }

    @Test
    public void unescapesQuoteInsideQuotes() {
        assertEquals(Arrays.asList("a\"b"), ByeDpiShellUtils.shellSplit("\"a\\\"b\""));
    }

    @Test
    public void trimsSurroundingWhitespace() {
        assertEquals(Arrays.asList("x", "y"), ByeDpiShellUtils.shellSplit("  x   y  "));
    }

    @Test
    public void nullYieldsEmptyList() {
        List<String> result = ByeDpiShellUtils.shellSplit(null);
        assertTrue(result.isEmpty());
    }
}
