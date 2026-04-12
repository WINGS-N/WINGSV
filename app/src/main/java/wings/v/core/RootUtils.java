package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import com.wireguard.android.backend.WgQuickBackend;
import com.wireguard.android.util.RootShell;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.ConsecutiveAppendsShouldReuse",
        "PMD.ConsecutiveLiteralAppends",
        "PMD.SimplifyBooleanReturns",
        "PMD.SignatureDeclareThrowsException",
        "PMD.AvoidLiteralsInIfCondition",
        "PMD.InsufficientStringBufferDeclaration",
    }
)
public final class RootUtils {

    private static final String TAG = "WINGSV/RootUtils";
    private static final String ROOT_UID_MARKER = "__WINGSV_ROOT_UID__=";
    private static final String ROOT_CHECK_EXIT_MARKER = "__WINGSV_ROOT_CHECK_EXIT__=";
    private static final int MAX_DIRECT_SU_LOG_CHARS = 240;

    private RootUtils() {}

    public static boolean verifyRootAccess(Context context) {
        RootShell rootShell = new RootShell(context.getApplicationContext());
        try {
            rootShell.start();
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Interactive RootShell probe failed, falling back to direct su check", error);
            return verifyRootAccessDirect();
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

    public static boolean isRootModeSupported(Context context, BackendType backendType, boolean refreshAccess) {
        return refreshAccess ? refreshRootAccessState(context) : isRootAccessGranted(context);
    }

    public static String getRootModeUnavailableReason(Context context) {
        return getRootModeUnavailableReason(context, BackendType.VK_TURN_WIREGUARD, false);
    }

    public static String getRootModeUnavailableReason(Context context, boolean refreshAccess) {
        return getRootModeUnavailableReason(context, BackendType.VK_TURN_WIREGUARD, refreshAccess);
    }

    public static String getRootModeUnavailableReason(Context context, BackendType backendType, boolean refreshAccess) {
        boolean rootGranted = refreshAccess ? refreshRootAccessState(context) : isRootAccessGranted(context);
        if (!rootGranted) {
            return "Root-доступ не подтверждён";
        }
        return null;
    }

    public static boolean isKernelWireGuardSupported(Context context, BackendType backendType, boolean refreshAccess) {
        return TextUtils.isEmpty(getKernelWireGuardUnavailableReason(context, backendType, refreshAccess));
    }

    public static String getKernelWireGuardUnavailableReason(
        Context context,
        BackendType backendType,
        boolean refreshAccess
    ) {
        boolean rootGranted = refreshAccess ? refreshRootAccessState(context) : isRootAccessGranted(context);
        if (!rootGranted) {
            return "Root-доступ не подтверждён";
        }
        if (backendType == null || !backendType.supportsKernelWireGuard()) {
            return "Доступно только для WireGuard backend";
        }
        if (!WgQuickBackend.hasKernelSupport()) {
            return "Kernel WireGuard недоступен на этом устройстве";
        }
        return null;
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
        String packageCodePath = context.getApplicationInfo() != null ? context.getApplicationInfo().sourceDir : null;
        StringBuilder command = new StringBuilder();
        command
            .append("APK_PATH=$(cmd package path ")
            .append(shellQuote(context.getPackageName()))
            .append(" 2>/dev/null); ");
        command.append("APK_PATH=${APK_PATH#package:}; ");
        command.append("if [ ! -r \"$APK_PATH\" ]; then ");
        command.append("APK_PATH=$(pm path ").append(shellQuote(context.getPackageName())).append(" 2>/dev/null); ");
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

        Process process = new ProcessBuilder("su", "-c", command.toString()).redirectErrorStream(true).start();
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
            return rootShell.run(null, command) == 0;
        } catch (Exception ignored) {
            try {
                return runDirectRootCommand(command).exitCode == 0;
            } catch (Exception ignoredAgain) {
                return false;
            }
        } finally {
            rootShell.stop();
        }
    }

    private static boolean verifyRootAccessDirect() {
        try {
            DirectRootCommandResult result = runDirectRootCommand(buildDirectRootAccessProbeCommand());
            String uid = extractMarkerValue(result.output, ROOT_UID_MARKER);
            String commandExit = extractMarkerValue(result.output, ROOT_CHECK_EXIT_MARKER);
            boolean granted = result.exitCode == 0 && "0".equals(uid) && "0".equals(commandExit);
            if (!granted) {
                Log.w(
                    TAG,
                    "Direct su root probe failed: processExit=" +
                        result.exitCode +
                        ", uid=" +
                        safeLogValue(uid) +
                        ", commandExit=" +
                        safeLogValue(commandExit) +
                        ", output=" +
                        summarizeDirectSuOutput(result.output)
                );
            }
            return granted;
        } catch (Exception error) {
            Log.w(TAG, "Direct su root probe execution failed", error);
            return false;
        }
    }

    private static String buildDirectRootAccessProbeCommand() {
        return (
            "printf '\\n" +
            ROOT_UID_MARKER +
            "'; (/system/bin/id -u 2>/dev/null || id -u 2>/dev/null); " +
            "rc=$?; printf '\\n" +
            ROOT_CHECK_EXIT_MARKER +
            "%s\\n' \"$rc\""
        );
    }

    private static DirectRootCommandResult runDirectRootCommand(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        String output;
        try (InputStream inputStream = process.getInputStream()) {
            output = readFully(inputStream);
        }
        int exitCode = process.waitFor();
        return new DirectRootCommandResult(exitCode, output == null ? "" : output);
    }

    private static String extractMarkerValue(String output, String marker) {
        if (TextUtils.isEmpty(output) || TextUtils.isEmpty(marker)) {
            return null;
        }
        String[] lines = output.replace("\r", "").split("\n");
        for (String line : lines) {
            if (line.startsWith(marker)) {
                return line.substring(marker.length()).trim();
            }
        }
        return null;
    }

    private static String summarizeDirectSuOutput(String output) {
        if (TextUtils.isEmpty(output)) {
            return "<empty>";
        }
        String normalized = output.replace("\r", " ").replace("\n", " ").trim();
        if (normalized.length() <= MAX_DIRECT_SU_LOG_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_DIRECT_SU_LOG_CHARS) + "...";
    }

    private static String safeLogValue(String value) {
        return TextUtils.isEmpty(value) ? "<missing>" : value;
    }

    private static String readFully(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        read = inputStream.read(buffer);
        while (read != -1) {
            outputStream.write(buffer, 0, read);
            read = inputStream.read(buffer);
        }
        return outputStream.toString(StandardCharsets.UTF_8.name());
    }

    private static final class DirectRootCommandResult {

        private final int exitCode;
        private final String output;

        private DirectRootCommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
