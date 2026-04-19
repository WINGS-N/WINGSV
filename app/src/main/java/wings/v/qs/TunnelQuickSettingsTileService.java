package wings.v.qs;

import android.service.quicksettings.Tile;
import wings.v.ExternalActions;
import wings.v.service.ProxyTunnelService;

public class TunnelQuickSettingsTileService extends BaseQuickSettingsTileService {

    @Override
    protected void bindTile(Tile tile) {
        QuickSettingsTiles.bindTunnelTile(this, tile);
    }

    @Override
    protected void handleTileClick() {
        if (ProxyTunnelService.isActive()) {
            ExternalActions.stopTunnel(this);
            return;
        }
        ExternalActions.startTunnel(this, true);
    }
}
