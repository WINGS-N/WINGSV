package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.core.FederationSubscription;

/** Подписка федерации: адрес, состояние и обновление вручную */
public final class FederationSubscriptionActivity extends AppCompatActivity {

    private TextView address;
    private TextView state;

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, FederationSubscriptionActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_federation_subscription);

        ToolbarLayout toolbar = findViewById(R.id.toolbar_layout);
        toolbar.setShowNavigationButtonAsBack(true);

        address = findViewById(R.id.federation_subscription_address);
        state = findViewById(R.id.federation_subscription_state);
        findViewById(R.id.federation_subscription_copy).setOnClickListener(v -> copyAddress());

        render();
    }

    private void render() {
        String url = FederationSubscription.url(this);
        boolean present = !TextUtils.isEmpty(url);
        address.setText(present ? url : "");
        address.setVisibility(present ? View.VISIBLE : View.GONE);
        findViewById(R.id.federation_subscription_copy).setVisibility(present ? View.VISIBLE : View.GONE);
        state.setText(
            present ? R.string.wings_account_subscription_added_state : R.string.wings_account_no_subscription
        );
    }

    private void copyAddress() {
        String url = FederationSubscription.url(this);
        if (TextUtils.isEmpty(url)) {
            return;
        }
        android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("subscription", url));
            Toast.makeText(this, R.string.wings_account_subscription_copied, Toast.LENGTH_SHORT).show();
        }
    }
}
