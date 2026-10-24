package wings.v.xray;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.text.TextUtils;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import libXray.DialerController;
import libXray.LibXray;
import libXray.ProcessFinder;
import org.json.JSONArray;
import org.json.JSONObject;
import wings.v.R;
import wings.v.WingsApplication;
import wings.v.core.XrayStore;
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

    /** Версия контракта libXray. Библиотека отбивает запрос с чужой */
    private static final int LIBXRAY_API_VERSION = 1;

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
    private static final AtomicReference<Context> UID_LOOKUP_CONTEXT = new AtomicReference<>();
    private static final String UDP_NETWORK = "udp";
    private static final int IPPROTO_TCP = 6;
    private static final int IPPROTO_UDP = 17;
    private static final java.util.concurrent.atomic.AtomicLong UID_LOOKUP_CALLS =
        new java.util.concurrent.atomic.AtomicLong();

    private static void diagAppend(Context context, String line) {
        if (context == null) return;
        try {
            File diagFile = new File(context.getFilesDir(), "uid-diag.log");
            try (FileOutputStream out = new FileOutputStream(diagFile, true)) {
                String ts = String.valueOf(System.currentTimeMillis());
                out.write((ts + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    // Ядро спрашивает владельца соединения, чтобы правила роутинга с полем
    // process выбирали приложения по UID. На андроиде без рута это умеет
    // только getConnectionOwnerUid, и он же единственный видит соединения,
    // которые идут через наш же туннель
    private static final ProcessFinder PROCESS_FINDER = new ProcessFinder() {
        @Override
        public long findProcessByConnection(String network, String srcIP, long srcPort, String destIP, long destPort) {
            long callIndex = UID_LOOKUP_CALLS.incrementAndGet();
            Context context = UID_LOOKUP_CONTEXT.get();
            if (context == null) {
                if (callIndex % 16 == 1) {
                    diagAppend(null, "no-context #" + callIndex);
                }
                return -1L;
            }
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) {
                if (callIndex % 16 == 1) {
                    diagAppend(context, "no-cm #" + callIndex);
                }
                return -1L;
            }
            try {
                InetSocketAddress local = socketAddress(srcIP, srcPort);
                InetSocketAddress remote = socketAddress(destIP, destPort);
                if (local == null || remote == null) {
                    if (callIndex % 16 == 1) {
                        diagAppend(context, "parse-fail " + srcIP + ":" + srcPort + " -> " + destIP + ":" + destPort);
                    }
                    return -1L;
                }
                int protocol = UDP_NETWORK.equals(network) ? IPPROTO_UDP : IPPROTO_TCP;
                int uid = cm.getConnectionOwnerUid(protocol, local, remote);
                if (uid != Process.INVALID_UID || callIndex < 200) {
                    diagAppend(
                        context,
                        "lookup #" +
                            callIndex +
                            " " +
                            network +
                            " " +
                            srcIP +
                            ":" +
                            srcPort +
                            " -> " +
                            destIP +
                            ":" +
                            destPort +
                            " uid=" +
                            uid
                    );
                }
                return uid == Process.INVALID_UID ? -1L : uid;
            } catch (SecurityException error) {
                diagAppend(context, "denied: " + error.getMessage());
                return -1L;
            } catch (RuntimeException error) {
                if (callIndex % 16 == 1) {
                    diagAppend(context, "exception #" + callIndex + ": " + error);
                }
                return -1L;
            }
        }
    };

    private static InetSocketAddress socketAddress(String ip, long port) {
        if (TextUtils.isEmpty(ip) || port < 0L || port > 65535L) {
            return null;
        }
        try {
            return new InetSocketAddress(InetAddress.getByName(ip), (int) port);
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

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
                throw new IllegalStateException(
                    WingsApplication.getStringSafe(R.string.xray_bridge_vpn_service_not_ready)
                );
            }
            ACTIVE_NETWORK_CONTROLLER.set(vpnService);
            ensureControllersRegisteredLocked();
        }
    }

    public static synchronized void prepareRuntimeDirect(String remoteDns, String directDns) {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            ACTIVE_NETWORK_CONTROLLER.set(DIRECT_NETWORK_CONTROLLER);
            ensureControllersRegisteredLocked();
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
            ensureControllersRegisteredLocked();
        }
    }

    public static String convertShareLinkToOutboundJson(String rawLink) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            JSONObject payload = new JSONObject();
            payload.put("text", rawLink);
            JSONObject response = invoke("convertShareLinksToXrayJson", payload);
            Object data = response.opt("data");
            if (data instanceof JSONObject) {
                return ((JSONObject) data).toString();
            }
            if (data != null) {
                return String.valueOf(data);
            }
            throw new IllegalStateException(WingsApplication.getStringSafe(R.string.xray_bridge_empty_outbound_config));
        }
    }

    public static String convertXrayJsonToShareLinks(String configJson) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            JSONObject payload = new JSONObject();
            payload.put("xrayJson", configJson);
            JSONObject response = invoke("convertXrayJsonToShareLinks", payload);
            JSONObject data = response.optJSONObject("data");
            if (data != null && !TextUtils.isEmpty(data.optString("links"))) {
                return data.optString("links");
            }
            throw new IllegalStateException(WingsApplication.getStringSafe(R.string.xray_bridge_empty_share_links));
        }
    }

    public static void runFromJson(Context context, String configJson, int tunFd) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            RUNTIME_STARTED.set(false);
            File datDir = ensureDatDir(context);
            // Дескриптор и каталог ассетов - окружение процесса, а не часть
            // запроса: ядро читает их, пока поднимает inbound
            LibXray.setAssetDir(datDir.getAbsolutePath());
            LibXray.setTunFd(tunFd);
            // Окно ожидания берётся из настроек, а UID приложений могли
            // поменяться с прошлого запуска, поэтому резолвер переставляется
            // на каждый старт - заодно сбрасывается его кеш
            registerUidProcessFinder(context);
            JSONObject payload = new JSONObject();
            payload.put("xrayJson", configJson);
            invoke("runXray", payload);
            RUNTIME_STARTED.set(true);
        }
    }

    public static long pingConfig(Context context, String configJson, int timeoutSeconds, String url) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            File datDir = ensureDatDir(context);
            LibXray.setAssetDir(datDir.getAbsolutePath());
            // Ядро само поднимает инстанс и ходит через указанный outbound,
            // локальный socks для замера больше не нужен
            JSONObject item = new JSONObject();
            item.put("xrayJson", configJson);
            item.put("outboundTag", XrayAutoSearchConfigFactory.PROXY_TAG);
            JSONObject payload = new JSONObject();
            payload.put("configs", new JSONArray().put(item));
            payload.put("timeout", timeoutSeconds);
            payload.put("url", url);
            JSONObject response = invoke("pingBatch", payload);
            JSONObject data = response.optJSONObject("data");
            JSONArray results = data == null ? null : data.optJSONArray("results");
            JSONObject result = results == null || results.length() == 0 ? null : results.optJSONObject(0);
            if (result == null || !result.optBoolean("success", false)) {
                return 0L;
            }
            return result.optLong("delay", 0L);
        }
    }

    public static void countGeoData(Context context, String name, String geoType) throws Exception {
        countGeoData(ensureDatDir(context), name, geoType);
    }

    public static void countGeoData(File datDir, String name, String geoType) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            JSONObject payload = new JSONObject();
            payload.put("datDir", datDir.getAbsolutePath());
            payload.put("name", trim(name));
            payload.put("geoType", trim(geoType));
            invoke("countGeoData", payload);
        }
    }

    public static JSONObject readGeoFiles(String configJson) throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            return new JSONObject(LibXray.readGeoFiles(configJson));
        }
    }

    public static void stop() throws Exception {
        ensureLoaded();
        synchronized (JNI_LOCK) {
            try {
                if (RUNTIME_STARTED.get()) {
                    invoke("stopXray", null);
                }
            } finally {
                RUNTIME_STARTED.set(false);
                ACTIVE_NETWORK_CONTROLLER.set(DIRECT_NETWORK_CONTROLLER);
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

    /**
     * Public hook for one-time warmup of the native library, typically
     * called from ProxyTunnelService.onCreate on a background thread so
     * the JNI link + Go runtime init happen off the user-perceived start
     * path. Cheap no-op on repeat calls.
     */
    public static void warmupRuntimeLoad() {
        ensureLoaded();
    }

    private static void ensureControllersRegisteredLocked() {
        if (CONTROLLERS_REGISTERED.compareAndSet(false, true)) {
            LibXray.registerDialerController(DELEGATING_CONTROLLER);
            LibXray.registerListenerController(DELEGATING_CONTROLLER);
            registerUidProcessFinder(UID_LOOKUP_CONTEXT.get());
        }
    }

    /**
     * Sets the application context used by {@link #UID_LOOKUP_CONTROLLER} to
     * obtain {@link ConnectivityManager#getConnectionOwnerUid}. The context
     * is stored as a static reference; pass an Application context to avoid
     * Activity leaks.
     */
    public static void setUidLookupContext(Context applicationContext) {
        UID_LOOKUP_CONTEXT.set(applicationContext);
        registerUidProcessFinder(applicationContext);
    }

    // Окно ожидания живёт в настройках, а регистрация идемпотентна, поэтому
    // резолвер переставляется заново, как только контекст появился
    private static void registerUidProcessFinder(Context context) {
        int lookupTimeoutMs = 0;
        if (context != null) {
            try {
                lookupTimeoutMs = XrayStore.getXraySettings(context).tunUidLookupTimeoutMs;
            } catch (Exception ignored) {
                lookupTimeoutMs = 0;
            }
        }
        LibXray.registerUIDProcessFinder(PROCESS_FINDER, Build.VERSION.SDK_INT, lookupTimeoutMs);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Один вызов в libXray. Библиотека свела всё к единственной двери, куда едет
     * имя метода и его данные, поэтому обёртка тут одна на всех.
     */
    private static JSONObject invoke(String method, JSONObject payload) throws Exception {
        JSONObject request = new JSONObject();
        request.put("apiVersion", LIBXRAY_API_VERSION);
        request.put("method", method);
        if (payload != null) {
            request.put("payload", payload);
        }
        JSONObject response = new JSONObject(LibXray.invoke(request.toString()));
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
