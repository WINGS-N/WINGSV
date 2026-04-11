package wings.v.service;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.util.Log;
import com.wireguard.android.backend.GoBackend;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({ "PMD.AvoidCatchingGenericException", "PMD.AvoidAccessibilityAlteration" })
final class GoBackendVpnAccess {

    private static final String TAG = "WINGSV/GoBackendVpn";
    private static final long SERVICE_WAIT_TIMEOUT_MS = 2_000L;

    private GoBackendVpnAccess() {}

    static VpnService ensureServiceStarted(Context context) {
        if (context == null) {
            return null;
        }
        context.startService(new Intent(context, GoBackend.VpnService.class));
        return awaitService(SERVICE_WAIT_TIMEOUT_MS);
    }

    static VpnService getServiceNow() {
        try {
            CompletableFuture<?> future = getVpnServiceFuture();
            Object value = future != null ? future.getNow(null) : null;
            return value instanceof VpnService ? (VpnService) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static void stopService(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.stopService(new Intent(context, GoBackend.VpnService.class));
        } catch (Exception ignored) {}
    }

    static boolean promoteServiceForeground(VpnService service, int notificationId, Notification notification) {
        if (service == null || notification == null) {
            return false;
        }
        try {
            service.startForeground(notificationId, notification);
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Unable to promote GoBackend VpnService to foreground", error);
            return false;
        }
    }

    private static VpnService awaitService(long timeoutMs) {
        try {
            CompletableFuture<?> future = getVpnServiceFuture();
            if (future == null) {
                return null;
            }
            Object value = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return value instanceof VpnService ? (VpnService) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static CompletableFuture<?> getVpnServiceFuture() {
        try {
            Field vpnServiceField = GoBackend.class.getDeclaredField("vpnService");
            vpnServiceField.setAccessible(true);
            Object value = vpnServiceField.get(null);
            return value instanceof CompletableFuture<?> ? (CompletableFuture<?>) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
