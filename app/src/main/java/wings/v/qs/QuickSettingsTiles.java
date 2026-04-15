package wings.v.qs;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import androidx.annotation.DrawableRes;
import wings.v.R;
import wings.v.core.BackendType;
import wings.v.core.XrayStore;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
    }
)
public final class QuickSettingsTiles {

    public static final String ACTION_REFRESH_TILES = "wings.v.intent.action.REFRESH_QS_TILES";

    private QuickSettingsTiles() {}

    public static void requestRefresh(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        Context appContext = context.getApplicationContext();
        try {
            appContext.sendBroadcast(
                new android.content.Intent(ACTION_REFRESH_TILES).setPackage(appContext.getPackageName())
            );
        } catch (Exception ignored) {}
        requestListening(appContext, TunnelQuickSettingsTileService.class);
        requestListening(appContext, XrayBackendQuickSettingsTileService.class);
        requestListening(appContext, VkBackendQuickSettingsTileService.class);
    }

    private static void requestListening(Context context, Class<? extends TileService> serviceClass) {
        try {
            TileService.requestListeningState(context, new ComponentName(context, serviceClass));
        } catch (Exception ignored) {}
    }

    public static void bindTunnelTile(Context context, Tile tile) {
        boolean active = ProxyTunnelService.isActive();
        bindCommon(
            context,
            tile,
            R.string.qs_tile_tunnel_label,
            active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE,
            active
                ? (ProxyTunnelService.isStopping()
                      ? R.string.service_stopping
                      : ProxyTunnelService.isConnecting()
                          ? R.string.qs_tile_status_connecting
                          : R.string.qs_tile_status_on)
                : R.string.qs_tile_status_off,
            R.drawable.ic_power
        );
    }

    public static void bindBackendTile(Context context, Tile tile, BackendType backendType) {
        BackendType currentBackend = XrayStore.getBackendType(context);
        boolean selected =
            backendType == BackendType.XRAY
                ? currentBackend != null && currentBackend.usesXrayCore()
                : currentBackend == backendType;
        @DrawableRes
        int iconRes = backendType == BackendType.XRAY ? R.drawable.ic_profiles : R.drawable.ic_sharing_nav;
        int labelRes =
            backendType == BackendType.XRAY ? R.string.qs_tile_backend_xray_label : R.string.qs_tile_backend_vk_label;
        bindCommon(context, tile, labelRes, selected ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE, 0, iconRes);
    }

    private static void bindCommon(
        Context context,
        Tile tile,
        int labelRes,
        int state,
        int subtitleRes,
        @DrawableRes int iconRes
    ) {
        if (tile == null) {
            return;
        }
        String label = context.getString(labelRes);
        String subtitle = subtitleRes != 0 ? context.getString(subtitleRes) : null;
        tile.setLabel(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(subtitle);
        }
        if (!TextUtils.isEmpty(subtitle)) {
            tile.setContentDescription(label + ", " + subtitle);
        } else {
            tile.setContentDescription(label);
        }
        tile.setState(state);
        tile.setIcon(Icon.createWithResource(context, iconRes));
    }
}
