package wings.v.core;

import android.text.TextUtils;
import androidx.annotation.Nullable;
import java.util.Locale;
import java.util.Set;

public final class SocksAuthSecurity {

    private static final int MIN_RECOMMENDED_PASSWORD_LENGTH = 12;
    private static final int LONG_PASSWORD_LENGTH = 20;
    private static final Set<String> COMMON_PASSWORDS = Set.of(
        "password",
        "password1",
        "123456",
        "12345678",
        "123456789",
        "qwerty",
        "qwerty123",
        "admin",
        "admin123",
        "root",
        "proxy",
        "socks",
        "wingsv",
        "111111",
        "000000"
    );

    private SocksAuthSecurity() {}

    public static boolean isPasswordTooSimple(@Nullable String username, @Nullable String password) {
        String normalizedPassword = normalize(password);
        if (normalizedPassword.isEmpty()) {
            return true;
        }
        if (normalizedPassword.length() < MIN_RECOMMENDED_PASSWORD_LENGTH) {
            return true;
        }
        String normalizedUsername = normalize(username);
        if (!normalizedUsername.isEmpty() && normalizedPassword.equalsIgnoreCase(normalizedUsername)) {
            return true;
        }
        if (COMMON_PASSWORDS.contains(normalizedPassword.toLowerCase(Locale.US))) {
            return true;
        }
        if (isRepeatedCharacterPassword(normalizedPassword)) {
            return true;
        }
        if (isSimpleSequentialPassword(normalizedPassword)) {
            return true;
        }
        int categories = countCharacterCategories(normalizedPassword);
        if (normalizedPassword.length() >= LONG_PASSWORD_LENGTH) {
            return categories < 2;
        }
        return categories < 3;
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isRepeatedCharacterPassword(String value) {
        if (value.length() < MIN_RECOMMENDED_PASSWORD_LENGTH) {
            return true;
        }
        char first = value.charAt(0);
        for (int index = 1; index < value.length(); index++) {
            if (value.charAt(index) != first) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSimpleSequentialPassword(String value) {
        if (TextUtils.isEmpty(value) || value.length() < 6) {
            return false;
        }
        boolean ascending = true;
        boolean descending = true;
        for (int index = 1; index < value.length(); index++) {
            int previous = value.charAt(index - 1);
            int current = value.charAt(index);
            ascending &= current == previous + 1;
            descending &= current == previous - 1;
        }
        return ascending || descending;
    }

    private static int countCharacterCategories(String value) {
        boolean hasLower = false;
        boolean hasUpper = false;
        boolean hasDigit = false;
        boolean hasOther = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLowerCase(current)) {
                hasLower = true;
            } else if (Character.isUpperCase(current)) {
                hasUpper = true;
            } else if (Character.isDigit(current)) {
                hasDigit = true;
            } else {
                hasOther = true;
            }
        }
        int total = 0;
        total += hasLower ? 1 : 0;
        total += hasUpper ? 1 : 0;
        total += hasDigit ? 1 : 0;
        total += hasOther ? 1 : 0;
        return total;
    }
}
