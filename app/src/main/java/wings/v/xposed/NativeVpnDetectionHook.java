package wings.v.xposed;

import android.os.Build;
import android.util.Log;
import de.robv.android.xposed.XposedBridge;
import java.io.File;

@SuppressWarnings(
    {
        "PMD.AvoidSynchronizedAtMethodLevel",
        "PMD.AvoidCatchingGenericException",
        "PMD.AvoidUsingNativeCode",
        "PMD.UseProperClassLoader",
    }
)
final class NativeVpnDetectionHook {

    private static final String LOG_TAG = "WINGS-Xposed";
    private static boolean attempted;
    private static boolean installed;

    private NativeVpnDetectionHook() {}

    static synchronized boolean install() {
        if (attempted) {
            return installed;
        }
        attempted = true;
        try {
            loadNativeLibraries();
            installed = nativeInstall();
            Log.i(LOG_TAG, "native install result=" + installed);
        } catch (Throwable throwable) {
            Log.w(LOG_TAG, "native hook failed", throwable);
            XposedBridge.log("WINGS V native Xposed hook failed: " + throwable);
            installed = false;
        }
        return installed;
    }

    static synchronized void refresh() {
        if (!installed) {
            return;
        }
        try {
            nativeRefresh();
        } catch (Throwable throwable) {
            XposedBridge.log("WINGS V native Xposed hook refresh failed: " + throwable);
        }
    }

    private static void loadNativeLibraries() {
        try {
            try {
                System.loadLibrary("shadowhook");
            } catch (UnsatisfiedLinkError ignored) {}
            System.loadLibrary("wingsxposednative");
            return;
        } catch (UnsatisfiedLinkError ignored) {}

        File nativeDir = resolveModuleNativeLibraryDir();
        if (nativeDir == null) {
            throw new UnsatisfiedLinkError("WINGS V module native library dir not found");
        }
        File shadowhookSo = new File(nativeDir, "libshadowhook.so");
        if (shadowhookSo.exists()) {
            try {
                System.load(shadowhookSo.getAbsolutePath());
            } catch (UnsatisfiedLinkError ignored) {}
        }
        System.load(new File(nativeDir, "libwingsxposednative.so").getAbsolutePath());
    }

    private static File resolveModuleNativeLibraryDir() {
        String moduleApkPath = resolveModuleApkPath();
        if (moduleApkPath == null || moduleApkPath.isEmpty()) {
            return null;
        }

        File moduleDir = new File(moduleApkPath).getParentFile();
        if (moduleDir == null) {
            return null;
        }

        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        String libDirName;
        if (abi.startsWith("arm64")) {
            libDirName = "arm64";
        } else if (abi.startsWith("armeabi")) {
            libDirName = "arm";
        } else {
            libDirName = abi;
        }

        File nativeDir = new File(new File(moduleDir, "lib"), libDirName);
        return nativeDir.isDirectory() ? nativeDir : null;
    }

    private static String resolveModuleApkPath() {
        ClassLoader classLoader = NativeVpnDetectionHook.class.getClassLoader();
        String description = classLoader != null ? classLoader.toString() : "";
        String marker = "module=";
        int start = description.indexOf(marker);
        if (start >= 0) {
            start += marker.length();
            int end = description.indexOf(',', start);
            if (end < 0) {
                end = description.indexOf(']', start);
            }
            if (end > start) {
                String path = description.substring(start, end).trim();
                if (path.endsWith(".apk")) {
                    return path;
                }
            }
        }

        String resourcePath =
            NativeVpnDetectionHook.class.getResource("NativeVpnDetectionHook.class") != null
                ? NativeVpnDetectionHook.class.getResource("NativeVpnDetectionHook.class").toString()
                : "";
        int fileStart = resourcePath.indexOf("file:");
        int apkEnd = resourcePath.indexOf(".apk");
        if (fileStart >= 0 && apkEnd > fileStart) {
            return resourcePath.substring(fileStart + "file:".length(), apkEnd + ".apk".length());
        }
        return null;
    }

    private static native boolean nativeInstall();

    private static native void nativeRefresh();
}
