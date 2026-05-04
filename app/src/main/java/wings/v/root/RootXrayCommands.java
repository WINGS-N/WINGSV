package wings.v.root;

import android.text.TextUtils;
import android.util.Base64;
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
 *     touched) can resolve the lib — app_process started with a bare
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

    private static final Object LOCK = new Object();
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
                    LibXray.stopXray();
                } catch (Exception ignored) {}
                synchronized (LOCK) {
                    stopRequested = true;
                    LOCK.notifyAll();
                }
            })
        );

        String resolvedDataDir = TextUtils.isEmpty(dataDir) ? "" : dataDir;
        String request = LibXray.newXrayRunFromJSONRequest(resolvedDataDir, "", configJson, 0);
        String response = LibXray.runXrayFromJSON(request);
        decodeResponse(response);

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

    private static void decodeResponse(String base64Response) throws Exception {
        if (TextUtils.isEmpty(base64Response)) {
            throw new IllegalStateException("libXray returned empty response");
        }
        byte[] decoded = Base64.decode(base64Response, Base64.DEFAULT);
        JSONObject response = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        if (!response.optBoolean("success", false)) {
            throw new IllegalStateException("libXray request failed: " + response.optString("error", "unknown"));
        }
    }
}
