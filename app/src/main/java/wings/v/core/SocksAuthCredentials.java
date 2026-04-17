package wings.v.core;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;
import androidx.annotation.NonNull;
import java.io.FileInputStream;
import java.security.SecureRandom;

@SuppressWarnings({ "PMD.AvoidFileStream", "PMD.AvoidCatchingGenericException" })
public final class SocksAuthCredentials {

    private static final int RANDOM_BYTES = 32;

    private SocksAuthCredentials() {}

    @NonNull
    public static Pair ensure(
        @NonNull SharedPreferences preferences,
        @NonNull String usernameKey,
        @NonNull String passwordKey
    ) {
        String username = trim(preferences.getString(usernameKey, ""));
        String password = trim(preferences.getString(passwordKey, ""));
        if (!TextUtils.isEmpty(username) && !TextUtils.isEmpty(password)) {
            return new Pair(username, password);
        }
        if (TextUtils.isEmpty(username)) {
            username = generateToken();
        }
        if (TextUtils.isEmpty(password)) {
            password = generateToken();
        }
        preferences.edit().putString(usernameKey, username).putString(passwordKey, password).commit();
        return new Pair(username, password);
    }

    @NonNull
    private static String generateToken() {
        return Base64.encodeToString(readUrandom(RANDOM_BYTES), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    @NonNull
    private static byte[] readUrandom(int bytes) {
        byte[] seed = new byte[bytes];
        try (FileInputStream inputStream = new FileInputStream("/dev/urandom")) {
            int offset = 0;
            while (offset < seed.length) {
                int read = inputStream.read(seed, offset, seed.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset == seed.length) {
                return seed;
            }
        } catch (Exception ignored) {}
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    @NonNull
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Pair {

        public final String username;
        public final String password;

        Pair(@NonNull String username, @NonNull String password) {
            this.username = username;
            this.password = password;
        }
    }
}
