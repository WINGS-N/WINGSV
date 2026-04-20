package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.databinding.ActivitySubscriptionUpdateSettingsBinding;
import wings.v.ui.SubscriptionUpdateSettingsFragment;

public class SubscriptionUpdateSettingsActivity extends AppCompatActivity {

    public static Intent createIntent(Context context) {
        return new Intent(context, SubscriptionUpdateSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        ActivitySubscriptionUpdateSettingsBinding binding = ActivitySubscriptionUpdateSettingsBinding.inflate(
            getLayoutInflater()
        );
        setContentView(binding.getRoot());
        ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.subscription_update_settings_container, new SubscriptionUpdateSettingsFragment())
                .commit();
        }
    }
}
