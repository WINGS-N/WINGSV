package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import com.wireguard.android.backend.WgQuickBackend;
import com.wireguard.android.util.RootShell;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    private static final String ROOT_PROBE_COMMAND =
        "uid=$(/system/bin/id -u 2>/dev/null || id -u 2>/dev/null); printf '%s' \"$uid\"";
    private static final String ROOT_UID_ZERO_CHECK_COMMAND =
        "uid=$(/system/bin/id -u 2>/dev/null || id -u 2>/dev/null); [ \"$uid\" = \"0\" ]";
    private static final int MAX_DIRECT_SU_LOG_CHARS = 240;
    private static final int ROOT_PROBE_ATTEMPTS = 6;
    // Kitsune/KernelSU/Apatch often показывают grant-диалог пользователю на
    // первом probe — 450 мс не хватало докрутить «Allow». Делаем первое
    // ожидание длинным (≈12с до полной серии retry), последующие быстрые
    // — если уже разрешили, мгновенно поймаем uid=0 на следующей итерации.
    private static final long[] ROOT_PROBE_RETRY_DELAYS_MS = { 500L, 1_500L, 2_500L, 3_500L, 4_000L };

    private RootUtils() {}

    public static boolean verifyRootAccess(Context context) {
        for (int attempt = 1; attempt <= ROOT_PROBE_ATTEMPTS; attempt++) {
            if (verifyRootAccessOnce(context, attempt)) {
                return true;
            }
            if (attempt < ROOT_PROBE_ATTEMPTS) {
                sleepBeforeRootRetry(attempt);
            }
        }
        // Все probe'ы провалились — стерем кэш пути su, чтобы при следующей
        // успешной проверке он переопределился актуальным значением, а не
        // указывал на binary, который OS отозвал.
        AppPrefs.setRootSuPath(context, "");
        // Все стратегии провалились — собираем диагностический отпечаток в
        // runtime-лог, чтобы юзер мог скинуть и понять почему `su` exec не
        // работает (binary не найден, exec denied SELinux'ом, magisk daemon
        // не отвечает и т.п.). Без этого приходится гадать на Kitsune/KernelSU.
        try {
            String diagnostic = collectRootDiagnostics();
            wings.v.service.ProxyTunnelService.writeRuntimeLogLine("Root probe diagnostics:\n" + diagnostic);
        } catch (Exception ignored) {}
        return false;
    }

    private static String collectRootDiagnostics() {
        StringBuilder out = new StringBuilder();
        out.append("PATH=").append(System.getenv("PATH")).append('\n');
        String[] paths = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/sbin/.magisk/busybox/su",
            "/debug_ramdisk/su",
            "/data/adb/magisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/apatch/bin/su",
            "/odm/bin/su",
            "/vendor/bin/su",
        };
        for (String p : paths) {
            java.io.File f = new java.io.File(p);
            out
                .append("  ")
                .append(p)
                .append(": exists=")
                .append(f.exists())
                .append(" canExecute=")
                .append(f.canExecute())
                .append('\n');
        }
        try {
            DirectRootCommandResult sh = runDirectRootCommand(
                new String[] {
                    "sh",
                    "-c",
                    "command -v su 2>/dev/null; type su 2>&1; ls -laZ /system/bin/su /system/xbin/su 2>&1 | head -5",
                }
            );
            out
                .append("  shell-which su (exit=")
                .append(sh.exitCode)
                .append("): ")
                .append(summarizeDirectSuOutput(sh.output))
                .append('\n');
        } catch (Exception ignored) {}
        try {
            DirectRootCommandResult run = runDirectRootCommand(new String[] { "sh", "-c", "su -c 'id -u' 2>&1" });
            out
                .append("  sh -c \"su -c id -u\" (exit=")
                .append(run.exitCode)
                .append("): ")
                .append(summarizeDirectSuOutput(run.output));
        } catch (Exception error) {
            out.append("  sh -c su exec error: ").append(error.getMessage());
        }
        return out.toString();
    }

    private static boolean verifyRootAccessOnce(Context context, int attempt) {
        // Лёгкие direct probe'ы идут первыми — они не запускают magisk-sqlite
        // policy update в preamble (как делает RootShell), который ломается
        // на Kitsune Magisk и других не-Topjohnwu форках.
        if (verifyRootAccessDirect()) {
            Log.i(TAG, "Root access confirmed via direct su probe on attempt " + attempt);
            AppPrefs.setRootSuPath(context, "su");
            return true;
        }
        String fallbackPath = verifyRootAccessFallbackMatrix();
        if (fallbackPath != null) {
            Log.i(TAG, "Root access confirmed via fallback probe (" + fallbackPath + ") on attempt " + attempt);
            AppPrefs.setRootSuPath(context, fallbackPath);
            return true;
        }
        if (verifyRootAccessViaHelper(context)) {
            Log.i(TAG, "Root access confirmed via helper probe on attempt " + attempt);
            // helper использует "su" из PATH — кэшируем дефолт.
            if (TextUtils.isEmpty(AppPrefs.getRootSuPath(context))) {
                AppPrefs.setRootSuPath(context, "su");
            }
            return true;
        }
        // Interactive RootShell оставляем последним — он содержит preamble с
        // `magisk --sqlite "UPDATE policies ..."`, который не работает на
        // Kitsune/KernelSU/Apatch. Если предыдущие probe'ы уже подтвердили
        // root, до сюда не дойдём — а если форк не Magisk-совместимый,
        // эта попытка просто провалится тихо.
        if (verifyRootAccessInteractive(context)) {
            Log.i(TAG, "Root access confirmed via interactive probe on attempt " + attempt);
            if (TextUtils.isEmpty(AppPrefs.getRootSuPath(context))) {
                AppPrefs.setRootSuPath(context, "su");
            }
            return true;
        }
        return false;
    }

    private static void sleepBeforeRootRetry(int attempt) {
        long delayMs = ROOT_PROBE_RETRY_DELAYS_MS[Math.min(attempt - 1, ROOT_PROBE_RETRY_DELAYS_MS.length - 1)];
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while waiting before root probe retry " + attempt, error);
        }
    }

    private static boolean verifyRootAccessInteractive(Context context) {
        RootShell rootShell = new RootShell(context.getApplicationContext());
        try {
            rootShell.start();
            int exitCode = rootShell.run(null, ROOT_UID_ZERO_CHECK_COMMAND);
            if (exitCode == 0) {
                return true;
            }
            Log.w(TAG, "Interactive RootShell probe returned non-root exit code: " + exitCode);
            return false;
        } catch (Exception error) {
            Log.w(TAG, "Interactive RootShell probe failed", error);
            return false;
        } finally {
            rootShell.stop();
        }
    }

    public static boolean refreshRootAccessState(Context context) {
        boolean wasGranted = AppPrefs.isRootAccessGranted(context);
        boolean granted = verifyRootAccess(context);
        AppPrefs.setRootAccessGranted(context, granted);
        if (wasGranted && !granted) {
            handleRootRevoked(context);
        }
        return granted;
    }

    /**
     * Лёгкий single-attempt re-probe для периодического опроса. Без длинных
     * retry-задержек — полная серия из {@link #verifyRootAccess(Context)}
     * сжигала бы по 12с каждые 30с в фоне. Применяется в MainActivity для
     * детекта revoke, пока пользователь сидит в приложении.
     */
    public static boolean quickRefreshRootAccessState(Context context) {
        boolean wasGranted = AppPrefs.isRootAccessGranted(context);
        boolean granted = verifyRootAccessOnce(context, 1);
        AppPrefs.setRootAccessGranted(context, granted);
        if (wasGranted && !granted) {
            // Перед тем как объявить revoke — даём ОС ещё одну попытку
            // (Magisk daemon мог временно зависнуть). Если повторная проба
            // тоже false, считаем что грант реально отозван.
            try {
                Thread.sleep(800L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            granted = verifyRootAccessOnce(context, 2);
            AppPrefs.setRootAccessGranted(context, granted);
            if (!granted) {
                handleRootRevoked(context);
            }
        }
        return granted;
    }

    /**
     * Полный sync UI/runtime после того как OS отозвал root (Magisk/Kitsune
     * пользователь снёс или нажал Deny на следующей elevation): мастер-тогл
     * выключаем, runtime root-state стираем, активному сервису просим
     * переподключиться без root-функционала. Settings/MainActivity подхватят
     * KEY_ROOT_MODE через свой OnSharedPreferenceChangeListener / периодический
     * syncNavigationState — отдельно дёргать их не нужно.
     */
    private static void handleRootRevoked(Context context) {
        Log.w(TAG, "Root access was revoked — disabling root-mode toggle and clearing runtime state");
        AppPrefs.setRootModeEnabled(context, false);
        AppPrefs.setRootSuPath(context, "");
        AppPrefs.clearRootRuntimeState(context);
        try {
            if (wings.v.service.ProxyTunnelService.isActive()) {
                wings.v.service.ProxyTunnelService.requestReconnect(
                    context.getApplicationContext(),
                    "Root access revoked"
                );
            }
        } catch (Exception ignored) {}
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
        if (rootGranted) {
            return null;
        }
        // Cache says no root, but on Kitsune/KernelSU/Apatch our up-front probes
        // (WG RootShell preamble, direct su exec from background thread) often
        // can't see root even when it's actually granted. Last-chance live
        // check: invoke the root helper as we'd do for real operations. If it
        // returns uid=0, root is functional — update the cache and continue.
        // We only run this when caller asked for cached lookup; if refreshAccess
        // was true, verifyRootAccess already tried the helper-probe path.
        if (!refreshAccess && verifyRootAccessViaHelper(context)) {
            AppPrefs.setRootAccessGranted(context, true);
            return null;
        }
        return "Root-доступ не подтверждён";
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

    public static boolean isXrayTproxySupported(Context context, boolean refreshAccess) {
        return TextUtils.isEmpty(getXrayTproxyUnavailableReason(context, refreshAccess));
    }

    public static String getXrayTproxyUnavailableReason(Context context, boolean refreshAccess) {
        boolean rootGranted = refreshAccess ? refreshRootAccessState(context) : isRootAccessGranted(context);
        if (!rootGranted) {
            return "Root-доступ не подтверждён";
        }
        if (!runRootCheck(context, "grep -qE '(^| )TPROXY( |$)' /proc/net/ip_tables_targets 2>/dev/null")) {
            return "Kernel-модуль xt_TPROXY не загружен";
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
        Process process = new ProcessBuilder(resolveSuBinary(context), "-c", buildRootHelperShellCommand(context, args))
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

    /**
     * Spawns a long-running root helper subprocess (via {@code su} + {@code app_process}) and
     * returns the {@link Process} handle without waiting for completion. Caller is responsible
     * for reading stdout/stderr and calling {@link Process#destroy()} on shutdown.
     */
    public static Process spawnRootHelperProcess(Context context, String... args) throws Exception {
        return new ProcessBuilder(resolveSuBinary(context), "-c", buildRootHelperShellCommand(context, args))
            .redirectErrorStream(true)
            .start();
    }

    /**
     * Возвращает путь к работающему su из кэша probe'ов. Дефолт {@code "su"}
     * (PATH lookup) — на Topjohnwu Magisk достаточно. На Kitsune Magisk probe
     * запоминает {@code /debug_ramdisk/su} или другой реальный путь.
     */
    private static String resolveSuBinary(Context context) {
        String cached = AppPrefs.getRootSuPath(context);
        return TextUtils.isEmpty(cached) ? "su" : cached;
    }

    private static String buildRootHelperShellCommand(Context context, String... args) {
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
        return command.toString();
    }

    private static boolean runRootCheck(Context context, String command) {
        RootShell rootShell = new RootShell(context.getApplicationContext());
        try {
            rootShell.start();
            return rootShell.run(null, command) == 0;
        } catch (Exception ignored) {
            try {
                return runDirectRootCommand(new String[] { resolveSuBinary(context), "-c", command }).exitCode == 0;
            } catch (Exception ignoredAgain) {
                return false;
            }
        } finally {
            rootShell.stop();
        }
    }

    private static boolean verifyRootAccessDirect() {
        try {
            DirectRootCommandResult result = runDirectRootCommand(
                new String[] { "su", "-c", buildDirectRootAccessProbeCommand() }
            );
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

    private static boolean verifyRootAccessViaHelper(Context context) {
        try {
            String output = runRootHelper(context, "shell", "id", "-u");
            String uid = trim(output);
            boolean granted = "0".equals(uid);
            if (!granted) {
                Log.w(TAG, "Root helper probe returned non-root uid: " + safeLogValue(uid));
            }
            return granted;
        } catch (Exception error) {
            Log.w(TAG, "Root helper probe failed", error);
            return false;
        }
    }

    /**
     * Возвращает путь к работающему su-binary (или {@code "su"} если уже на PATH),
     * либо {@code null} если ни один пробный вызов не дал uid=0. Этот же путь потом
     * используется в {@link #runRootHelper(Context, String...)} —
     * без него Kitsune Magisk: probe находит su в /debug_ramdisk, но реальный
     * exec через PATH "su" падает с ENOENT.
     */
    private static String verifyRootAccessFallbackMatrix() {
        List<FallbackProbe> commandMatrix = new ArrayList<>();
        // Shell-mediated PATH lookup — ловит варианты, когда наш ProcessBuilder
        // не находит su через свой PATH, но shell его видит (Kitsune Magisk и
        // некоторые KernelSU-установки кладут su в нестандартное место).
        commandMatrix.add(
            new FallbackProbe("su", new String[] { "sh", "-c", "su -c " + shellQuote(ROOT_PROBE_COMMAND) })
        );
        commandMatrix.add(
            new FallbackProbe("su", new String[] { "/system/bin/sh", "-c", "su -c " + shellQuote(ROOT_PROBE_COMMAND) })
        );
        commandMatrix.add(new FallbackProbe("su", new String[] { "su", "-c", ROOT_PROBE_COMMAND }));
        commandMatrix.add(new FallbackProbe("su", new String[] { "su", "0", "sh", "-c", ROOT_PROBE_COMMAND }));
        commandMatrix.add(new FallbackProbe("su", new String[] { "su", "root", "sh", "-c", ROOT_PROBE_COMMAND }));
        // Прямые пути ко всем известным местам где может лежать su на разных
        // root-менеджерах (Topjohnwu Magisk / Kitsune / KernelSU / APatch /
        // SuperSU legacy).
        for (String suPath : KNOWN_SU_PATHS) {
            commandMatrix.add(new FallbackProbe(suPath, new String[] { suPath, "-c", ROOT_PROBE_COMMAND }));
        }

        for (FallbackProbe probe : commandMatrix) {
            try {
                DirectRootCommandResult result = runDirectRootCommand(probe.command);
                String uid = trim(result.output);
                if (result.exitCode == 0 && "0".equals(uid)) {
                    Log.i(TAG, "Fallback root probe succeeded via " + Arrays.toString(probe.command));
                    return probe.suPath;
                }
                Log.w(
                    TAG,
                    "Fallback root probe returned non-root result via " +
                        Arrays.toString(probe.command) +
                        ": exit=" +
                        result.exitCode +
                        ", output=" +
                        summarizeDirectSuOutput(result.output)
                );
            } catch (Exception error) {
                Log.w(TAG, "Fallback root probe failed via " + Arrays.toString(probe.command), error);
            }
        }
        return null;
    }

    private static final String[] KNOWN_SU_PATHS = {
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/sbin/.magisk/busybox/su",
        // /debug_ramdisk/su — Kitsune Magisk на некоторых прошивках кладёт su
        // именно сюда (debug_ramdisk монтируется ранним init'ом).
        "/debug_ramdisk/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/apatch/bin/su",
        "/odm/bin/su",
        "/vendor/bin/su",
    };

    private static final class FallbackProbe {

        private final String suPath;
        private final String[] command;

        private FallbackProbe(String suPath, String[] command) {
            this.suPath = suPath;
            this.command = command;
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

    private static DirectRootCommandResult runDirectRootCommand(String[] command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream inputStream = process.getInputStream()) {
            output = readFully(inputStream);
        }
        int exitCode = process.waitFor();
        return new DirectRootCommandResult(exitCode, output == null ? "" : output);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
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
