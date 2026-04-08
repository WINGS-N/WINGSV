package wings.v.core;

import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.net.Authenticator;
import java.net.PasswordAuthentication;

@SuppressWarnings({ "PMD.NullAssignment", "PMD.AvoidSynchronizedStatement" })
public final class SocksProxyAuthenticator {

    private static final Object LOCK = new Object();
    private static boolean installed;
    private static ActiveCredentials activeCredentials;

    private SocksProxyAuthenticator() {}

    public static <T> T run(
        @Nullable String host,
        int port,
        @Nullable String username,
        @Nullable String password,
        @NonNull Request<T> request
    ) throws Exception {
        String normalizedUsername = trim(username);
        String normalizedPassword = trim(password);
        if (TextUtils.isEmpty(normalizedUsername) || TextUtils.isEmpty(normalizedPassword) || port <= 0) {
            return request.run();
        }
        synchronized (LOCK) {
            installAuthenticator();
            ActiveCredentials credentials = new ActiveCredentials(port, normalizedUsername, normalizedPassword);
            activeCredentials = credentials;
            try {
                return request.run();
            } finally {
                if (credentials.equals(activeCredentials)) {
                    activeCredentials = null;
                }
            }
        }
    }

    private static void installAuthenticator() {
        if (installed) {
            return;
        }
        Authenticator.setDefault(
            new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    ActiveCredentials credentials = activeCredentials;
                    if (
                        credentials == null ||
                        getRequestorType() != RequestorType.PROXY ||
                        getRequestingPort() != credentials.port
                    ) {
                        return null;
                    }
                    return new PasswordAuthentication(credentials.username, credentials.password.toCharArray());
                }
            }
        );
        installed = true;
    }

    @NonNull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    public interface Request<T> {
        T run() throws Exception;
    }

    private static final class ActiveCredentials {

        final int port;
        final String username;
        final String password;

        ActiveCredentials(int port, @NonNull String username, @NonNull String password) {
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }
}
