package wings.v.root;

import android.text.TextUtils;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import libXray.LibXray;
import org.json.JSONObject;

/**
 * Boots the gomobile-bound Xray runtime (shipped in the AAR as the standard
 * {@code libgojni.so}) inside an app_process forked under {@code su} so the
 * Xray runtime inherits root capabilities (notably CAP_NET_ADMIN, which is
 * required for setsockopt(IP_TRANSPARENT) on the TPROXY listener).
 *
 * Lifecycle:
 *  1. Parent (WINGSV) writes config json to a private file, spawns this command
 *     via {@link wings.v.core.RootUtils#spawnRootHelperProcess(android.content.Context, String[])}.
 *  2. We inject the app's nativeLibraryDir into the system PathClassLoader's
 *     nativeLibraryDirectories so {@code System.loadLibrary("gojni")} (called
 *     from {@code go.Seq}'s static init the first time {@code LibXray} is
 *     touched) can resolve the lib - app_process started with a bare
 *     {@code CLASSPATH=base.apk} doesn't propagate the app's lib search path.
 *  3. {@code LibXray.runXrayFromJSON} kicks the runtime into goroutines and
 *     returns immediately. We park on a monitor until shutdown.
 *  4. Parent calls {@code Process.destroy()} (SIGTERM); the JVM shutdown hook
 *     calls {@code LibXray.stopXray()} for graceful drain.
 */
@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.SignatureDeclareThrowsException",
        "PMD.SystemPrintln",
        "PMD.AvoidUsingHardCodedIP",
        "PMD.CommentRequired",
        "PMD.AvoidPrintStackTrace",
        "PMD.AvoidSynchronizedStatement",
    }
)
final class RootXrayCommands {

    /** Версия контракта libXray. Библиотека отбивает запрос с чужой */
    private static final int LIBXRAY_API_VERSION = 1;

    private static final Object LOCK = new Object();
    private static final int API_SOCKET_WAIT_MS = 2000;
    private static final int API_SOCKET_POLL_MS = 50;
    private static volatile boolean stopRequested;

    private RootXrayCommands() {}

    static void handle(String[] args) throws Exception {
        String configPath = parseArg(args, "--config");
        String libDir = parseArg(args, "--lib-dir");
        String dataDir = parseArg(args, "--data-dir");

        if (TextUtils.isEmpty(configPath)) {
            throw new IllegalArgumentException("xray-tproxy: --config <path> required");
        }
        if (TextUtils.isEmpty(libDir)) {
            throw new IllegalArgumentException("xray-tproxy: --lib-dir <path> required");
        }

        injectNativeLibrarySearchPath(libDir);
        System.load(libDir + "/libgojni.so");

        String configJson = readFile(configPath);
        if (TextUtils.isEmpty(configJson)) {
            throw new IllegalStateException("xray-tproxy: config file is empty: " + configPath);
        }

        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> {
                try {
                    invoke("stopXray", null);
                } catch (Exception ignored) {}
                synchronized (LOCK) {
                    stopRequested = true;
                    LOCK.notifyAll();
                }
            })
        );

        String resolvedDataDir = TextUtils.isEmpty(dataDir) ? "" : dataDir;
        // Каталог ассетов и дескриптор - окружение процесса: ядро читает их,
        // пока поднимает inbound, а в запрос они больше не входят
        LibXray.setAssetDir(resolvedDataDir);
        LibXray.setTunFd(0);
        JSONObject payload = new JSONObject();
        payload.put("configJSON", configJson);
        invoke("runXrayFromJson", payload);

        handApiSocketToApp(
            parseArg(args, "--api-socket"),
            parseArg(args, "--api-peer-uid"),
            parseArg(args, "--api-peer-context")
        );

        System.out.println("PROXY_STATUS:ok");

        synchronized (LOCK) {
            while (!stopRequested) {
                try {
                    LOCK.wait();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Gives the app back the stats api socket Xray just bound.
     *
     * Xray runs as root here, so the socket it created is owned by uid 0 and carries the
     * bare app_data_file label without the app's MLS categories - the app would be
     * SELinux-denied connecting to its own socket. Xray's listener can only chmod, so the
     * chown and the relabel have to happen here. Nothing is fatal: losing this only costs
     * exact statistics, and the caller falls back to reconstructing them.
     *
     * Ownership and label ARE the authentication - Xray's gRPC api authenticates nothing
     * on its own, so a socket left owned by root and world-labelled would be an open
     * counter feed for every app on the device.
     */
    private static void handApiSocketToApp(String socketPath, String peerUid, String peerContext) {
        if (TextUtils.isEmpty(socketPath) || TextUtils.isEmpty(peerUid)) {
            return;
        }
        try {
            java.io.File socket = new java.io.File(socketPath);
            // runXrayFromJSON returns once the runtime is up, but the commander binds on
            // its own goroutine, so the socket may not have appeared yet.
            for (int waited = 0; !socket.exists() && waited < API_SOCKET_WAIT_MS; waited += API_SOCKET_POLL_MS) {
                Thread.sleep(API_SOCKET_POLL_MS);
            }
            if (!socket.exists()) {
                System.out.println("API_SOCKET:missing");
                return;
            }
            int uid = Integer.parseInt(peerUid);
            android.system.Os.chown(socketPath, uid, uid);
            if (!TextUtils.isEmpty(peerContext)) {
                android.system.Os.setxattr(
                    socketPath,
                    "security.selinux",
                    (peerContext + "\0").getBytes(StandardCharsets.UTF_8),
                    0
                );
            }
            System.out.println("API_SOCKET:ok");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            System.out.println("API_SOCKET:failed " + error.getMessage());
        }
    }

    /**
     * Adds {@code libDir} to the system PathClassLoader's native library
     * directories so {@code findLibrary("gojni")} (invoked from gomobile's
     * {@code go.Seq} static initializer) can resolve the path.
     */
    private static void injectNativeLibrarySearchPath(String libDir) {
        try {
            ClassLoader loader = RootXrayCommands.class.getClassLoader();
            if (loader == null) {
                return;
            }
            Class<?> baseDexClassLoader = Class.forName("dalvik.system.BaseDexClassLoader");
            if (!baseDexClassLoader.isInstance(loader)) {
                return;
            }
            Field pathListField = baseDexClassLoader.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            if (pathList == null) {
                return;
            }
            Method addNativePath = pathList.getClass().getDeclaredMethod("addNativePath", Collection.class);
            addNativePath.setAccessible(true);
            addNativePath.invoke(pathList, Collections.singletonList(libDir));
        } catch (Exception ignored) {}
    }

    private static String parseArg(String[] args, String name) {
        if (args == null) {
            return "";
        }
        for (int index = 0; index < args.length - 1; index++) {
            if (name.equals(args[index])) {
                return args[index + 1];
            }
        }
        return "";
    }

    private static String readFile(String path) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read = in.read(buffer);
            while (read > 0) {
                out.write(buffer, 0, read);
                read = in.read(buffer);
            }
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** Один вызов в libXray: имя метода и его данные едут в единственную дверь */
    private static void invoke(String method, JSONObject payload) throws Exception {
        JSONObject request = new JSONObject();
        request.put("apiVersion", LIBXRAY_API_VERSION);
        request.put("method", method);
        if (payload != null) {
            request.put("payload", payload);
        }
        String raw = LibXray.invoke(request.toString());
        if (TextUtils.isEmpty(raw)) {
            throw new IllegalStateException("libXray returned empty response");
        }
        JSONObject response = new JSONObject(raw);
        if (!response.optBoolean("success", false)) {
            throw new IllegalStateException("libXray request failed: " + response.optString("error", "unknown"));
        }
    }
}
