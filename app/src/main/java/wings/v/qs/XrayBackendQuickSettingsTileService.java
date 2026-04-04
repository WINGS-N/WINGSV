package wings.v.qs;

import android.service.quicksettings.Tile;

import wings.v.ExternalActions;
import wings.v.core.BackendType;

public class XrayBackendQuickSettingsTileService extends BaseQuickSettingsTileService {
    @Override
    protected void bindTile(Tile tile) {
        QuickSettingsTiles.bindBackendTile(this, tile, BackendType.XRAY);
    }

    @Override
    protected void handleTileClick() {
        ExternalActions.setBackend(this, BackendType.XRAY, true, true);
    }
}
