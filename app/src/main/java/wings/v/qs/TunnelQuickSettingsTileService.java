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
        // A tunnel the app no longer knows about can still exist: the module keeps a
        // session alive when the app is killed, deliberately, so the user does not lose
        // the VPN to the low-memory killer. The tile is the only way back to it once
        // that has happened, so check before assuming a tap means "connect".
        if (RootdTileActions.stopOrphanedSession(getApplicationContext())) {
            return;
        }
        ExternalActions.startTunnel(this, true);
    }
}
