package wings.v.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.ByeDpiSettings;
import wings.v.core.ByeDpiStore;
import wings.v.core.XrayProfile;
import wings.v.core.XraySettings;
import wings.v.xray.XrayAutoSearchConfigFactory;
import wings.v.xray.XrayBridge;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.AvoidCatchingGenericException",
        "PMD.AvoidFileStream",
        "PMD.TooManyMethods",
        "PMD.CommentRequired",
        "PMD.ShortVariable",
    }
)
public abstract class XrayRealDelayTestService extends Service {

    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_FAILED = 2;
    public static final String RESULT_PROFILE_ID = "profile_id";
    public static final String RESULT_PING_KEY = "ping_key";
    public static final String RESULT_LATENCY_MS = "latency_ms";
    private static final int WORKER_COUNT = 4;
    private static final int REAL_DELAY_TIMEOUT_SECONDS = 8;
    private static final String ACTION_TEST = "wings.v.intent.action.XRAY_REAL_DELAY_TEST";
    private static final String REAL_DELAY_TEST_URL = "https://cp.cloudflare.com/generate_204";
    private static final String EXTRA_REQUEST = "request";
    private static final String EXTRA_RECEIVER = "receiver";
    private static final String REQUEST_PROFILE_ID = "profile_id";
    private static final String REQUEST_PROFILE_TITLE = "profile_title";
    private static final String REQUEST_PROFILE_RAW_LINK = "profile_raw_link";
    private static final String REQUEST_PROFILE_SUBSCRIPTION_ID = "profile_subscription_id";
    private static final String REQUEST_PROFILE_SUBSCRIPTION_TITLE = "profile_subscription_title";
    private static final String REQUEST_PROFILE_ADDRESS = "profile_address";
    private static final String REQUEST_PROFILE_PORT = "profile_port";
    private static final String REQUEST_PROFILE_PING_KEY = "profile_ping_key";
    private static final String REQUEST_XRAY_ALLOW_INSECURE = "xray_allow_insecure";
    private static final String REQUEST_XRAY_REMOTE_DNS = "xray_remote_dns";
    private static final String REQUEST_XRAY_DIRECT_DNS = "xray_direct_dns";
    private static final String REQUEST_XRAY_IPV6 = "xray_ipv6";
    private static final String REQUEST_XRAY_SNIFFING = "xray_sniffing";
    private static final String REQUEST_XRAY_PROXY_QUIC = "xray_proxy_quic";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static int workerCount() {
        return WORKER_COUNT;
    }

    public static boolean startTest(
        Context context,
        int workerIndex,
        XrayProfile profile,
        String pingKey,
        XraySettings settings,
        ResultReceiver receiver
    ) {
        if (context == null || profile == null || TextUtils.isEmpty(profile.rawLink) || receiver == null) {
            return false;
        }
        Intent intent = new Intent(context, workerClass(workerIndex)).setAction(ACTION_TEST);
        intent.putExtra(EXTRA_REQUEST, buildRequest(profile, pingKey, settings));
        intent.putExtra(EXTRA_RECEIVER, receiver);
        try {
            return context.startService(intent) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Class<? extends XrayRealDelayTestService> workerClass(int workerIndex) {
        switch (Math.floorMod(workerIndex, WORKER_COUNT)) {
            case 1:
                return XrayRealDelayTestWorker1Service.class;
            case 2:
                return XrayRealDelayTestWorker2Service.class;
            case 3:
                return XrayRealDelayTestWorker3Service.class;
            case 0:
            default:
                return XrayRealDelayTestWorker0Service.class;
        }
    }

    private static Bundle buildRequest(XrayProfile profile, String pingKey, @Nullable XraySettings settings) {
        Bundle bundle = new Bundle();
        bundle.putString(REQUEST_PROFILE_ID, profile.id);
        bundle.putString(REQUEST_PROFILE_TITLE, profile.title);
        bundle.putString(REQUEST_PROFILE_RAW_LINK, profile.rawLink);
        bundle.putString(REQUEST_PROFILE_SUBSCRIPTION_ID, profile.subscriptionId);
        bundle.putString(REQUEST_PROFILE_SUBSCRIPTION_TITLE, profile.subscriptionTitle);
        bundle.putString(REQUEST_PROFILE_ADDRESS, profile.address);
        bundle.putInt(REQUEST_PROFILE_PORT, profile.port);
        bundle.putString(REQUEST_PROFILE_PING_KEY, pingKey);
        if (settings != null) {
            bundle.putBoolean(REQUEST_XRAY_ALLOW_INSECURE, settings.allowInsecure);
            bundle.putString(REQUEST_XRAY_REMOTE_DNS, settings.remoteDns);
            bundle.putString(REQUEST_XRAY_DIRECT_DNS, settings.directDns);
            bundle.putBoolean(REQUEST_XRAY_IPV6, settings.ipv6);
            bundle.putBoolean(REQUEST_XRAY_SNIFFING, settings.sniffingEnabled);
            bundle.putBoolean(REQUEST_XRAY_PROXY_QUIC, settings.proxyQuicEnabled);
        }
        return bundle;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || !TextUtils.equals(ACTION_TEST, intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        Bundle request = intent.getBundleExtra(EXTRA_REQUEST);
        ResultReceiver receiver = intent.getParcelableExtra(EXTRA_RECEIVER);
        executor.execute(() -> {
            handleRequest(request, receiver);
            stopSelf(startId);
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void handleRequest(@Nullable Bundle request, @Nullable ResultReceiver receiver) {
        if (request == null || receiver == null) {
            return;
        }
        String profileId = request.getString(REQUEST_PROFILE_ID, "");
        String pingKey = request.getString(REQUEST_PROFILE_PING_KEY, "");
        int latencyMs = runRealDelay(request);
        Bundle result = new Bundle();
        result.putString(RESULT_PROFILE_ID, profileId);
        result.putString(RESULT_PING_KEY, pingKey);
        result.putInt(RESULT_LATENCY_MS, Math.max(0, latencyMs));
        receiver.send(latencyMs > 0 ? RESULT_SUCCESS : RESULT_FAILED, result);
    }

    private int runRealDelay(Bundle request) {
        File configFile = null;
        try {
            XrayProfile profile = readProfile(request);
            int localProxyPort = findAvailableTcpPort();
            XraySettings settings = buildRealDelaySettings(readSettings(request), localProxyPort);
            ByeDpiSettings byeDpiSettings = ByeDpiStore.getSettings(getApplicationContext());
            boolean useByeDpi = byeDpiSettings != null && byeDpiSettings.launchOnXrayStart;
            String configJson = XrayAutoSearchConfigFactory.buildConfigJson(
                getApplicationContext(),
                profile,
                settings,
                localProxyPort,
                byeDpiSettings,
                useByeDpi
            );
            configFile = writeConnectionTestConfig(configJson);
            String protectSocketName = RuntimeStateStore.readProtectSocketName();
            if (!TextUtils.isEmpty(protectSocketName)) {
                XrayBridge.prepareRuntimeViaProtectSocket(protectSocketName, settings.remoteDns, settings.directDns);
            } else {
                XrayBridge.prepareRuntimeDirect(settings.remoteDns, settings.directDns);
            }
            long delayMs = XrayBridge.pingConfig(
                getApplicationContext(),
                configFile,
                REAL_DELAY_TIMEOUT_SECONDS,
                REAL_DELAY_TEST_URL,
                "socks://127.0.0.1:" + localProxyPort
            );
            if (delayMs <= 0L || delayMs > Integer.MAX_VALUE) {
                return 0;
            }
            return Math.max((int) delayMs, 1);
        } catch (Exception ignored) {
            return 0;
        } finally {
            if (configFile != null && configFile.exists()) {
                configFile.delete();
            }
        }
    }

    private XrayProfile readProfile(Bundle request) {
        return new XrayProfile(
            request.getString(REQUEST_PROFILE_ID, ""),
            request.getString(REQUEST_PROFILE_TITLE, ""),
            request.getString(REQUEST_PROFILE_RAW_LINK, ""),
            request.getString(REQUEST_PROFILE_SUBSCRIPTION_ID, ""),
            request.getString(REQUEST_PROFILE_SUBSCRIPTION_TITLE, ""),
            request.getString(REQUEST_PROFILE_ADDRESS, ""),
            request.getInt(REQUEST_PROFILE_PORT, 0)
        );
    }

    private XraySettings readSettings(Bundle request) {
        XraySettings settings = new XraySettings();
        settings.allowInsecure = request.getBoolean(REQUEST_XRAY_ALLOW_INSECURE, false);
        settings.remoteDns = request.getString(REQUEST_XRAY_REMOTE_DNS, "");
        settings.directDns = request.getString(REQUEST_XRAY_DIRECT_DNS, "");
        settings.ipv6 = request.getBoolean(REQUEST_XRAY_IPV6, false);
        settings.sniffingEnabled = request.getBoolean(REQUEST_XRAY_SNIFFING, true);
        settings.proxyQuicEnabled = request.getBoolean(REQUEST_XRAY_PROXY_QUIC, false);
        return settings;
    }

    private XraySettings buildRealDelaySettings(XraySettings source, int localProxyPort) {
        XraySettings settings = source.copy();
        settings.allowLan = false;
        settings.localProxyEnabled = true;
        settings.localProxyAuthEnabled = false;
        settings.localProxyUsername = "";
        settings.localProxyPassword = "";
        settings.localProxyPort = localProxyPort;
        return settings;
    }

    private int findAvailableTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private File writeConnectionTestConfig(String configJson) throws IOException {
        File dir = new File(getCacheDir(), "xray/profile-tests");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, "real-delay-" + System.nanoTime() + ".json");
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write(configJson.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
