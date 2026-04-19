package wings.v.service;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AtomicFile;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@SuppressWarnings({ "PMD.AvoidFileStream", "PMD.AvoidCatchingGenericException", "PMD.TooManyMethods" })
public final class RuntimeStateStore {

    static final String STATE_STOPPED = "STOPPED";
    static final String STATE_CONNECTING = "CONNECTING";
    static final String STATE_STOPPING = "STOPPING";
    static final String STATE_RUNNING = "RUNNING";

    private static final String STATE_FILE_NAME = "wingsv_runtime_state.properties";
    private static final String RUNTIME_LOG_FILE_NAME = "wingsv_runtime.log";
    private static final String PROXY_LOG_FILE_NAME = "wingsv_proxy.log";
    private static final long MAX_LOG_BYTES = 256L * 1024L;
    private static final long TRIMMED_LOG_BYTES = 192L * 1024L;
    private static final long SNAPSHOT_CACHE_TTL_MS = 200L;

    private static final String KEY_STATE = "state";
    private static final String KEY_BACKEND_TYPE = "backend_type";
    private static final String KEY_RX = "rx";
    private static final String KEY_TX = "tx";
    private static final String KEY_RXPS = "rxps";
    private static final String KEY_TXPS = "txps";
    private static final String KEY_PUBLIC_IP = "public_ip";
    private static final String KEY_PUBLIC_COUNTRY = "public_country";
    private static final String KEY_PUBLIC_ISP = "public_isp";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_VISIBLE_ERROR = "visible_error";
    private static final String KEY_ERROR_SESSION = "error_session";
    private static final String KEY_DISMISSED_ERROR_SESSION = "dismissed_error_session";
    private static final String KEY_PUBLIC_IP_REFRESHING = "public_ip_refreshing";
    private static final String KEY_CAPTCHA_LOCKOUT_UNTIL = "captcha_lockout_until";
    private static final String KEY_RUNTIME_LOG_VERSION = "runtime_log_version";
    private static final String KEY_PROXY_LOG_VERSION = "proxy_log_version";
    private static final String KEY_PROTECT_SOCKET_NAME = "protect_socket_name";

    private static volatile Context appContext;
    private static volatile Snapshot cachedSnapshot;
    private static volatile long cachedSnapshotAtElapsedMs;

    private RuntimeStateStore() {}

    public static void initialize(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    static Snapshot readSnapshot() {
        long now = SystemClock.elapsedRealtime();
        Snapshot snapshot = cachedSnapshot;
        if (snapshot != null && now - cachedSnapshotAtElapsedMs <= SNAPSHOT_CACHE_TTL_MS) {
            return snapshot;
        }
        synchronized (RuntimeStateStore.class) {
            snapshot = cachedSnapshot;
            if (snapshot != null && now - cachedSnapshotAtElapsedMs <= SNAPSHOT_CACHE_TTL_MS) {
                return snapshot;
            }
            Properties properties = readProperties();
            snapshot = new Snapshot(
                properties.getProperty(KEY_STATE, STATE_STOPPED),
                emptyToNull(properties.getProperty(KEY_BACKEND_TYPE)),
                readLong(properties, KEY_RX),
                readLong(properties, KEY_TX),
                readLong(properties, KEY_RXPS),
                readLong(properties, KEY_TXPS),
                emptyToNull(properties.getProperty(KEY_PUBLIC_IP)),
                emptyToNull(properties.getProperty(KEY_PUBLIC_COUNTRY)),
                emptyToNull(properties.getProperty(KEY_PUBLIC_ISP)),
                emptyToNull(properties.getProperty(KEY_LAST_ERROR)),
                emptyToNull(properties.getProperty(KEY_VISIBLE_ERROR)),
                readLong(properties, KEY_ERROR_SESSION),
                readLong(properties, KEY_DISMISSED_ERROR_SESSION),
                readBoolean(properties, KEY_PUBLIC_IP_REFRESHING),
                readLong(properties, KEY_CAPTCHA_LOCKOUT_UNTIL),
                readLong(properties, KEY_RUNTIME_LOG_VERSION),
                readLong(properties, KEY_PROXY_LOG_VERSION),
                emptyToNull(properties.getProperty(KEY_PROTECT_SOCKET_NAME))
            );
            cachedSnapshot = snapshot;
            cachedSnapshotAtElapsedMs = now;
            return snapshot;
        }
    }

    static void writeState(String state) {
        updateProperties(properties -> properties.setProperty(KEY_STATE, firstNonEmpty(state, STATE_STOPPED)));
    }

    static void writeRuntimeState(String state, @Nullable String backendType) {
        updateProperties(properties -> {
            properties.setProperty(KEY_STATE, firstNonEmpty(state, STATE_STOPPED));
            setNullable(properties, KEY_BACKEND_TYPE, backendType);
        });
    }

    static void writeBackendType(String backendType) {
        updateProperties(properties -> setNullable(properties, KEY_BACKEND_TYPE, backendType));
    }

    static void writeTraffic(long rx, long tx, long rxPerSecond, long txPerSecond) {
        updateProperties(properties -> {
            properties.setProperty(KEY_RX, String.valueOf(Math.max(0L, rx)));
            properties.setProperty(KEY_TX, String.valueOf(Math.max(0L, tx)));
            properties.setProperty(KEY_RXPS, String.valueOf(Math.max(0L, rxPerSecond)));
            properties.setProperty(KEY_TXPS, String.valueOf(Math.max(0L, txPerSecond)));
        });
    }

    static void writePublicIp(String ip, String country, String isp) {
        updateProperties(properties -> {
            setNullable(properties, KEY_PUBLIC_IP, ip);
            setNullable(properties, KEY_PUBLIC_COUNTRY, country);
            setNullable(properties, KEY_PUBLIC_ISP, isp);
        });
    }

    static void writeLastError(String lastError, String visibleError, long sessionId, long dismissedSessionId) {
        updateProperties(properties -> {
            setNullable(properties, KEY_LAST_ERROR, lastError);
            setNullable(properties, KEY_VISIBLE_ERROR, visibleError);
            properties.setProperty(KEY_ERROR_SESSION, String.valueOf(sessionId));
            properties.setProperty(KEY_DISMISSED_ERROR_SESSION, String.valueOf(dismissedSessionId));
        });
    }

    static void writePublicIpRefreshing(boolean refreshing) {
        updateProperties(properties -> properties.setProperty(KEY_PUBLIC_IP_REFRESHING, String.valueOf(refreshing)));
    }

    static void writeCaptchaLockoutUntil(long deadlineElapsedMs) {
        updateProperties(properties ->
            properties.setProperty(KEY_CAPTCHA_LOCKOUT_UNTIL, String.valueOf(Math.max(0L, deadlineElapsedMs)))
        );
    }

    static void writeProtectSocketName(@Nullable String socketName) {
        updateProperties(properties -> setNullable(properties, KEY_PROTECT_SOCKET_NAME, socketName));
    }

    @Nullable
    static String readProtectSocketName() {
        return readSnapshot().protectSocketName;
    }

    static void resetEphemeralState() {
        updateProperties(properties -> {
            properties.setProperty(KEY_STATE, STATE_STOPPED);
            properties.remove(KEY_BACKEND_TYPE);
            properties.setProperty(KEY_RX, "0");
            properties.setProperty(KEY_TX, "0");
            properties.setProperty(KEY_RXPS, "0");
            properties.setProperty(KEY_TXPS, "0");
            properties.remove(KEY_PUBLIC_IP);
            properties.remove(KEY_PUBLIC_COUNTRY);
            properties.remove(KEY_PUBLIC_ISP);
            properties.remove(KEY_LAST_ERROR);
            properties.remove(KEY_VISIBLE_ERROR);
            properties.setProperty(KEY_ERROR_SESSION, "0");
            properties.setProperty(KEY_DISMISSED_ERROR_SESSION, "0");
            properties.setProperty(KEY_PUBLIC_IP_REFRESHING, "false");
            properties.setProperty(KEY_CAPTCHA_LOCKOUT_UNTIL, "0");
            properties.remove(KEY_PROTECT_SOCKET_NAME);
        });
    }

    static long getRuntimeLogVersion() {
        return readSnapshot().runtimeLogVersion;
    }

    static long getProxyLogVersion() {
        return readSnapshot().proxyLogVersion;
    }

    static String getRuntimeLogSnapshot() {
        return readLog(runtimeLogFile());
    }

    static String getProxyLogSnapshot() {
        return readLog(proxyLogFile());
    }

    static void appendRuntimeLog(String line) {
        appendLog(runtimeLogFile(), KEY_RUNTIME_LOG_VERSION, line);
    }

    static void appendProxyLog(String line) {
        appendLog(proxyLogFile(), KEY_PROXY_LOG_VERSION, line);
    }

    static void clearRuntimeLog() {
        clearLog(runtimeLogFile(), KEY_RUNTIME_LOG_VERSION);
    }

    static void clearProxyLog() {
        clearLog(proxyLogFile(), KEY_PROXY_LOG_VERSION);
    }

    private static synchronized void appendLog(File file, String versionKey, String line) {
        if (TextUtils.isEmpty(line) || appContext == null) {
            return;
        }
        trimLogIfNeeded(file);
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
        } catch (Exception ignored) {}
        updateProperties(properties ->
            properties.setProperty(versionKey, String.valueOf(readLong(properties, versionKey) + 1L))
        );
    }

    private static synchronized void clearLog(File file, String versionKey) {
        if (appContext == null) {
            return;
        }
        try (FileOutputStream ignored = new FileOutputStream(file, false)) {
            // Truncate only.
        } catch (Exception ignored) {}
        updateProperties(properties ->
            properties.setProperty(versionKey, String.valueOf(readLong(properties, versionKey) + 1L))
        );
    }

    private static String readLog(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read = input.read(buffer);
            while (read != -1) {
                output.write(buffer, 0, read);
                read = input.read(buffer);
            }
            return output.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void trimLogIfNeeded(File file) {
        if (file == null || !file.isFile() || file.length() <= MAX_LOG_BYTES) {
            return;
        }
        long keepBytes = Math.min(TRIMMED_LOG_BYTES, file.length());
        byte[] tail = new byte[(int) keepBytes];
        try (FileInputStream input = new FileInputStream(file)) {
            long skipBytes = Math.max(0L, file.length() - keepBytes);
            while (skipBytes > 0L) {
                long skipped = input.skip(skipBytes);
                if (skipped <= 0L) {
                    break;
                }
                skipBytes -= skipped;
            }
            int offset = 0;
            while (offset < tail.length) {
                int read = input.read(tail, offset, tail.length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
        } catch (Exception ignored) {
            return;
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(tail);
        } catch (Exception ignored) {}
    }

    private static synchronized void updateProperties(PropertyEditor editor) {
        if (appContext == null || editor == null) {
            return;
        }
        Properties properties = readProperties();
        editor.edit(properties);
        writeProperties(properties);
    }

    private static Properties readProperties() {
        Properties properties = new Properties();
        AtomicFile atomicFile = stateAtomicFile();
        if (atomicFile == null) {
            return properties;
        }
        try (FileInputStream input = atomicFile.openRead()) {
            properties.load(input);
        } catch (Exception ignored) {}
        return properties;
    }

    private static void writeProperties(Properties properties) {
        AtomicFile atomicFile = stateAtomicFile();
        if (atomicFile == null) {
            return;
        }
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            properties.store(output, null);
            atomicFile.finishWrite(output);
            cachedSnapshot = null;
            cachedSnapshotAtElapsedMs = 0L;
        } catch (Exception ignored) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
        }
    }

    private static File stateFile() {
        return appContext != null ? new File(appContext.getFilesDir(), STATE_FILE_NAME) : null;
    }

    private static AtomicFile stateAtomicFile() {
        File file = stateFile();
        return file != null ? new AtomicFile(file) : null;
    }

    private static File runtimeLogFile() {
        return appContext != null ? new File(appContext.getFilesDir(), RUNTIME_LOG_FILE_NAME) : null;
    }

    private static File proxyLogFile() {
        return appContext != null ? new File(appContext.getFilesDir(), PROXY_LOG_FILE_NAME) : null;
    }

    private static long readLong(Properties properties, String key) {
        try {
            return Long.parseLong(properties.getProperty(key, "0"));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean readBoolean(Properties properties, String key) {
        return Boolean.parseBoolean(properties.getProperty(key, "false"));
    }

    private static void setNullable(Properties properties, String key, String value) {
        if (TextUtils.isEmpty(value)) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
    }

    private static String emptyToNull(String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    private static String firstNonEmpty(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    @FunctionalInterface
    interface PropertyEditor {
        void edit(Properties properties);
    }

    static final class Snapshot {

        final String state;
        final String backendType;
        final long rxBytes;
        final long txBytes;
        final long rxBytesPerSecond;
        final long txBytesPerSecond;
        final String publicIp;
        final String publicCountry;
        final String publicIsp;
        final String lastError;
        final String visibleError;
        final long errorSessionId;
        final long dismissedErrorSessionId;
        final boolean publicIpRefreshing;
        final long captchaLockoutUntilElapsedMs;
        final long runtimeLogVersion;
        final long proxyLogVersion;
        final String protectSocketName;

        Snapshot(
            String state,
            String backendType,
            long rxBytes,
            long txBytes,
            long rxBytesPerSecond,
            long txBytesPerSecond,
            String publicIp,
            String publicCountry,
            String publicIsp,
            String lastError,
            String visibleError,
            long errorSessionId,
            long dismissedErrorSessionId,
            boolean publicIpRefreshing,
            long captchaLockoutUntilElapsedMs,
            long runtimeLogVersion,
            long proxyLogVersion,
            String protectSocketName
        ) {
            this.state = state;
            this.backendType = backendType;
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
            this.rxBytesPerSecond = rxBytesPerSecond;
            this.txBytesPerSecond = txBytesPerSecond;
            this.publicIp = publicIp;
            this.publicCountry = publicCountry;
            this.publicIsp = publicIsp;
            this.lastError = lastError;
            this.visibleError = visibleError;
            this.errorSessionId = errorSessionId;
            this.dismissedErrorSessionId = dismissedErrorSessionId;
            this.publicIpRefreshing = publicIpRefreshing;
            this.captchaLockoutUntilElapsedMs = captchaLockoutUntilElapsedMs;
            this.runtimeLogVersion = runtimeLogVersion;
            this.proxyLogVersion = proxyLogVersion;
            this.protectSocketName = protectSocketName;
        }
    }
}
