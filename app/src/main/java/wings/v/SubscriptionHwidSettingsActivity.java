package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.databinding.ActivitySubscriptionHwidSettingsBinding;
import wings.v.ui.SubscriptionHwidSettingsFragment;

/** Hosts the subscription HWID settings screen. */
public class SubscriptionHwidSettingsActivity extends AppCompatActivity {

    /** Required empty constructor. */
    public SubscriptionHwidSettingsActivity() {
        super();
    }

    /** Creates an intent for the subscription HWID settings screen. */
    public static Intent createIntent(final Context context) {
        return new Intent(context, SubscriptionHwidSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivitySubscriptionHwidSettingsBinding binding = ActivitySubscriptionHwidSettingsBinding.inflate(
            getLayoutInflater()
        );
        setContentView(binding.getRoot());
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.subscription_hwid_settings_container, new SubscriptionHwidSettingsFragment())
                .commit();
        }
    }
}
