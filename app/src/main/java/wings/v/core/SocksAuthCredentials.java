package wings.v.core;

import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class SocksAuthCredentials {
    private static final int USER_RANDOM_BYTES = 16;
    private static final int PASSWORD_RANDOM_BYTES = 32;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SocksAuthCredentials() {
    }

    @NonNull
    public static Pair ensure(@NonNull SharedPreferences preferences,
                              @NonNull String usernameKey,
                              @NonNull String passwordKey) {
        String username = trim(preferences.getString(usernameKey, ""));
        String password = trim(preferences.getString(passwordKey, ""));
        if (!TextUtils.isEmpty(username) && !TextUtils.isEmpty(password)) {
            return new Pair(username, password);
        }
        if (TextUtils.isEmpty(username)) {
            username = generateToken(USER_RANDOM_BYTES);
        }
        if (TextUtils.isEmpty(password)) {
            password = generateToken(PASSWORD_RANDOM_BYTES);
        }
        preferences.edit()
                .putString(usernameKey, username)
                .putString(passwordKey, password)
                .apply();
        return new Pair(username, password);
    }

    @NonNull
    private static String generateToken(int randomBytes) {
        byte[] seed = readUrandom(Math.max(16, Math.min(32, randomBytes)));
        return hex(sha512(seed));
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
        } catch (Exception ignored) {
        }
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    @NonNull
    private static byte[] sha512(@NonNull byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(value);
        } catch (Exception ignored) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value);
            } catch (Exception error) {
                throw new IllegalStateException("SHA-512 and SHA-256 are unavailable", error);
            }
        }
    }

    @NonNull
    private static String hex(@NonNull byte[] value) {
        char[] chars = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int item = value[index] & 0xff;
            chars[index * 2] = HEX[item >>> 4];
            chars[index * 2 + 1] = HEX[item & 0x0f];
        }
        return new String(chars);
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
