package wings.v.qs;

import android.service.quicksettings.Tile;
import wings.v.ExternalActions;
import wings.v.core.BackendType;

public class VkBackendQuickSettingsTileService extends BaseQuickSettingsTileService {

    @Override
    protected void bindTile(Tile tile) {
        QuickSettingsTiles.bindBackendTile(this, tile, BackendType.VK_TURN_WIREGUARD);
    }

    @Override
    protected void handleTileClick() {
        ExternalActions.setBackend(this, BackendType.VK_TURN_WIREGUARD, true, true);
    }
}
