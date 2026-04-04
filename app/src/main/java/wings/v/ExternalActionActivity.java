package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class ExternalActionActivity extends AppCompatActivity {
    public static final String ACTION_START_TUNNEL = ExternalActions.ACTION_START_TUNNEL;
    public static final String ACTION_STOP_TUNNEL = ExternalActions.ACTION_STOP_TUNNEL;
    public static final String ACTION_SET_BACKEND = ExternalActions.ACTION_SET_BACKEND;
    public static final String ACTION_SET_BACKEND_XRAY = ExternalActions.ACTION_SET_BACKEND_XRAY;
    public static final String ACTION_SET_BACKEND_VK = ExternalActions.ACTION_SET_BACKEND_VK;

    public static final String EXTRA_BACKEND = ExternalActions.EXTRA_BACKEND;
    public static final String EXTRA_RECONNECT_IF_ACTIVE = ExternalActions.EXTRA_RECONNECT_IF_ACTIVE;

    public static final String BACKEND_XRAY = ExternalActions.BACKEND_XRAY;
    public static final String BACKEND_VK = ExternalActions.BACKEND_VK;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        finish();
        overridePendingTransition(0, 0);
    }

    private void handleIntent(@Nullable Intent intent) {
        Context appContext = getApplicationContext();
        ExternalActions.handleIntent(appContext, intent);
    }
}
