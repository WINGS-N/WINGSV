package wings.v.core;

import java.util.ArrayList;
import java.util.List;

public final class ByeDpiShellUtils {

    private ByeDpiShellUtils() {}

    public static List<String> shellSplit(CharSequence value) {
        ArrayList<String> tokens = new ArrayList<>();
        if (value == null) {
            return tokens;
        }
        char quoteChar = ' ';
        boolean escaping = false;
        boolean quoting = false;
        int lastCloseQuoteIndex = Integer.MIN_VALUE;
        StringBuilder current = new StringBuilder();

        for (int index = 0; index < value.length(); index++) {
            char currentChar = value.charAt(index);
            if (escaping) {
                current.append(currentChar);
                escaping = false;
            } else if (currentChar == '\\' && quoting) {
                if (index + 1 < value.length() && value.charAt(index + 1) == quoteChar) {
                    escaping = true;
                } else {
                    current.append(currentChar);
                }
            } else if (quoting && currentChar == quoteChar) {
                quoting = false;
                lastCloseQuoteIndex = index;
            } else if (!quoting && (currentChar == '\'' || currentChar == '"')) {
                quoting = true;
                quoteChar = currentChar;
            } else if (!quoting && Character.isWhitespace(currentChar)) {
                if (current.length() > 0 || lastCloseQuoteIndex == index - 1) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(currentChar);
            }
        }

        if (current.length() > 0 || lastCloseQuoteIndex == value.length() - 1) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
