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
    private static final String EXTRA_REASON = "reason";
    private static final String RESET_ADDRESS_V4 = "172.31.255.1";
    private static final int RESET_PREFIX_V4 = 30;
    private static final long DEFAULT_HOLD_MS = 1_200L;
    private static final String UNKNOWN_REASON = "unspecified";

    private static final AtomicBoolean PULSE_IN_FLIGHT = new AtomicBoolean();

    /**
     * Establishes a throwaway VPN for holdMs, which revokes whoever holds the system's single VPN
     * slot. The reason is written to the runtime log next to the displacement itself: a backend
     * losing the slot surfaces only as a bare tunnel-DOWN, so without it there is no way to tell a
     * pulse we fired on purpose from a tunnel that died on its own.
     */
    public static void pulse(@Nullable Context context, long holdMs, String reason) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null) {
            return;
        }
        Intent intent = new Intent(appContext, EmergencyVpnResetService.class)
            .setAction(ACTION_PULSE)
            .putExtra(EXTRA_HOLD_MS, Math.max(250L, holdMs))
            .putExtra(EXTRA_REASON, reason);
        try {
            appContext.startService(intent);
        } catch (RuntimeException error) {
            ProxyTunnelService.writeRuntimeLogLine(
                "VPN reset pulse (" + normalizeReason(reason) + ") could not start: " + error
            );
        }
    }

    private static String normalizeReason(@Nullable String reason) {
        return reason == null || reason.trim().isEmpty() ? UNKNOWN_REASON : reason.trim();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ACTION_PULSE.equals(intent != null ? intent.getAction() : null)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String reason = normalizeReason(intent != null ? intent.getStringExtra(EXTRA_REASON) : null);
        if (!PULSE_IN_FLIGHT.compareAndSet(false, true)) {
            ProxyTunnelService.writeRuntimeLogLine(
                "VPN reset pulse (" + reason + ") skipped: another pulse is already in flight"
            );
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        long holdMs = intent != null ? intent.getLongExtra(EXTRA_HOLD_MS, DEFAULT_HOLD_MS) : DEFAULT_HOLD_MS;
        Thread pulseThread = new Thread(() -> runPulse(startId, holdMs, reason), "wingsv-vpn-reset");
        pulseThread.setDaemon(true);
        pulseThread.start();
        return START_NOT_STICKY;
    }

    private void runPulse(int startId, long holdMs, String reason) {
        ParcelFileDescriptor tunnel = null;
        try {
            if (VpnService.prepare(this) != null) {
                ProxyTunnelService.writeRuntimeLogLine(
                    "VPN reset pulse (" + reason + ") skipped: VPN permission not granted"
                );
                return;
            }
            Builder builder = new Builder()
                .setSession("WINGSV reset")
                .setMtu(1500)
                .addAddress(RESET_ADDRESS_V4, RESET_PREFIX_V4)
                .addRoute("0.0.0.0", 0);
            tunnel = builder.establish();
            if (tunnel == null) {
                ProxyTunnelService.writeRuntimeLogLine(
                    "VPN reset pulse (" + reason + ") failed: establish returned no descriptor"
                );
                return;
            }
            ProxyTunnelService.writeRuntimeLogLine(
                "VPN reset pulse (" + reason + "): displaced the active VPN, holding the slot for " + holdMs + "ms"
            );
            SystemClock.sleep(Math.max(250L, holdMs));
        } catch (RuntimeException error) {
            ProxyTunnelService.writeRuntimeLogLine("VPN reset pulse (" + reason + ") failed: " + error);
        } finally {
            if (tunnel != null) {
                try {
                    tunnel.close();
                } catch (Exception ignored) {}
                ProxyTunnelService.writeRuntimeLogLine("VPN reset pulse (" + reason + ") released the slot");
            }
            PULSE_IN_FLIGHT.set(false);
            stopSelf(startId);
        }
    }
}
