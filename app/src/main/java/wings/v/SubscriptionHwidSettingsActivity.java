package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import wings.v.databinding.ActivitySubscriptionHwidSettingsBinding;
import wings.v.ui.SubscriptionHwidSettingsFragment;

public class SubscriptionHwidSettingsActivity extends AppCompatActivity {

    public static Intent createIntent(Context context) {
        return new Intent(context, SubscriptionHwidSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivitySubscriptionHwidSettingsBinding binding = ActivitySubscriptionHwidSettingsBinding.inflate(
            getLayoutInflater()
        );
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.subscription_hwid_settings_container, new SubscriptionHwidSettingsFragment())
                .commit();
        }
    }
}
