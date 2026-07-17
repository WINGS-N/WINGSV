package wings.v.root;

import android.text.TextUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import wings.v.byedpi.ByeDpiNative;

/**
 * Runs the ByeDPI front proxy inside an app_process forked under {@code su}, mirroring
 * how {@link RootXrayCommands} runs Xray for the TPROXY path.
 *
 * <p>It has to be root, and that is not about capabilities it needs for itself. On the
 * TUN path ByeDPI's upstream dials escape the tunnel via VpnService.protect(fd); TPROXY
 * has no VpnService and no protect, so an in-process ByeDPI dials under the app's uid,
 * gets caught by our own mangle MARK rule, is TPROXYed straight back into Xray, and Xray
 * hands it to ByeDPI again - an infinite loop that presents as every connection being
 * reset. Under uid 0 the existing "-m owner --uid-owner 0 -j RETURN" exclusion lets its
 * dials out, which is exactly what the TPROXY router already assumes happens.
 *
 * <p>Lifecycle mirrors the Xray helper: jniStartProxy blocks for the life of the proxy,
 * so it runs on its own thread while main() parks; the parent kills the process with
 * SIGTERM and the shutdown hook stops the proxy.
 */
@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.SignatureDeclareThrowsException",
        "PMD.SystemPrintln",
        "PMD.CommentRequired",
        "PMD.AvoidSynchronizedStatement",
        "PMD.DoNotUseThreads",
    }
)
final class RootByeDpiCommands {

    private static final Object LOCK = new Object();
    private static volatile boolean stopRequested;

    private RootByeDpiCommands() {}

    static void handle(String[] args) throws Exception {
        String libDir = parseArg(args, "--lib-dir");
        if (TextUtils.isEmpty(libDir)) {
            throw new IllegalArgumentException("byedpi: --lib-dir <path> required");
        }
        List<String> proxyArgs = argsAfterSeparator(args);
        if (proxyArgs.isEmpty()) {
            throw new IllegalArgumentException("byedpi: proxy arguments required after --");
        }

        // app_process starts with a bare CLASSPATH and no library search path, so the
        // lib has to be loaded by absolute path before ByeDpiNative's static
        // System.loadLibrary("byedpi") runs - by then it is already loaded and the
        // injected path makes the lookup succeed anyway.
        injectNativeLibrarySearchPath(libDir);
        System.load(libDir + "/libbyedpi.so");

        ByeDpiNative proxy = new ByeDpiNative();
        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> {
                try {
                    proxy.stopProxy();
                } catch (Exception ignored) {}
                synchronized (LOCK) {
                    stopRequested = true;
                    LOCK.notifyAll();
                }
            })
        );

        Thread worker = new Thread(
            () -> {
                int exitCode = proxy.startProxy(proxyArgs.toArray(new String[0]));
                // Only interesting when it quits on its own; on SIGTERM the hook got here
                // first and the parent already knows.
                if (!stopRequested) {
                    System.out.println("BYEDPI_STATUS:exited " + exitCode);
                }
                synchronized (LOCK) {
                    stopRequested = true;
                    LOCK.notifyAll();
                }
            },
            "wingsv-byedpi"
        );
        worker.setDaemon(true);
        worker.start();

        // The parent decides readiness by probing the listen port, exactly as it does
        // for the in-process proxy, so this is a breadcrumb rather than a handshake.
        System.out.println("BYEDPI_STATUS:started");

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

    /** Everything after a bare "--" is ByeDPI's own command line, passed through as is. */
    private static List<String> argsAfterSeparator(String[] args) {
        List<String> result = new ArrayList<>();
        boolean collecting = false;
        for (String arg : args) {
            if (collecting) {
                result.add(arg);
            } else if ("--".equals(arg)) {
                collecting = true;
            }
        }
        return result;
    }

    private static void injectNativeLibrarySearchPath(String libDir) {
        try {
            ClassLoader loader = RootByeDpiCommands.class.getClassLoader();
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
}
