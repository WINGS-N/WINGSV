package wings.v.service;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings({ "PMD.DoNotUseThreads", "PMD.AvoidCatchingGenericException", "PMD.CommentRequired" })
public final class EmergencyVpnResetService extends VpnService {

    private static final String ACTION_PULSE = "wings.v.action.EMERGENCY_VPN_RESET";
    private static final String EXTRA_HOLD_MS = "hold_ms";
    private static final String RESET_ADDRESS_V4 = "172.31.255.1";
    private static final int RESET_PREFIX_V4 = 30;
    private static final long DEFAULT_HOLD_MS = 1_200L;

    private static final AtomicBoolean PULSE_IN_FLIGHT = new AtomicBoolean();

    public static void pulse(@Nullable Context context, long holdMs) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null) {
            return;
        }
        Intent intent = new Intent(appContext, EmergencyVpnResetService.class)
            .setAction(ACTION_PULSE)
            .putExtra(EXTRA_HOLD_MS, Math.max(250L, holdMs));
        try {
            appContext.startService(intent);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ACTION_PULSE.equals(intent != null ? intent.getAction() : null)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!PULSE_IN_FLIGHT.compareAndSet(false, true)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        long holdMs = intent != null ? intent.getLongExtra(EXTRA_HOLD_MS, DEFAULT_HOLD_MS) : DEFAULT_HOLD_MS;
        Thread pulseThread = new Thread(() -> runPulse(startId, holdMs), "wingsv-vpn-reset");
        pulseThread.setDaemon(true);
        pulseThread.start();
        return START_NOT_STICKY;
    }

    private void runPulse(int startId, long holdMs) {
        ParcelFileDescriptor tunnel = null;
        try {
            if (VpnService.prepare(this) != null) {
                return;
            }
            Builder builder = new Builder()
                .setSession("WINGSV reset")
                .setMtu(1500)
                .addAddress(RESET_ADDRESS_V4, RESET_PREFIX_V4)
                .addRoute("0.0.0.0", 0);
            tunnel = builder.establish();
            if (tunnel == null) {
                return;
            }
            SystemClock.sleep(Math.max(250L, holdMs));
        } catch (RuntimeException ignored) {
        } finally {
            if (tunnel != null) {
                try {
                    tunnel.close();
                } catch (Exception ignored) {}
            }
            PULSE_IN_FLIGHT.set(false);
            stopSelf(startId);
        }
    }
}
