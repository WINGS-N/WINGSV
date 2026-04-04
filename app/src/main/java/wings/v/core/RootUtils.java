package wings.v.core;

import android.content.Context;
import android.text.TextUtils;

import com.wireguard.android.backend.WgQuickBackend;
import com.wireguard.android.util.RootShell;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class RootUtils {
    private RootUtils() {
    }

    public static boolean verifyRootAccess(Context context) {
        RootShell rootShell = new RootShell(context.getApplicationContext());
        try {
            rootShell.start();
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            rootShell.stop();
        }
    }

    public static boolean refreshRootAccessState(Context context) {
        boolean granted = verifyRootAccess(context);
        AppPrefs.setRootAccessGranted(context, granted);
        return granted;
    }

    public static boolean isRootAccessGranted(Context context) {
        return AppPrefs.isRootAccessGranted(context);
    }

    public static boolean isRootModeSupported(Context context) {
        return isRootModeSupported(context, BackendType.VK_TURN_WIREGUARD, false);
    }

    public static boolean isRootModeSupported(Context context, boolean refreshAccess) {
        return isRootModeSupported(context, BackendType.VK_TURN_WIREGUARD, refreshAccess);
    }

    public static boolean isRootModeSupported(Context context,
                                              BackendType backendType,
                                              boolean refreshAccess) {
        boolean rootGranted = refreshAccess ? refreshRootAccessState(context) : isRootAccessGranted(context);
        return rootGranted && isBackendRootCapable(backendType);
    }

    public static String getRootModeUnavailableReason(Context context) {
        return getRootModeUnavailableReason(context, BackendType.VK_TURN_WIREGUARD, false);
    }

    public static String getRootModeUnavailableReason(Context context, boolean refreshAccess) {
        return getRootModeUnavailableReason(context, BackendType.VK_TURN_WIREGUARD, refreshAccess);
    }

    public static String getRootModeUnavailableReason(Context context,
                                                      BackendType backendType,
                                                      boolean refreshAccess) {
        boolean rootGranted = refreshAccess ? refreshRootAccessState(context) : isRootAccessGranted(context);
        if (!rootGranted) {
            return "Root-доступ не подтверждён";
        }
        if (!isBackendRootCapable(backendType)) {
            return "Kernel WireGuard недоступен на этом устройстве";
        }
        return null;
    }

    private static boolean isBackendRootCapable(BackendType backendType) {
        if (backendType == BackendType.XRAY) {
            return true;
        }
        if (backendType == BackendType.AMNEZIAWG) {
            return false;
        }
        return WgQuickBackend.hasKernelSupport();
    }

    public static boolean isRootInterfaceAlive(Context context, String interfaceName) {
        if (TextUtils.isEmpty(interfaceName)) {
            return false;
        }
        return runRootCheck(context, "ip link show dev " + shellQuote(interfaceName) + " >/dev/null 2>&1");
    }

    public static boolean isRootProcessAlive(Context context, long pid) {
        if (pid <= 0L) {
            return false;
        }
        return runRootCheck(context, "kill -0 " + pid + " >/dev/null 2>&1");
    }

    public static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static String runRootHelper(Context context, String... args) throws Exception {
        String packageCodePath = context.getApplicationInfo() != null
                ? context.getApplicationInfo().sourceDir
                : null;
        StringBuilder command = new StringBuilder();
        command.append("APK_PATH=$(cmd package path ")
                .append(shellQuote(context.getPackageName()))
                .append(" 2>/dev/null); ");
        command.append("APK_PATH=${APK_PATH#package:}; ");
        command.append("if [ ! -r \"$APK_PATH\" ]; then ");
        command.append("APK_PATH=$(pm path ")
                .append(shellQuote(context.getPackageName()))
                .append(" 2>/dev/null); ");
        command.append("APK_PATH=${APK_PATH#package:}; ");
        command.append("fi; ");
        command.append("if [ ! -r \"$APK_PATH\" ]; then ");
        command.append("APK_PATH=").append(shellQuote(packageCodePath)).append("; ");
        command.append("fi; ");
        command.append("[ -r \"$APK_PATH\" ] || exit 127; ");
        command.append("CLASSPATH=\"$APK_PATH\" ");
        command.append("exec app_process /system/bin wings.v.root.RootCommandMain");
        if (args != null) {
            for (String argument : args) {
                command.append(' ').append(shellQuote(argument));
            }
        }

        Process process = new ProcessBuilder("su", "-c", command.toString())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream inputStream = process.getInputStream()) {
            output = readFully(inputStream);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    TextUtils.isEmpty(output) ? "Root helper exited with code " + exitCode : output.trim()
            );
        }
        return output == null ? "" : output.trim();
    }

    private static boolean runRootCheck(Context context, String command) {
        RootShell rootShell = new RootShell(context.getApplicationContext());
        try {
            rootShell.start();
            rootShell.run(null, command);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            rootShell.stop();
        }
    }

    private static String readFully(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toString(StandardCharsets.UTF_8);
    }
}
