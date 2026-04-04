package wings.v.service;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import libXray.DialerController;
import wings.v.MainActivity;
import wings.v.core.AppPrefs;
import wings.v.core.ProxySettings;

public class XrayVpnService extends VpnService implements DialerController {
    private static final long SERVICE_WAIT_TIMEOUT_MS = 2_000L;
    private static final String VPN_ADDRESS_V4 = "172.19.0.1";
    private static final int VPN_PREFIX_V4 = 30;
    private static final String VPN_ADDRESS_V6 = "fd19:19::1";
    private static final int VPN_PREFIX_V6 = 126;
    private static final int DEFAULT_MTU = 1500;

    private static volatile CompletableFuture<XrayVpnService> serviceFuture = new CompletableFuture<>();

    private final Object tunnelLock = new Object();
    private volatile boolean shuttingDown;
    private ParcelFileDescriptor tunnelFd;
    private String tunnelSignature;

    public static XrayVpnService ensureServiceStarted(Context context) {
        if (context == null) {
            return null;
        }
        XrayVpnService existing = getServiceNow();
        if (existing != null && existing.isReusable()) {
            return existing;
        }
        resetServiceFuture();
        context.startService(new Intent(context, XrayVpnService.class));
        return awaitService(SERVICE_WAIT_TIMEOUT_MS);
    }

    @Nullable
    public static XrayVpnService getServiceNow() {
        try {
            return serviceFuture.getNow(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void stopService(Context context) {
        if (context == null) {
            return;
        }
        try {
            XrayVpnService service = getServiceNow();
            if (service != null) {
                service.shuttingDown = true;
            }
            resetServiceFuture();
            if (service != null) {
                service.shutdown();
            }
            context.stopService(new Intent(context, XrayVpnService.class));
        } catch (Exception ignored) {
        }
    }

    private static XrayVpnService awaitService(long timeoutMs) {
        try {
            return serviceFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        shuttingDown = false;
        serviceFuture.complete(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        shutdownTunnel();
        if (getServiceNow() == this) {
            resetServiceFuture();
        }
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        shutdown();
        super.onRevoke();
    }

    public int establishTunnel(ProxySettings settings) {
        if (shuttingDown) {
            throw new IllegalStateException("Xray VPN service is shutting down");
        }
        ProxySettings value = settings != null ? settings : new ProxySettings();
        synchronized (tunnelLock) {
            String signature = buildSignature(value);
            if (tunnelFd != null && TextUtils.equals(tunnelSignature, signature)) {
                return tunnelFd.getFd();
            }

            closeTunnelLocked();

            Builder builder = new Builder()
                    .setSession("WINGSV Xray")
                    .setMtu(DEFAULT_MTU)
                    .addAddress(VPN_ADDRESS_V4, VPN_PREFIX_V4)
                    .addRoute("0.0.0.0", 0);

            if (value.xraySettings == null || value.xraySettings.ipv6) {
                builder.addAddress(VPN_ADDRESS_V6, VPN_PREFIX_V6);
                builder.addRoute("::", 0);
            }

            addDnsServers(builder, value);
            applyAppRouting(builder, value);

            Intent configureIntent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            builder.setConfigureIntent(PendingIntent.getActivity(
                    this,
                    201,
                    configureIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            ));

            ParcelFileDescriptor established = builder.establish();
            if (established == null) {
                throw new IllegalStateException("Не удалось открыть Xray TUN");
            }
            tunnelFd = established;
            tunnelSignature = signature;
            return established.getFd();
        }
    }

    @Override
    public boolean protectFd(long fd) {
        return protect((int) fd);
    }

    private void applyAppRouting(Builder builder, ProxySettings settings) {
        Set<String> packages = AppPrefs.getAppRoutingPackages(this);
        if (packages.isEmpty()) {
            return;
        }
        try {
            if (AppPrefs.isAppRoutingBypassEnabled(this)) {
                for (String packageName : packages) {
                    builder.addDisallowedApplication(packageName);
                }
            } else {
                for (String packageName : packages) {
                    builder.addAllowedApplication(packageName);
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось применить app routing для Xray", error);
        }
    }

    private void addDnsServers(Builder builder, ProxySettings settings) {
        String remoteDns = settings.xraySettings != null ? settings.xraySettings.remoteDns : null;
        String directDns = settings.xraySettings != null ? settings.xraySettings.directDns : null;
        String advertisedDns = resolveAdvertisedDnsForVpn(trim(remoteDns), trim(directDns));
        addDnsServer(builder, advertisedDns);
    }

    private String resolveAdvertisedDnsForVpn(String remoteDns, String directDns) {
        String advertised = normalizeDnsServerForVpn(remoteDns);
        if (!TextUtils.isEmpty(advertised)) {
            return advertised;
        }
        advertised = normalizeDnsServerForVpn(directDns);
        if (!TextUtils.isEmpty(advertised)) {
            return advertised;
        }
        return "1.1.1.1";
    }

    private void addDnsServer(Builder builder, String value) {
        String normalized = normalizeDnsServerForVpn(trim(value));
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        try {
            builder.addDnsServer(normalized);
        } catch (Exception ignored) {
        }
    }

    private String buildSignature(ProxySettings settings) {
        StringBuilder builder = new StringBuilder();
        builder.append(settings != null && settings.xraySettings != null && settings.xraySettings.ipv6);
        builder.append('|').append(AppPrefs.isAppRoutingBypassEnabled(this));
        for (String packageName : AppPrefs.getAppRoutingPackages(this)) {
            builder.append('|').append(packageName);
        }
        builder.append('|').append(trim(settings != null && settings.xraySettings != null ? settings.xraySettings.remoteDns : null));
        builder.append('|').append(trim(settings != null && settings.xraySettings != null ? settings.xraySettings.directDns : null));
        return builder.toString();
    }

    private void closeTunnelLocked() {
        if (tunnelFd != null) {
            try {
                tunnelFd.close();
            } catch (Exception ignored) {
            }
            tunnelFd = null;
            tunnelSignature = null;
        }
    }

    public void shutdown() {
        shuttingDown = true;
        shutdownTunnel();
        stopSelf();
    }

    private void shutdownTunnel() {
        synchronized (tunnelLock) {
            closeTunnelLocked();
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeDnsServerForVpn(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        if (value.startsWith("https://")
                || value.startsWith("tls://")
                || value.startsWith("quic://")
                || value.startsWith("h3://")) {
            return "";
        }
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing > 0) {
                return value.substring(1, closing);
            }
            return value;
        }
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            String host = value.substring(0, firstColon);
            String portCandidate = value.substring(firstColon + 1);
            if (!TextUtils.isEmpty(host) && isDigits(portCandidate)) {
                return host;
            }
        }
        return value;
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

    private boolean isReusable() {
        return !shuttingDown;
    }

    private static void resetServiceFuture() {
        serviceFuture = new CompletableFuture<>();
    }
}
