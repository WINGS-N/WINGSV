package wings.v;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.XrayStore;
import wings.v.qs.QuickSettingsTiles;
import wings.v.service.ProxyTunnelService;

public final class ExternalActions {
    public static final String ACTION_START_TUNNEL = "wings.v.intent.action.START_TUNNEL";
    public static final String ACTION_STOP_TUNNEL = "wings.v.intent.action.STOP_TUNNEL";
    public static final String ACTION_SET_BACKEND = "wings.v.intent.action.SET_BACKEND";
    public static final String ACTION_SET_BACKEND_XRAY = "wings.v.intent.action.SET_BACKEND_XRAY";
    public static final String ACTION_SET_BACKEND_VK = "wings.v.intent.action.SET_BACKEND_VK";

    public static final String EXTRA_BACKEND = "wings.v.intent.extra.BACKEND";
    public static final String EXTRA_RECONNECT_IF_ACTIVE = "wings.v.intent.extra.RECONNECT_IF_ACTIVE";

    public static final String BACKEND_XRAY = "xray";
    public static final String BACKEND_VK = "vk";

    private ExternalActions() {
    }

    public static void handleIntent(Context context, @Nullable Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        String action = intent.getAction();
        if (ACTION_START_TUNNEL.equals(action)) {
            startTunnel(appContext, true);
            return;
        }
        if (ACTION_STOP_TUNNEL.equals(action)) {
            stopTunnel(appContext);
            return;
        }

        BackendType targetBackend = null;
        if (ACTION_SET_BACKEND_XRAY.equals(action)) {
            targetBackend = BackendType.XRAY;
        } else if (ACTION_SET_BACKEND_VK.equals(action)) {
            targetBackend = BackendType.VK_TURN_WIREGUARD;
        } else if (ACTION_SET_BACKEND.equals(action)) {
            targetBackend = parseBackend(intent.getStringExtra(EXTRA_BACKEND));
        }
        if (targetBackend == null) {
            QuickSettingsTiles.requestRefresh(appContext);
            return;
        }
        boolean reconnectIfActive = intent.getBooleanExtra(EXTRA_RECONNECT_IF_ACTIVE, true);
        setBackend(appContext, targetBackend, reconnectIfActive, true);
    }

    public static void startTunnel(Context context, boolean transientLaunch) {
        Context appContext = context.getApplicationContext();
        if (transientLaunch) {
            AppPrefs.setExternalActionTransientLaunchPending(appContext, true);
        }
        ContextCompat.startForegroundService(
                appContext,
                ProxyTunnelService.createStartIntent(appContext)
        );
        QuickSettingsTiles.requestRefresh(appContext);
    }

    public static void stopTunnel(Context context) {
        Context appContext = context.getApplicationContext();
        ProxyTunnelService.requestStop(appContext);
        QuickSettingsTiles.requestRefresh(appContext);
    }

    public static boolean setBackend(Context context,
                                     BackendType targetBackend,
                                     boolean reconnectIfActive,
                                     boolean transientLaunch) {
        Context appContext = context.getApplicationContext();
        BackendType currentBackend = XrayStore.getBackendType(appContext);
        boolean changed = currentBackend != targetBackend;
        if (changed) {
            XrayStore.setBackendType(appContext, targetBackend);
        }
        if (changed && reconnectIfActive && ProxyTunnelService.isActive()) {
            if (transientLaunch) {
                AppPrefs.setExternalActionTransientLaunchPending(appContext, true);
            }
            ContextCompat.startForegroundService(
                    appContext,
                    ProxyTunnelService.createReconnectIntent(appContext)
            );
        }
        QuickSettingsTiles.requestRefresh(appContext);
        return changed;
    }

    @Nullable
    public static BackendType parseBackend(@Nullable String rawBackend) {
        String value = rawBackend != null ? rawBackend.trim().toLowerCase() : "";
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        if (BACKEND_XRAY.equals(value) || BackendType.XRAY.prefValue.equals(value)) {
            return BackendType.XRAY;
        }
        if (BACKEND_VK.equals(value)
                || "vk_turn_wireguard".equals(value)
                || BackendType.VK_TURN_WIREGUARD.prefValue.equals(value)) {
            return BackendType.VK_TURN_WIREGUARD;
        }
        return null;
    }
}
