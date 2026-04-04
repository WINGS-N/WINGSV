package wings.v.qs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public abstract class BaseQuickSettingsTileService extends TileService {
    private boolean refreshReceiverRegistered;
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshTile();
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        registerRefreshReceiverIfNeeded();
        refreshTile();
    }

    @Override
    public void onStopListening() {
        unregisterRefreshReceiver();
        super.onStopListening();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        refreshTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (isLocked()) {
            unlockAndRun(this::performClickAction);
            return;
        }
        performClickAction();
    }

    private void performClickAction() {
        handleTileClick();
        QuickSettingsTiles.requestRefresh(getApplicationContext());
        refreshTile();
    }

    protected void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        bindTile(tile);
        tile.updateTile();
    }

    protected abstract void bindTile(Tile tile);

    protected abstract void handleTileClick();

    private void registerRefreshReceiverIfNeeded() {
        if (refreshReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(QuickSettingsTiles.ACTION_REFRESH_TILES);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(refreshReceiver, filter);
            }
            refreshReceiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void unregisterRefreshReceiver() {
        if (!refreshReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(refreshReceiver);
        } catch (Exception ignored) {
        } finally {
            refreshReceiverRegistered = false;
        }
    }
}
