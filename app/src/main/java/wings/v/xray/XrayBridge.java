package wings.v.xray;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Base64;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import libXray.DialerController;
import libXray.LibXray;
import org.json.JSONObject;
import wings.v.service.XrayVpnService;

@SuppressWarnings(
    {
        "PMD.SignatureDeclareThrowsException",
        "PMD.AvoidSynchronizedStatement",
        "PMD.AvoidSynchronizedAtMethodLevel",
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.GodClass",
        "PMD.TooManyMethods",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.ShortVariable",
        "PMD.OnlyOneReturn",
        "PMD.ConfusingTernary",
        "PMD.AvoidDuplicateLiterals",
        "PMD.UselessParentheses",
    }
)
public final class XrayBridge {

    private static final AtomicBoolean LOADED = new AtomicBoolean();
    private static final AtomicBoolean RUNTIME_STARTED = new AtomicBoolean();
    private static final AtomicBoolean CONTROLLERS_REGISTERED = new AtomicBoolean();
    private static final Object JNI_LOCK = new Object();
    private static final DialerController DIRECT_NETWORK_CONTROLLER = new DialerController() {
        @Override
        public boolean protectFd(long fd) {
            return true;
        }
    };
    private static final AtomicReference<DialerController> ACTIVE_NETWORK_CONTROLLER = new AtomicReference<>(
        DIRECT_NETWORK_CONTROLLER
    );
    private static final DialerController DELEGATING_CONTROLLER = new DialerController() {
        @Override
        public boolean protectFd(long fd) {
            DialerController activeController = ACTIVE_NETWORK_CONTROLLER.get();
            if (activeController instanceof XrayVpnService) {
                XrayVpnService vpnService = (XrayVpnService) activeController;
                if (!vpnService.canProtectSockets()) {
                    return false;
                }
            }
            try {
                return activeController == null || activeController.protectFd(fd);
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    };

    private XrayBridge() {}

    public static synchronized void prepareRuntime(XrayVpnService vpnService, String remoteDns, String directDns) {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            if (vpnService == null) {
                throw new IllegalStateException("Xray VpnService не готов");
            }
            ACTIVE_NETWORK_CONTROLLER.set(vpnService);
            configureRuntimeNetworkingLocked(remoteDns, directDns);
        }
    }

    public static synchronized void prepareRuntimeDirect(String remoteDns, String directDns) {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            ACTIVE_NETWORK_CONTROLLER.set(DIRECT_NETWORK_CONTROLLER);
            configureRuntimeNetworkingLocked(remoteDns, directDns);
        }
    }

    public static synchronized void prepareRuntimeViaProtectSocket(
        String socketName,
        String remoteDns,
        String directDns
    ) {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            if (TextUtils.isEmpty(socketName)) {
                throw new IllegalArgumentException("Protect socket name is empty");
            }
            ACTIVE_NETWORK_CONTROLLER.set(new ProtectBridgeDialerController(socketName));
            configureRuntimeNetworkingLocked(remoteDns, directDns);
        }
    }

    public static String convertShareLinkToOutboundJson(String rawLink) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            String request = Base64.encodeToString(rawLink.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            JSONObject response = decodeResponse(LibXray.convertShareLinksToXrayJson(request));
            Object data = response.opt("data");
            if (data instanceof JSONObject) {
                return ((JSONObject) data).toString();
            }
            if (data != null) {
                return String.valueOf(data);
            }
            throw new IllegalStateException("libXray вернул пустой outbound config");
        }
    }

    public static String convertXrayJsonToShareLinks(String configJson) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            String request = Base64.encodeToString(configJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            JSONObject response = decodeResponse(LibXray.convertXrayJsonToShareLinks(request));
            Object data = response.opt("data");
            if (data != null) {
                return String.valueOf(data);
            }
            throw new IllegalStateException("libXray вернул пустые share links");
        }
    }

    public static void runFromJson(Context context, String configJson, int tunFd) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            RUNTIME_STARTED.set(false);
            File datDir = ensureDatDir(context);
            String request = LibXray.newXrayRunFromJSONRequest(datDir.getAbsolutePath(), "", configJson, tunFd);
            decodeResponse(LibXray.runXrayFromJSON(request));
            RUNTIME_STARTED.set(true);
        }
    }

    public static long pingConfig(Context context, File configFile, int timeoutSeconds, String url, String proxy)
        throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            File datDir = ensureDatDir(context);
            JSONObject request = new JSONObject();
            request.put("datDir", datDir.getAbsolutePath());
            request.put("configPath", configFile.getAbsolutePath());
            request.put("timeout", timeoutSeconds);
            request.put("url", url);
            request.put("proxy", proxy);
            String encodedRequest = Base64.encodeToString(
                request.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            );
            JSONObject response = decodeResponse(LibXray.ping(encodedRequest));
            return response.optLong("data", 0L);
        }
    }

    public static void countGeoData(Context context, String name, String geoType) throws Exception {
        countGeoData(ensureDatDir(context), name, geoType);
    }

    public static void countGeoData(File datDir, String name, String geoType) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            JSONObject request = new JSONObject();
            request.put("datDir", datDir.getAbsolutePath());
            request.put("name", trim(name));
            request.put("geoType", trim(geoType));
            String encodedRequest = Base64.encodeToString(
                request.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            );
            decodeResponse(LibXray.countGeoData(encodedRequest));
        }
    }

    public static JSONObject readGeoFiles(String configJson) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            String request = Base64.encodeToString(configJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            return decodeResponse(LibXray.readGeoFiles(request)).optJSONObject("data");
        }
    }

    public static void stop() throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            try {
                if (RUNTIME_STARTED.get()) {
                    decodeResponse(LibXray.stopXray());
                }
            } finally {
                RUNTIME_STARTED.set(false);
                ACTIVE_NETWORK_CONTROLLER.set(DIRECT_NETWORK_CONTROLLER);
                LibXray.resetDns();
            }
        }
    }

    public static void detachVpnService(XrayVpnService vpnService) {
        if (vpnService == null) {
            return;
        }
        ACTIVE_NETWORK_CONTROLLER.compareAndSet(vpnService, DIRECT_NETWORK_CONTROLLER);
    }

    public static boolean isRunning() {
        ensureLoaded();
        return RUNTIME_STARTED.get();
    }

    public static boolean usesCachedStateFallback() {
        return true;
    }

    private static File ensureDatDir(Context context) {
        File datDir = new File(context.getFilesDir(), "xray/geo");
        if (!datDir.exists()) {
            datDir.mkdirs();
        }
        migrateLegacyGeoFiles(context, datDir);
        return datDir;
    }

    private static void migrateLegacyGeoFiles(Context context, File targetDir) {
        File legacyDir = new File(context.getFilesDir(), "xray/dat");
        copyGeoFileIfNeeded(new File(legacyDir, "geoip.dat"), new File(targetDir, "geoip.dat"));
        copyGeoFileIfNeeded(new File(legacyDir, "geosite.dat"), new File(targetDir, "geosite.dat"));
        copyGeoFileIfNeeded(new File(legacyDir, "geoip.json"), new File(targetDir, "geoip.json"));
        copyGeoFileIfNeeded(new File(legacyDir, "geosite.json"), new File(targetDir, "geosite.json"));
    }

    private static void copyGeoFileIfNeeded(File source, File target) {
        if (source == null || target == null || !source.exists() || target.exists()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (
            FileInputStream inputStream = new FileInputStream(source);
            FileOutputStream outputStream = new FileOutputStream(target, false)
        ) {
            byte[] buffer = new byte[8192];
            int read = inputStream.read(buffer);
            while (read >= 0) {
                if (read == 0) {
                    read = inputStream.read(buffer);
                    continue;
                }
                outputStream.write(buffer, 0, read);
                read = inputStream.read(buffer);
            }
        } catch (Exception ignored) {
            // best-effort migration
        }
    }

    private static void ensureLoaded() {
        if (LOADED.compareAndSet(false, true)) {
            LibXray.touch();
        }
    }

    private static void ensureControllersRegisteredLocked() {
        if (CONTROLLERS_REGISTERED.compareAndSet(false, true)) {
            LibXray.registerDialerController(DELEGATING_CONTROLLER);
            LibXray.registerListenerController(DELEGATING_CONTROLLER);
        }
    }

    private static void configureRuntimeNetworkingLocked(String remoteDns, String directDns) {
        ensureControllersRegisteredLocked();
        String runtimeDns = resolveBootstrapDnsDialTarget(remoteDns, directDns);
        if (!TextUtils.isEmpty(runtimeDns)) {
            LibXray.initDns(DELEGATING_CONTROLLER, runtimeDns);
        } else {
            LibXray.resetDns();
        }
    }

    private static String resolveBootstrapDnsDialTarget(String remoteDns, String directDns) {
        String candidate = normalizePlainDnsDialTarget(remoteDns);
        if (!TextUtils.isEmpty(candidate)) {
            return candidate;
        }
        candidate = normalizePlainDnsDialTarget(directDns);
        if (!TextUtils.isEmpty(candidate)) {
            return candidate;
        }
        candidate = normalizeDnsUrlBootstrapTarget(remoteDns);
        if (!TextUtils.isEmpty(candidate)) {
            return candidate;
        }
        candidate = normalizeDnsUrlBootstrapTarget(directDns);
        if (!TextUtils.isEmpty(candidate)) {
            return candidate;
        }
        return "1.1.1.1:53";
    }

    private static String normalizePlainDnsDialTarget(String value) {
        String normalized = trim(value);
        if (TextUtils.isEmpty(normalized)) {
            return "";
        }
        if (looksLikeDnsUrl(normalized)) {
            return "";
        }
        if (normalized.startsWith("[")) {
            return normalized.contains("]:") ? normalized : normalized + ":53";
        }
        int firstColon = normalized.indexOf(':');
        int lastColon = normalized.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            String portCandidate = normalized.substring(lastColon + 1);
            if (isDigits(portCandidate)) {
                return normalized;
            }
            return normalized + ":53";
        }
        if (firstColon != lastColon) {
            return "[" + normalized + "]:53";
        }
        return normalized + ":53";
    }

    private static String normalizeDnsUrlBootstrapTarget(String value) {
        String normalized = trim(value);
        if (!looksLikeDnsUrl(normalized)) {
            return "";
        }
        try {
            Uri uri = Uri.parse(normalized);
            String host = trim(uri.getHost());
            if (TextUtils.isEmpty(host)) {
                return "";
            }
            if (!looksLikeIpLiteral(host)) {
                return "";
            }
            if (host.contains(":")) {
                return "[" + host + "]:53";
            }
            return host + ":53";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean looksLikeDnsUrl(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return (
            normalized.startsWith("https://") ||
            normalized.startsWith("tls://") ||
            normalized.startsWith("quic://") ||
            normalized.startsWith("h3://")
        );
    }

    private static boolean looksLikeIpLiteral(String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        if (host.contains(":")) {
            return true;
        }
        for (int index = 0; index < host.length(); index++) {
            char value = host.charAt(index);
            if (!(Character.isDigit(value) || value == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigits(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static JSONObject decodeResponse(String base64Response) throws Exception {
        byte[] decoded = Base64.decode(base64Response, Base64.DEFAULT);
        JSONObject response = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        if (!response.optBoolean("success", false)) {
            throw new IllegalStateException(response.optString("error", "libXray request failed"));
        }
        return response;
    }

    private static final class ProtectBridgeDialerController implements DialerController {

        private final String socketName;

        private ProtectBridgeDialerController(String socketName) {
            this.socketName = socketName;
        }

        @Override
        public boolean protectFd(long fd) {
            if (fd <= 0L || TextUtils.isEmpty(socketName)) {
                return false;
            }
            LocalSocket socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
                try (
                    ParcelFileDescriptor adoptedFd = ParcelFileDescriptor.adoptFd((int) fd);
                    InputStream inputStream = socket.getInputStream();
                    OutputStream outputStream = socket.getOutputStream()
                ) {
                    FileDescriptor fileDescriptor = adoptedFd.getFileDescriptor();
                    if (fileDescriptor == null) {
                        return false;
                    }
                    socket.setFileDescriptorsForSend(new FileDescriptor[] { fileDescriptor });
                    outputStream.write(1);
                    outputStream.flush();
                    socket.setFileDescriptorsForSend(null);
                    int result = inputStream.read();
                    adoptedFd.detachFd();
                    return result == 1;
                }
            } catch (Exception ignored) {
                return false;
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
        }
    }
}
