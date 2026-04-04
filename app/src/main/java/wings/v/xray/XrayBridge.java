package wings.v.xray;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import libXray.LibXray;
import wings.v.service.XrayVpnService;

public final class XrayBridge {
    private static final AtomicBoolean LOADED = new AtomicBoolean();

    private XrayBridge() {
    }

    public static synchronized void prepareRuntime(XrayVpnService vpnService,
                                                   String remoteDns,
                                                   String directDns) {
        ensureLoaded();
        if (vpnService == null) {
            throw new IllegalStateException("Xray VpnService не готов");
        }
        LibXray.registerDialerController(vpnService);
        LibXray.registerListenerController(vpnService);
        String runtimeDns = resolveBootstrapDnsDialTarget(remoteDns, directDns);
        if (!TextUtils.isEmpty(runtimeDns)) {
            LibXray.initDns(vpnService, runtimeDns);
        } else {
            LibXray.resetDns();
        }
    }

    public static String convertShareLinkToOutboundJson(String rawLink) throws Exception {
        ensureLoaded();
        String request = Base64.encodeToString(
                rawLink.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP
        );
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

    public static void runFromJson(Context context, String configJson, int tunFd) throws Exception {
        ensureLoaded();
        File datDir = ensureDatDir(context);
        LibXray.setTunFd(tunFd);
        String request = LibXray.newXrayRunFromJSONRequest(
                datDir.getAbsolutePath(),
                "",
                configJson
        );
        decodeResponse(LibXray.runXrayFromJSON(request));
    }

    public static void stop() throws Exception {
        ensureLoaded();
        decodeResponse(LibXray.stopXray());
        LibXray.resetDns();
    }

    public static boolean isRunning() {
        ensureLoaded();
        return LibXray.getXrayState();
    }

    private static File ensureDatDir(Context context) {
        File datDir = new File(context.getFilesDir(), "xray/dat");
        if (!datDir.exists()) {
            datDir.mkdirs();
        }
        return datDir;
    }

    private static void ensureLoaded() {
        if (LOADED.compareAndSet(false, true)) {
            LibXray.touch();
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
        String normalized = value.toLowerCase();
        return normalized.startsWith("https://")
                || normalized.startsWith("tls://")
                || normalized.startsWith("quic://")
                || normalized.startsWith("h3://");
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
}
