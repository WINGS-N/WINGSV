package wings.v.service;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.amnezia.awg.backend.GoBackend;

@SuppressWarnings({ "PMD.AvoidCatchingGenericException", "PMD.AvoidAccessibilityAlteration" })
final class AwgBackendVpnAccess {

    private static final String TAG = "WINGSV/AwgBackendVpn";
    private static final long SERVICE_WAIT_TIMEOUT_MS = 2_000L;
    private static final long SERVICE_WAIT_POLL_MS = 200L;

    private AwgBackendVpnAccess() {}

    static VpnService ensureServiceStarted(Context context) {
        if (context == null) {
            return null;
        }
        context.startService(new Intent(context, GoBackend.VpnService.class));
        return awaitService(SERVICE_WAIT_TIMEOUT_MS);
    }

    static VpnService getServiceNow() {
        try {
            Object future = getVpnServiceFuture();
            if (future == null || !invokeIsDone(future)) {
                return null;
            }
            Object value = invokeTimedGet(future, 0L, TimeUnit.NANOSECONDS);
            return value instanceof VpnService ? (VpnService) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isServiceAlive() {
        return getServiceNow() != null;
    }

    static void stopService(Context context) {
        if (context == null) {
            return;
        }
        clearServiceOwner();
        try {
            context.stopService(new Intent(context, GoBackend.VpnService.class));
        } catch (Exception ignored) {}
    }

    static boolean waitForStopped(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 1L);
        while (System.currentTimeMillis() < deadline) {
            if (!isServiceAlive()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(SERVICE_WAIT_POLL_MS);
        }
        return !isServiceAlive();
    }

    static void clearServiceOwner() {
        VpnService service = getServiceNow();
        if (service == null) {
            return;
        }
        if (clearServiceOwnerWithSetter(service)) {
            return;
        }
        clearServiceOwnerField(service);
    }

    static boolean promoteServiceForeground(VpnService service, int notificationId, Notification notification) {
        if (service == null || notification == null) {
            return false;
        }
        try {
            service.startForeground(notificationId, notification);
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Unable to promote AmneziaWG VpnService to foreground", error);
            return false;
        }
    }

    private static boolean clearServiceOwnerWithSetter(VpnService service) {
        try {
            Method setOwnerMethod = service.getClass().getDeclaredMethod("setOwner", GoBackend.class);
            setOwnerMethod.setAccessible(true);
            setOwnerMethod.invoke(service, new Object[] { null });
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void clearServiceOwnerField(VpnService service) {
        try {
            Field ownerField = service.getClass().getDeclaredField("owner");
            ownerField.setAccessible(true);
            ownerField.set(service, null);
        } catch (Exception ignored) {}
    }

    private static VpnService awaitService(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 1L);
        while (System.currentTimeMillis() < deadline) {
            VpnService service = getServiceNow();
            if (service != null) {
                return service;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(SERVICE_WAIT_POLL_MS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return getServiceNow();
    }

    private static Object getVpnServiceFuture() {
        try {
            Field vpnServiceField = GoBackend.class.getDeclaredField("vpnService");
            vpnServiceField.setAccessible(true);
            return vpnServiceField.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean invokeIsDone(Object future) throws Exception {
        Method isDoneMethod = future.getClass().getDeclaredMethod("isDone");
        isDoneMethod.setAccessible(true);
        Object value = isDoneMethod.invoke(future);
        return value instanceof Boolean && (Boolean) value;
    }

    private static Object invokeTimedGet(Object future, long timeout, TimeUnit unit) throws Exception {
        Method getMethod = future.getClass().getDeclaredMethod("get", long.class, TimeUnit.class);
        getMethod.setAccessible(true);
        return getMethod.invoke(future, timeout, unit);
    }
}
